package com.documenter.service.impl;

import com.documenter.entity.AuditLog;
import com.documenter.entity.User;
import com.documenter.enums.AuditAction;
import com.documenter.mapper.AuditLogMapper;
import com.documenter.service.AuditService;
import com.documenter.service.VerificationContext;
import com.documenter.service.VerificationRequestContextHolder;
import com.documenter.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审计日志实现（TODO 3.2）。
 *
 * <p>细节长度统一截断到数据库列可容纳的范围：审计细节来自业务字符串，
 * 长度不可控，写超长内容会导致插入失败，而插入失败又会被吞掉，
 * 结果就是「这条操作静默地没有审计记录」——比报错更危险。
 */
@Slf4j
@Service
public class AuditServiceImpl implements AuditService {

    /** audit_log.detail 是 TEXT，这里限制在 4000 字符以内，留足余量。 */
    private static final int MAX_DETAIL_LENGTH = 4000;
    private static final int MAX_TARGET_ID_LENGTH = 64;
    private static final int MAX_USERNAME_LENGTH = 50;

    private final AuditLogMapper auditLogMapper;

    public AuditServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(AuditAction action, String targetType, Object targetId, String detail, boolean success) {
        User current = SecurityUtil.getUser();
        recordFor(current == null ? null : current.getId(),
                current == null ? null : current.getUsername(),
                action, targetType, targetId, detail, success);
    }

    @Override
    public void recordFor(Long userId, String username, AuditAction action,
                          String targetType, Object targetId, String detail, boolean success) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUserId(userId);
            entry.setUsername(truncate(username, MAX_USERNAME_LENGTH));
            entry.setAction(action.name());
            entry.setTargetType(targetType);
            entry.setTargetId(targetId == null ? null : truncate(String.valueOf(targetId), MAX_TARGET_ID_LENGTH));
            entry.setDetail(truncate(detail, MAX_DETAIL_LENGTH));
            entry.setResult(success ? "SUCCESS" : "FAILURE");

            VerificationContext context = VerificationRequestContextHolder.get();
            entry.setRequestIp(context.requestIp());
            entry.setRequestId(context.requestId());

            auditLogMapper.insert(entry);
        } catch (RuntimeException e) {
            // 审计失败不能影响业务，但必须留下告警，否则「审计缺失」无人知晓
            log.warn("审计日志写入失败, action={}, targetType={}, targetId={}",
                    action, targetType, targetId, e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
