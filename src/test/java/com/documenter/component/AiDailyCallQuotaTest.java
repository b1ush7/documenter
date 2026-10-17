package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiDailyCallQuotaTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AiModelProperties properties = new AiModelProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T15:59:59Z"), ZoneOffset.UTC);

    @Test
    void unlimitedModeDoesNotRequireRedis() {
        new AiDailyCallQuota(redis, properties, clock).acquire(7L);
        verifyNoInteractions(redis);
    }

    @Test
    void configuredLimitUsesOneAtomicScriptAndUserDayKey() {
        properties.setDailyCallLimitPerUser(5);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        new AiDailyCallQuota(redis, properties, clock).acquire(7L);

        verify(redis).execute(any(RedisScript.class), eq(List.of("ai:daily-call:7:20261017")), eq("5"), eq("172800"));
        verifyNoMoreInteractions(redis);
    }

    @Test
    void exhaustedLimitReturns429WithoutRetryingRedis() {
        properties.setDailyCallLimitPerUser(5);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        assertCode(429, () -> new AiDailyCallQuota(redis, properties, clock).acquire(7L));

        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void unavailableOrIndeterminateCounterBlocksCallsWith503() {
        properties.setDailyCallLimitPerUser(5);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("private connection information"))
                .thenReturn(null).thenReturn(-1L);
        var quota = new AiDailyCallQuota(redis, properties, clock);
        for (int i = 0; i < 3; i++) {
            BusinessException failure = assertThrows(BusinessException.class, () -> quota.acquire(7L));
            assertEquals(503, failure.getCode());
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    @Test
    void naturalDayRolloverAndDifferentUsersUseDifferentKeys() {
        properties.setDailyCallLimitPerUser(5);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        new AiDailyCallQuota(redis, properties, clock).acquire(7L);
        new AiDailyCallQuota(redis, properties, clock).acquire(8L);
        var afterMidnight = Clock.fixed(clock.instant().plusSeconds(2), ZoneOffset.UTC);
        new AiDailyCallQuota(redis, properties, afterMidnight).acquire(7L);

        verify(redis).execute(any(RedisScript.class), eq(List.of("ai:daily-call:7:20261017")), any(Object[].class));
        verify(redis).execute(any(RedisScript.class), eq(List.of("ai:daily-call:8:20261017")), any(Object[].class));
        verify(redis).execute(any(RedisScript.class), eq(List.of("ai:daily-call:7:20261018")), any(Object[].class));
    }

    @Test
    void configuredTimezoneDefinesDayInsteadOfHostTimezone() {
        properties.setDailyCallLimitPerUser(5);
        properties.setQuotaZone(ZoneId.of("UTC"));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        new AiDailyCallQuota(redis, properties,
                Clock.fixed(Instant.parse("2026-10-17T16:00:01Z"), ZoneOffset.UTC)).acquire(7L);
        verify(redis).execute(any(RedisScript.class), eq(List.of("ai:daily-call:7:20261017")), any(Object[].class));
    }

    @Test
    void invalidConfigurationCannotSilentlyDisableQuota() {
        properties.setDailyCallLimitPerUser(-1);
        assertCode(503, () -> new AiDailyCallQuota(redis, properties, clock).acquire(7L));
        properties.setDailyCallLimitPerUser(5);
        assertCode(503, () -> new AiDailyCallQuota(redis, properties, clock).acquire(null));
        verifyNoInteractions(redis);
    }

    @Test
    void springCanConstructQuotaWithoutATestClockBean() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiModelProperties.class, () -> properties);
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.register(AiDailyCallQuota.class);
            context.refresh();
            context.getBean(AiDailyCallQuota.class).acquire(7L);
        }
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private static void assertCode(int code, Runnable operation) {
        assertEquals(code, assertThrows(BusinessException.class, operation::run).getCode());
    }
}
