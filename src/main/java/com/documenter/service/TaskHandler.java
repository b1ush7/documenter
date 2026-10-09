package com.documenter.service;

import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskType;

/**
 * 任务处理器（TODO 3.4）。
 *
 * <p>P0 阶段只提供占位实现，把「提交 → 排队 → 执行 → 状态流转 → 超时/取消/重试」
 * 这套管路先跑通；OCR、转换、AI 编辑等真实处理逻辑属于 P1/P2，
 * 后续只需新增实现类并声明支持的 {@link #supports()}，业务代码无需改动。
 *
 * <p>实现约束：
 * <ul>
 *   <li>执行过程中要定期检查 {@link TaskExecutionContext#isCancelRequested()}，
 *       在步骤边界主动退出，而不是等线程被强杀；</li>
 *   <li>抛异常即视为失败，引擎会记录错误码并转为 FAILED；</li>
 *   <li>不要自己改任务状态，状态由引擎统一维护，避免出现两个地方写状态。</li>
 * </ul>
 */
public interface TaskHandler {

    /** 处理任务。 */
    void handle(ProcessingTask task, TaskExecutionContext context);

    /** 展示名，用于日志与步骤命名。 */
    String name();

    /** 声明本处理器支持的并行度上限（例如调用外部模型时限制并发）。 */
    default int maxConcurrency() {
        return 1;
    }

    /** 声明是否支持某种任务类型。 */
    boolean supports(TaskType type);
}
