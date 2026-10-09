package com.documenter.service;

import com.documenter.enums.AuditAction;

/**
 * 审计日志写入（TODO 3.2「记录管理操作」）。
 *
 * <p>设计原则：<b>审计失败不能影响业务</b>。用户登录成功却因为写审计日志出错而报错，
 * 是本末倒置。因此所有写入异常都会被吞掉并降级为告警日志——
 * 但绝不能静默，否则「审计缺失」这件事本身就无人知晓。
 */
public interface AuditService {

    /**
     * 记录一次操作。
     *
     * @param action     动作
     * @param targetType 目标类型，可为 null
     * @param targetId   目标标识，可为 null
     * @param detail     脱敏后的细节，<b>禁止</b>写入密码、密钥或文件正文
     * @param success    是否成功
     */
    void record(AuditAction action, String targetType, Object targetId, String detail, boolean success);

    /** 记录成功操作。 */
    default void success(AuditAction action, String targetType, Object targetId, String detail) {
        record(action, targetType, targetId, detail, true);
    }

    /** 只记录动作、不带目标与细节。 */
    default void success(AuditAction action) {
        record(action, null, null, null, true);
    }

    /**
     * 以指定用户为主体记录。
     *
     * <p>用于「操作者不是当前登录用户或还没有登录」的场景，
     * 例如登录失败时只有账号名、没有会话。
     */
    void recordFor(Long userId, String username, AuditAction action,
                   String targetType, Object targetId, String detail, boolean success);

    /** 记录登录失败：此时用户可能不存在，因此直接传账号名。 */
    default void loginFailed(String account) {
        recordFor(null, account, AuditAction.USER_LOGIN_FAILED, "USER", account,
                "账号或密码错误", false);
    }
}
