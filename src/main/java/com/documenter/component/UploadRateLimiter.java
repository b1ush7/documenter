package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 上传频率限制（TODO 3.3「校验实际文件类型及大小」配套的滥用防护）。
 *
 * <p>与验证码的日限额同类问题：如果把计数放在数据库里，就得为每次上传写一行记录，
 * 表会迅速膨胀。这里用 Redis 原子计数，按用户 + 自然日划分，TTL 25 小时。
 */
@Component
public class UploadRateLimiter {

    private static final String KEY_PREFIX = "upload:quota:";
    private static final long KEY_TTL_SECONDS = 25 * 60 * 60;

    private static final DefaultRedisScript<Long> CHECK_AND_INCR = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[1])
            if current >= limit then
                return 0
            end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final StorageProperties properties;

    public UploadRateLimiter(StringRedisTemplate redisTemplate, StorageProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 占用一次上传额度。
     *
     * <p>注意：本方法在「类型校验通过、真正落盘之前」调用。若后续落盘失败，
     * 这次额度不会退回。这是刻意的取舍——宁可让失败请求也占用额度，
     * 也不要因为退回逻辑本身失败而放过刷量请求。
     */
    public void acquire(Long userId) {
        String key = KEY_PREFIX + userId + ":" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Long allowed = redisTemplate.execute(CHECK_AND_INCR, List.of(key),
                Integer.toString(properties.getUpload().getHourlyLimit()),
                Long.toString(KEY_TTL_SECONDS));
        if (!Long.valueOf(1).equals(allowed)) {
            throw new BusinessException(429, "今日上传次数已达上限，请稍后再试");
        }
    }
}
