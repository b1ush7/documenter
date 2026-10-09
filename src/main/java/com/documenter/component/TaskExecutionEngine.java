package com.documenter.component;

import com.documenter.configuration.TaskExecutorConfig;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskStatus;
import com.documenter.enums.TaskType;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.TaskHandler;
import com.documenter.service.impl.TaskExecutionContextImpl;
import com.documenter.service.impl.TaskExecutionContextImpl.TaskCancelledException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 任务执行引擎（TODO 3.4）。
 *
 * <p>职责边界：引擎只管「状态流转、超时、取消、重试、失败记录」，
 * 具体处理逻辑交给 {@link TaskHandler}。这样替换处理逻辑时不会影响状态机。
 *
 * <p>超时实现：处理器被提交到线程池执行，主循环用 {@code Future.get(interval)}
 * 轮询。超时后 {@code cancel(true)} 发送中断信号；处理器必须在耗时边界响应中断
 * 或调用 {@code assertNotCancelled()}，否则线程会被拖住——这一点写进了 TaskHandler
 * 的接口注释，属于对实现方的硬性要求。做不到时线程池的线程会被长期占用，
 * 表现为「任务排队但没人处理」。
 */
@Slf4j
@Component
public class TaskExecutionEngine {

    /** 取消只对未进入终态的任务生效。 */
    private static final List<String> CANCELLABLE_FROM = List.of("PENDING", "RUNNING");
    /** 成功只允许从 RUNNING 流转，避免把 PENDING 直接置为成功。 */
    private static final List<String> RUNNING_ONLY = List.of("RUNNING");

    /** 轮询取消标记的间隔：太小会频繁打库，太大会让取消感知迟钝。 */
    private static final long CANCEL_POLL_MILLIS = 200;

    private final ProcessingTaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final TaskHandlerRegistry handlerRegistry;
    private final TaskExecutorProperties properties;
    private final ThreadPoolTaskExecutor executor;

