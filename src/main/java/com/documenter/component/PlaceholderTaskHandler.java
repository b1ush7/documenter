package com.documenter.component;

import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskStepStatus;
import com.documenter.enums.TaskType;
import com.documenter.service.TaskExecutionContext;
import com.documenter.service.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 占位任务处理器（P0 阶段）。
 *
 * <p>存在的意义是把「提交 → 排队 → 执行 → 状态流转 → 超时/取消/重试 → 重启恢复」
 * 这条管路先打通并可以端到端验证；真实的文档处理逻辑属于 P1/P2
 * （DOCX 编辑、PDF 转换、OCR、图片生成）。
 *
 * <p>它<b>不会</b>假装自己完成了真实处理：只登记步骤、上报进度，
 * 步骤说明里明确写出「未产生真实产物」，避免前端把占位结果当成真实交付。
 *
 * <p>接入真实处理器时，用 {@code res.task.placeholder-enabled=false} 关闭本类，
 * 否则会与真实处理器争抢任务类型导致启动失败（注册表会检测冲突）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "res.task.placeholder-enabled", havingValue = "true", matchIfMissing = true)
public class PlaceholderTaskHandler implements TaskHandler {

    @Override
    public void handle(ProcessingTask task, TaskExecutionContext context) {
        context.startStep("解析请求", "任务类型=" + task.getTaskType());
        context.assertNotCancelled();
        context.reportProgress(10);
        context.finishStep(TaskStepStatus.SUCCEEDED, "参数校验通过", null);

        context.startStep("执行处理", "P0 阶段未接入真实处理器");
        // 分段推进进度，同时留出检查取消的时机
        for (int progress = 30; progress <= 90; progress += 20) {
            context.assertNotCancelled();
            context.reportProgress(progress);
            sleepBriefly();
        }
        // 示范任务级幂等（TODO 3.4）：真实处理器产出新版本时应把任务与尝试序号传下去，
        // 使同一次尝试内的失败重入复用已产出的版本，而不是再分配一个版本号。
        // 占位处理器不写版本，因此这里只把两个标识写进步骤说明，便于联调时确认。
        context.finishStep(TaskStepStatus.SUCCEEDED,
                "占位处理完成，未产生真实产物（实际处理器应传 taskId=" + context.taskId()
                        + ", attemptNo=" + context.attemptNo() + " 以保证版本幂等）", null);

        context.reportProgress(100);
        log.info("占位处理器完成任务, taskId={}, attemptNo={}, taskType={}",
                task.getId(), context.attemptNo(), task.getTaskType());
    }

    @Override
    public String name() {
        return "PlaceholderTaskHandler";
    }

    @Override
    public boolean supports(TaskType type) {
        // P0 阶段对所有类型都提供占位实现，便于验证完整管路
        return true;
    }

    /** 短暂停顿，模拟真实处理耗时；被中断时立即返回交由上层处理。 */
    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务线程被中断", e);
        }
    }
}
