package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 任务重启恢复（TODO 3.4「处理外部模型不可用、存储失败及队列堆积」「重启恢复」）。
 *
 * <p>进程被强杀时，停留在 RUNNING 的任务不会有任何线程再推进它，
 * 前端会永远看到「运行中」。启动时必须把它们标记为失败，
 * 否则用户无法判断该等还是该重试。
 *
 * <p>另外两类遗留状态：
 * <ul>
 *   <li>PENDING：重启前排队但没开始的任务。部署配置默认自动重新入队，
 *       可通过 {@code res.task.requeue-pending-on-startup=false} 关闭。
 *       即使开启，也依赖幂等键与版本号检查避免重复产出。</li>
 *   <li>CANCELLED / SUCCEEDED / FAILED：终态，无需处理。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TaskRecoveryRunner implements ApplicationRunner {
    private ExecutionLeaseService leases;

    @org.springframework.beans.factory.annotation.Autowired
    public void setExecutionLeases(ExecutionLeaseService leases) { this.leases = leases; }

    private final ProcessingTaskMapper taskMapper;
    private final TaskExecutorProperties properties;
    private final TaskExecutionEngine executionEngine;
    private final TaskStepMapper stepMapper;
    private final TransactionTemplate transactions;

    public TaskRecoveryRunner(ProcessingTaskMapper taskMapper,
                              TaskExecutorProperties properties,
                              TaskExecutionEngine executionEngine,
                              TaskStepMapper stepMapper,
                              PlatformTransactionManager transactionManager) {
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.executionEngine = executionEngine;
        this.stepMapper = stepMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (leases != null && leases.enabled()) return;
        if (!properties.isRecoverOnStartup()) {
            log.info("已关闭任务重启恢复（res.task.recover-on-startup=false）");
            return;
        }

        int failed = transactions.execute(status -> {
            stepMapper.failInterruptedSteps();
            return taskMapper.failInterruptedTasks();
        });
        if (failed > 0) {
            log.warn("发现 {} 个因服务重启而中断的任务，已标记为失败，用户可重新提交", failed);
        } else {
            log.info("没有需要恢复的中断任务");
        }

        if (!properties.isRequeuePendingOnStartup()) {
            long pending = countPending();
            if (pending > 0) {
                log.warn("存在 {} 个排队中的任务未被自动重新入队（res.task.requeue-pending-on-startup=false）。"
                        + "需要自动恢复队列时可开启该开关。", pending);
            }
            return;
        }

        List<ProcessingTask> pendingTasks = taskMapper.selectPendingTasks();
        if (pendingTasks.isEmpty()) {
            return;
        }
        log.info("正在重新入队 {} 个排队中的任务", pendingTasks.size());
        for (ProcessingTask task : pendingTasks) {
            // 重新入队前不再改状态：引擎会用「PENDING → RUNNING」的条件更新自行抢占，
            // 因此重复入队是安全的
            executionEngine.submit(task.getId());
        }
    }

    private long countPending() {
        Long count = taskMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ProcessingTask>()
                .eq("status", "PENDING"));
        return count == null ? 0L : count;
    }
}