    public TaskExecutionEngine(ProcessingTaskMapper taskMapper,
                               TaskStepMapper stepMapper,
                               TaskHandlerRegistry handlerRegistry,
                               TaskExecutorProperties properties,
                               @Qualifier(TaskExecutorConfig.TASK_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.handlerRegistry = handlerRegistry;
        this.properties = properties;
        this.executor = executor;
    }

    /** 异步提交任务。调用方需先持久化任务记录。 */
    public void submit(Long taskId) {
        executor.execute(() -> execute(taskId));
    }

    /**
     * 执行一个任务，按需重试。
     *
     * <p>重试只针对「处理器抛异常」；用户取消不重试，
     * 超时也不重试——超时说明任务规模超出配置上限，重试只会再超时一次。
     */
    public void execute(Long taskId) {
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，跳过执行, taskId={}", taskId);
            return;
        }

        // 原子占用：只有把 PENDING 成功改成 RUNNING 的线程才有权执行
        int acquired = taskMapper.markRunning(taskId, "启动");
        if (acquired != 1) {
            log.info("任务已被其他执行线程接管或已进入终态, taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        TaskType type = parseType(task.getTaskType());
        Optional<TaskHandler> handler = handlerRegistry.find(type);
        if (handler.isEmpty()) {
            finish(taskId, TaskStatus.FAILED, task.getProgress(), "NO_HANDLER",
                    "没有可处理该任务类型的处理器：" + task.getTaskType());
            log.error("缺少任务处理器, taskId={}, taskType={}", taskId, task.getTaskType());
            return;
        }

        int maxRetry = task.getMaxRetry() == null ? properties.getDefaultMaxRetry() : task.getMaxRetry();
        int timeoutSeconds = task.getTimeoutSeconds() == null
                ? (int) properties.getDefaultTimeout().toSeconds() : task.getTimeoutSeconds();

        int attempt = 0;
        while (true) {
            TaskExecutionContextImpl context = new TaskExecutionContextImpl(taskMapper, stepMapper, task);
            Outcome outcome = runOnce(task, handler.get(), context, timeoutSeconds);
            if (outcome.status() == TaskStatus.SUCCEEDED || outcome.status() == TaskStatus.CANCELLED) {
                finish(taskId, outcome.status(), 100, null, null);
                return;
            }

            attempt++;
            if (attempt > maxRetry) {
                finish(taskId, TaskStatus.FAILED, currentProgress(taskId),
                        outcome.errorCode(), outcome.errorMessage());
                return;
            }

            log.warn("任务执行失败，准备第 {} 次重试, taskId={}, 原因={}", attempt, taskId, outcome.errorMessage());
            ProcessingTask update = new ProcessingTask();
            update.setId(taskId);
            update.setRetryCount(attempt);
            taskMapper.updateById(update);
        }
    }

    /** 单次执行，负责超时与取消判定。 */
    private Outcome runOnce(ProcessingTask task, TaskHandler handler,
                            TaskExecutionContextImpl context, int timeoutSeconds) {
        Future<?> future = executor.submit(() -> handler.handle(task, context));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        try {
            while (true) {
                try {
                    future.get(CANCEL_POLL_MILLIS, TimeUnit.MILLISECONDS);
                    return Outcome.success();
                } catch (TimeoutException e) {
                    if (context.isCancelRequested()) {
                        future.cancel(true);
                        context.skipCurrentStep("用户取消");
                        log.info("任务已取消, taskId={}", task.getId());
                        return Outcome.cancelled();
                    }
                    if (System.nanoTime() >= deadline) {
                        future.cancel(true);
                        context.failCurrentStep("执行超时");
                        log.warn("任务执行超时, taskId={}, timeout={}s", task.getId(), timeoutSeconds);
                        return Outcome.failure("TIMEOUT", "任务超时（超过 " + timeoutSeconds + " 秒）");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.failCurrentStep("执行线程被中断");
            return Outcome.failure("INTERRUPTED", "执行线程被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TaskCancelledException) {
                context.skipCurrentStep("用户取消");
                return Outcome.cancelled();
            }
            context.failCurrentStep(safeMessage(cause));
            // 只记录异常消息与堆栈到日志，不把堆栈写进数据库回传前端
            log.warn("任务执行异常, taskId={}", task.getId(), cause);
            return Outcome.failure("HANDLER_ERROR", safeMessage(cause));
        }
    }

    /**
     * 推进到终态。
     *
     * <p>使用条件更新：只有当前状态仍允许流转时才写入，
     * 避免「已成功」的任务被后续的失败回调覆盖。
     */
    private void finish(Long taskId, TaskStatus target, int progress,
                        String errorCode, String errorMessage) {
        List<String> from = target == TaskStatus.SUCCEEDED ? RUNNING_ONLY : CANCELLABLE_FROM;
        int updated = taskMapper.finish(taskId, target.name(), progress, errorCode, errorMessage, from);
        if (updated != 1) {
            log.warn("任务状态流转被拒绝（可能已进入终态）, taskId={}, target={}", taskId, target);
        }
    }

    private int currentProgress(Long taskId) {
        ProcessingTask latest = taskMapper.selectById(taskId);
        return latest == null || latest.getProgress() == null ? 0 : latest.getProgress();
    }

    private TaskType parseType(String value) {
        try {
            return TaskType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("未知任务类型: " + value, e);
        }
    }

    private static String safeMessage(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // 限制长度，避免把超长内容整段写进数据库
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** 供诊断使用的线程池快照。 */
    public String executorSnapshot() {
        return "active=" + executor.getActiveCount()
                + ", pool=" + executor.getPoolSize()
                + ", queue=" + executor.getThreadPoolExecutor().getQueue().size();
    }

    /** 单次执行结果。 */
    private record Outcome(TaskStatus status, String errorCode, String errorMessage) {

        static Outcome success() {
            return new Outcome(TaskStatus.SUCCEEDED, null, null);
        }

        static Outcome cancelled() {
            return new Outcome(TaskStatus.CANCELLED, null, null);
        }

        static Outcome failure(String errorCode, String errorMessage) {
            return new Outcome(TaskStatus.FAILED, errorCode, errorMessage);
        }
    }
}
