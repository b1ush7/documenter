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
 *
 * <h3>产出新版本时必须传任务幂等标识（TODO 3.4）</h3>
 *
 * <p>任务失败后会重试，而重试会<b>重新调用本处理器</b>。如果处理器直接调用
 * {@code versionWriter.appendVersion(...)} 的 7 参数重载，每次重试都会分配一个新的
 * 版本号，于是一次「已经产出版本、但在后续步骤失败」的执行会产出内容相同的重复版本。
 *
 * <p>正确写法是使用带任务与尝试序号的重载：
 *
 * <pre>{@code
 * DocumentVersion created = versionWriter.appendVersion(
 *         fileId, userId, content, ChangeType.AI_EDIT, instruction, summary,
 *         expectVersion,
 *         context.taskId(),       // 任务 ID
 *         context.attemptNo());   // 本次尝试序号，从 1 开始
 * }</pre>
 *
 * <p>这样同一任务的同一次尝试最多产出一个版本：已存在时 {@code appendVersion}
 * 直接返回已有记录，不分配新版本号、不重复落盘。用户显式重试时引擎会把
 * {@code attemptNo} 递增，因此新的执行意图仍能正常产出新版本。
 *
 * <h3>涉及额度扣减时先检查是否已产出</h3>
 *
 * <p>若处理器需要在成功后扣减额度或记账，必须先判断本次是否真的产出了新版本，
 * 否则重试会导致重复扣减：
 *
 * <pre>{@code
 * // 该任务此前是否已产出过版本（任意尝试）
 * if (!versionWriter.hasProducedVersion(context.taskId())) {
 *     // 仅在尚无产出时扣减
 *     quotaService.consumeOnce(context.taskId(), cost);
 * }
 * }</pre>
 *
 * <p>是否接入余额计费尚未确定（见 TODO 第 9 节），因此框架只提供判断依据，
 * 不替调用方做计费决策。
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
