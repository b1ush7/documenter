package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 在每次 ChatModel 调用前原子占用一个用户日额度；失败的调用不退还额度。 */
@Slf4j
@Component
public class AiDailyCallQuota {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            if used >= tonumber(ARGV[1]) then return 0 end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AiModelProperties properties;
    private final Clock clock;

    @Autowired
    public AiDailyCallQuota(StringRedisTemplate redis, AiModelProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    AiDailyCallQuota(StringRedisTemplate redis, AiModelProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void acquire(Long userId) {
        int limit = properties.getDailyCallLimitPerUser();
        if (limit == 0) return;
        if (limit < 0 || userId == null || userId <= 0 || properties.getQuotaZone() == null) {
            throw new BusinessException(503, "每日 AI 额度配置或用户上下文不合法");
        }
        String date = LocalDate.now(clock.withZone(properties.getQuotaZone())).format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "ai:daily-call:" + userId + ":" + date;
        Long acquired;
        try {
            // 按日期换键，48 小时 TTL 仅用于回收历史计数，不延长当天额度窗口。
            acquired = redis.execute(ACQUIRE, List.of(key), Integer.toString(limit), "172800");
        } catch (RuntimeException failure) {
            log.warn("无法检查 AI 日额度, userId={}", userId, failure);
            throw new BusinessException(503, "暂时无法检查每日 AI 额度，请稍后重试");
        }
        if (Long.valueOf(0).equals(acquired)) {
            throw new BusinessException(429, "今日 AI 调用次数已达上限，请于配置时区的下一自然日重试");
        }
        if (!Long.valueOf(1).equals(acquired)) {
            throw new BusinessException(503, "暂时无法检查每日 AI 额度，请稍后重试");
        }
    }
}
