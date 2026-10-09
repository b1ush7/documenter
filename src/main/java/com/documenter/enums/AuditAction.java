package com.documenter.enums;

/**
 * 审计动作（TODO 3.2、3.4）。
 *
 * <p>用枚举而不是裸字符串，避免同一个动作在不同地方写成不同名字，
 * 导致事后按 action 检索时漏掉记录。
 */
public enum AuditAction {

    // ---- 账号 ----
    USER_REGISTER,
    USER_LOGIN,
    USER_LOGIN_FAILED,
    USER_LOGOUT_ALL,
    USER_PASSWORD_CHANGED,

    // ---- 文件与版本 ----
    FILE_UPLOAD,
    FILE_RENAME,
    FILE_DELETE,
    FILE_DOWNLOAD,
    VERSION_RESTORE,

    // ---- 任务 ----
    TASK_SUBMIT,
    TASK_CANCEL,
    TASK_RETRY,

    // ---- 管理操作（属于敏感操作，必须留痕）----
    ADMIN_USER_DISABLE,
    ADMIN_USER_ENABLE,
    ADMIN_ROLE_CHANGE,
    ADMIN_MODEL_CONFIG_CREATE,
    ADMIN_MODEL_CONFIG_UPDATE,
    ADMIN_MODEL_CONFIG_DELETE,
    ADMIN_MODEL_CONFIG_ENABLE
}
