package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.entity.VerificationRecord;
import com.documenter.enums.VerificationScene;
import com.documenter.mapper.VerificationRecordMapper;
import com.documenter.service.VerificationContext;
import com.documenter.service.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 验证码记录的数据库落库（TODO 3.1「保存摘要、有效期与发送记录」）。
 *
 * <p>Redis 负责高频的原子判断，数据库负责可审计的历史记录与日限额统计。
 * 因此这里所有异常都被吞掉并降级为告警日志：审计记录写入失败不应该让用户收不到验证码，
 * 但也不能静默，必须留下日志以便排查。
 */
@Slf4j
@Service
public class VerificationRecordAuditService {

    private final VerificationRecordMapper mapper;

    public VerificationRecordAuditService(VerificationRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录一次发送。
     *
     * <p>表上 (identifier, scene) 是唯一索引，因此同一对象同一用途只保留最新一条，
     * 用 upsert 语义更新，避免记录无限增长。
     */
    public void recordSend(String identifier, VerificationScene scene, String codeHash,
                           VerificationContext context, int maxAttempts, java.time.Duration ttl) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expireTime = now.plus(ttl);
            VerificationRecord existing = findExisting(identifier, scene);
            if (existing == null) {
                VerificationRecord record = new VerificationRecord();
                record.setIdentifier(identifier);
                record.setScene(scene.name());
                record.setCodeHash(codeHash);
                record.setSendCount(1);
                record.setVerifyAttempts(0);
                record.setMaxAttempts(maxAttempts);
                record.setStatus("ACTIVE");
                record.setRequestIp(context == null ? null : context.requestIp());
                record.setRequestId(context == null ? null : context.requestId());
                record.setSentTime(now);
                record.setExpireTime(expireTime);
                mapper.insert(record);
            } else {
                existing.setCodeHash(codeHash);
                existing.setSendCount(existing.getSendCount() == null ? 1 : existing.getSendCount() + 1);
                // 重新发送意味着旧验证码作废，错误计数归零
                existing.setVerifyAttempts(0);
                existing.setStatus("ACTIVE");
                existing.setRequestIp(context == null ? null : context.requestIp());
                existing.setRequestId(context == null ? null : context.requestId());
                existing.setSentTime(now);
                existing.setExpireTime(expireTime);
                existing.setConsumeTime(null);
                mapper.updateById(existing);
            }
        } catch (RuntimeException e) {
            log.warn("验证码发送记录落库失败, identifier={}, scene={}", mask(identifier), scene, e);
        }
    }

    /** 记录一次校验失败。 */
    public void recordFailure(String identifier, VerificationScene scene) {
        try {
            VerificationRecord existing = findExisting(identifier, scene);
            if (existing == null) {
                return;
            }
            int attempts = existing.getVerifyAttempts() == null ? 0 : existing.getVerifyAttempts();
            existing.setVerifyAttempts(attempts + 1);
            if (existing.getMaxAttempts() != null && attempts + 1 >= existing.getMaxAttempts()) {
                existing.setStatus("LOCKED");
            }
            mapper.updateById(existing);
        } catch (RuntimeException e) {
            log.warn("验证码失败次数落库失败, identifier={}, scene={}", mask(identifier), scene, e);
        }
    }

    /** 记录一次成功消费。 */
    public void recordConsumed(String identifier, VerificationScene scene) {
        try {
            VerificationRecord existing = findExisting(identifier, scene);
            if (existing == null) {
                return;
            }
            existing.setStatus("CONSUMED");
            existing.setConsumeTime(LocalDateTime.now());
            // 已消费的验证码不应再参与错误计数
            existing.setVerifyAttempts(0);
            mapper.updateById(existing);
        } catch (RuntimeException e) {
            log.warn("验证码消费记录落库失败, identifier={}, scene={}", mask(identifier), scene, e);
        }
    }

    private VerificationRecord findExisting(String identifier, VerificationScene scene) {
        return mapper.selectOne(Wrappers.<VerificationRecord>lambdaQuery()
                .eq(VerificationRecord::getIdentifier, identifier)
                .eq(VerificationRecord::getScene, scene.name()));
    }

    private static String mask(String identifier) {
        if (identifier == null || identifier.length() < 4) {
            return "****";
        }
        return "****" + identifier.substring(identifier.length() - 4);
    }
}
