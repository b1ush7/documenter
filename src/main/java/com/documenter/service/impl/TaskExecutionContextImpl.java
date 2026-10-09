package com.documenter.service.impl;

import com.documenter.entity.ProcessingTask;
import com.documenter.entity.TaskStep;
import com.documenter.enums.TaskStepStatus;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.TaskExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 任务执行上下文实现（TODO 3.4）。
 *
 * <p>步骤记录采用「先插入 RUNNING，结束时更新」的方式，这样即使进程被杀，
 * 数据库里也能看到任务卡在哪个步骤，而不是一片空白。
 *
 * <p>取消检查直接查库：取消请求可能来自另一个实例或另一个请求线程，
 * 只靠内存标记无法跨线程/跨实例生效。查询频率受步骤与进度上报次数限制，不会造成压力。
 */
@Slf4j
public class TaskExecutionContextImpl implements TaskExecutionContext {

    private final ProcessingTaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final Long taskId;

    private int stepCounter = 0;
    private Long currentStepId;
    private String currentStepName;
    private LocalDateTime currentStepStart;
    private int lastReportedProgress = -1;

    public TaskExecutionContextImpl(ProcessingTaskMapper taskMapper, TaskStepMapper stepMapper,
                                   ProcessingTask task) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.taskId = task.getId();
    }

    @Override
    public Long taskId() {
        return taskId;
    }

    @Override
    public int nextStepNo() {
        return stepCounter + 1;
    }

    @Override
    public void startStep(String stepName, String detail) {
        // 上一步若未显式结束，先按成功收尾，避免步骤记录一直停在 RUNNING
        if (currentStepId != null) {
            finishStep(TaskStepStatus.SUCCEEDED, null, null);
        }
        stepCounter++;
        currentStepName = stepName;
        currentStepStart = LocalDateTime.now();

        TaskStep step = new TaskStep();
        step.setTaskId(taskId);
        step.setStepNo(stepCounter);
        step.setStepName(stepName);
        step.setStatus(TaskStepStatus.RUNNING.name());
        step.setDetail(detail);
        step.setStartTime(currentStepStart);
        stepMapper.insert(step);
        currentStepId = step.getId();

        // 同时更新任务的当前步骤，供前端轮询展示
        ProcessingTask update = new ProcessingTask();
        update.setId(taskId);
        update.setCurrentStep(stepName);
        taskMapper.updateById(update);
    }

    @Override
    public void finishStep(TaskStepStatus status, String detail, String errorMessage) {
        if (currentStepId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        TaskStep update = new TaskStep();
        update.setId(currentStepId);
        update.setStatus(status.name());
        update.setDetail(detail);
        update.setErrorMessage(errorMessage);
        update.setFinishTime(now);
        update.setDurationMs(currentStepStart == null ? null
                : Duration.between(currentStepStart, now).toMillis());
        stepMapper.updateById(update);

        currentStepId = null;
        currentStepStart = null;
    }

    @Override
    public void reportProgress(int progress) {
        int bounded = Math.max(0, Math.min(100, progress));
        // 同一进度不重复写库：进度上报可能很频繁，去重能显著减少写压力
        if (bounded == lastReportedProgress) {
            return;
        }
        lastReportedProgress = bounded;
        ProcessingTask update = new ProcessingTask();
        update.setId(taskId);
        update.setProgress(bounded);
        taskMapper.updateById(update);
    }

    @Override
    public boolean isCancelRequested() {
        Boolean flag = taskMapper.selectCancelRequested(taskId);
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void assertNotCancelled() {
        if (isCancelRequested()) {
            throw new TaskCancelledException(taskId);
        }
    }

    /** 当前处理中的步骤名，便于日志定位。 */
    public String currentStepName() {
        return currentStepName;
    }

    /** 供引擎在任务失败时把当前步骤标记为失败，而不是留在 RUNNING。 */
    public void failCurrentStep(String errorMessage) {
        finishStep(TaskStepStatus.FAILED, null, errorMessage);
    }

    /** 供引擎在任务被取消时把当前步骤标记为跳过。 */
    public void skipCurrentStep(String reason) {
        finishStep(TaskStepStatus.SKIPPED, reason, null);
    }

    /**
     * 用户取消任务（TODO 3.4）。
     *
     * <p>用异常打断深层调用栈，避免每个方法都要返回状态码。
     * 引擎会捕获它并把任务置为 CANCELLED——注意它<b>不算失败</b>。
     */
    public static class TaskCancelledException extends RuntimeException {
        private final Long taskId;

        public TaskCancelledException(Long taskId) {
            super("任务已取消");
            this.taskId = taskId;
        }

        public Long getTaskId() {
            return taskId;
        }
    }

    /** 任务状态不符合预期，用于引擎内部快速失败并给出明确原因。 */
    public static class TaskStateException extends BusinessException {
        public TaskStateException(String message) {
            super(409, message);
        }
    }
}
