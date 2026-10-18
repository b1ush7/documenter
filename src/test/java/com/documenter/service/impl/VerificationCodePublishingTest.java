package com.documenter.service.impl;

import com.documenter.configuration.VerificationCodeProperties;
import com.documenter.entity.User;
import com.documenter.enums.VerificationScene;
import com.documenter.exception.BusinessException;
import com.documenter.service.UserService;
import com.documenter.service.VerificationCodeSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationCodePublishingTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final VerificationCodeSender sender = mock(VerificationCodeSender.class);
    private final VerificationRecordAuditService audit = mock(VerificationRecordAuditService.class);
    private final UserService users = mock(UserService.class);
    private final VerificationCodeProperties properties = new VerificationCodeProperties();
    private final VerificationCodeServiceImpl service = new VerificationCodeServiceImpl(redis, properties, sender, audit, users);

    @BeforeEach
    void setUp() {
        var user = new User();
        user.setStatus("ENABLED");
        when(users.findByPhone("13800000000")).thenReturn(user);
    }

    @Test
    void publishesHashAttemptResetAndTtlInOneRedisOperationAfterDelivery() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        service.sendCode("13800000000", VerificationScene.LOGIN);

        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("13800000000"), eq(VerificationScene.LOGIN), code.capture());
        assertTrue(code.getValue().matches("[0-9]{6}"));
        var order = inOrder(sender, redis, audit);
        order.verify(sender).send(eq("13800000000"), eq(VerificationScene.LOGIN), anyString());
        order.verify(redis).execute(any(RedisScript.class),
                eq(List.of(VerificationCodeServiceImpl.codeKey("13800000000", VerificationScene.LOGIN))),
                eq(VerificationCodeServiceImpl.sha256(code.getValue())), eq("900000"));
        order.verify(audit).recordSend(eq("13800000000"), eq(VerificationScene.LOGIN),
                eq(VerificationCodeServiceImpl.sha256(code.getValue())), any(), eq(5), eq(properties.getTtl()));
        verify(redis, never()).opsForHash();
        verify(redis, never()).expire(anyString(), any(java.time.Duration.class));
    }

    @Test
    void atomicPublishRetainsSubSecondTtlPrecision() {
        properties.setTtl(java.time.Duration.ofMillis(1500));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        service.sendCode("13800000000", VerificationScene.LOGIN);

        verify(redis).execute(any(RedisScript.class),
                eq(List.of(VerificationCodeServiceImpl.codeKey("13800000000", VerificationScene.LOGIN))),
                anyString(), eq("1500"));
    }

    @Test
    void indeterminatePublishDoesNotReportSuccessfulSendOrWriteSuccessAudit() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L, null);
        var error = assertThrows(BusinessException.class,
                () -> service.sendCode("13800000000", VerificationScene.LOGIN));
        assertEquals(503, error.getCode());
        verify(sender).send(anyString(), any(), anyString());
        verifyNoInteractions(audit);
    }

    @Test
    void redisFailureAfterDeliveryReturnsSafe503() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 1L).thenThrow(new IllegalStateException("private connection information"));
        var error = assertThrows(BusinessException.class,
                () -> service.sendCode("13800000000", VerificationScene.LOGIN));
        assertEquals(503, error.getCode());
        assertFalse(error.getMessage().contains("private"));
        verifyNoInteractions(audit);
    }
}
