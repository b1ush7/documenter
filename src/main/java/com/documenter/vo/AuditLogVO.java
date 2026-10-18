package com.documenter.vo;

import com.documenter.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * 审计日志条目（TODO 4.3「管理员基础页面」）。
 *
 * <p>刻意不含 {@code detail} 之外的任何敏感字段 —— {@code detail} 本身在写入时
 * 已要求脱敏（禁止写入密码、密钥、模型 Key 或文件正文），
 * 这里原样透出以便管理员看清操作内容。
 *
 * @param id          记录 ID
 * @param userId      操作者用户 ID；系统操作为 null
 * @param username    操作者用户名<b>快照</b>：用户改名或删除后仍可追溯
 * @param action      动作标识
 * @param targetType  目标类型
 * @param targetId    目标标识
 * @param detail      操作细节（写入时已脱敏）
 * @param result      SUCCESS / FAILURE
 * @param requestIp   请求来源 IP
 * @param requestId   请求链路 ID，可与服务端日志对应
 * @param createTime  发生时间
 */
public record AuditLogVO(
        Long id,
        Long userId,
        String username,
        String action,
        String targetType,
        String targetId,
        String detail,
        String result,
        String requestIp,
        String requestId,
        LocalDateTime createTime) {

    public static AuditLogVO from(AuditLog log) {
        return new AuditLogVO(log.getId(), log.getUserId(), log.getUsername(), log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetail(), log.getResult(),
                log.getRequestIp(), log.getRequestId(), log.getCreateTime());
    }
}
