package com.documenter.enums;

/**
 * 任务状态机（TODO 3.4：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED）。
 *
 * <p>合法流转：
 * <pre>
 *   PENDING ──► RUNNING ──► SUCCEEDED
 *      │           │   └──► FAILED
 *      │           └──────► CANCELLED
 *      └──────────────────► CANCELLED
 * </pre>
 * 终态（SUCCEEDED / FAILED / CANCELLED）不可再流转，否则会出现「已完成的任务又被置为失败」
 * 这类覆盖历史的问题。数据库侧有同名 CHECK 约束。
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 是否为终态：终态任务不再接受任何状态变更。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 校验流转是否合法，用于在服务层提前拦截并给出明确错误。 */
    public boolean canTransitionTo(TaskStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == SUCCEEDED || target == FAILED || target == CANCELLED;
            // 终态不再流转；重复提交相同状态也不允许，避免重复扣减或重复生成版本
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }
}
