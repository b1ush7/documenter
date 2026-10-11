package com.documenter.enums;

/**
 * 任务步骤状态（TODO 3.4「包含步骤、进度」）。
 *
 * <p>与 task_step.status 的 CHECK 约束一一对应。
 */
public enum TaskStepStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,

    /** 因上游步骤失败或用户取消而跳过。 */
    SKIPPED
}
