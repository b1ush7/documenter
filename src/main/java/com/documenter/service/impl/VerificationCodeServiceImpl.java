package com.documenter.service.impl;

import com.documenter.configuration.VerificationCodeProperties;
import com.documenter.entity.User;
import com.documenter.enums.VerificationScene;
import com.documenter.exception.BusinessException;
import com.documenter.service.UserService;
import com.documenter.service.VerificationCodeSender;
import com.documenter.service.VerificationCodeService;
import com.documenter.service.VerificationContext;
import com.documenter.service.VerificationRequestContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * 验证码业务实现（TODO 3.1）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>Redis 存验证码、数据库存记录</b>：校验与频率限制要求原子性，放在 Redis；
 *       数据库保存可审计的发送/消费记录并统计日发送量。</li>
 *   <li><b>不存明文</b>：Redis 与数据库都只存 SHA-256 摘要，泄露存储不直接等于泄露验证码。</li>
 *   <li><b>消费即失效</b>：校验成功立刻删除，保证一次性使用。</li>
 *   <li><b>消费后错误计数归零</b>：验证码被替换或消费后，旧的失败计数不应该继续累计，
 *       否则用户在一天内多次正常操作后会被误锁。这一归零在 Redis 删除中天然成立，
 *       数据库侧在审计服务里显式处理。</li>
 * </ul>
 */
@Slf4j
@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final String CODE_KEY_PREFIX = "verify:code:";
    private static final String LIMIT_KEY_PREFIX = "verify:limit:";
    private static final String DAILY_LIMIT_KEY_PREFIX = "verify:daily:";
    /** 计数键 TTL 取 25 小时，跨日边界也能自然过期，不会无限堆积。 */
    private static final Duration DAILY_LIMIT_TTL = Duration.ofHours(25);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 发送限流：INI 保存上次发送时间，EXPIRE 由参数控制，同时充当「最短重发间隔」锁。 */
    private static final DefaultRedisScript<Long> ACQUIRE_SEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            return 1
            """, Long.class);

    /**
     * 日发送量限制：原子「检查并自增」当天计数器。
     *
     * <p>为什么必须用 Redis 而不是查数据库：verification_record 表上 (identifier, scene)
     * 是唯一索引，重复发送只会更新同一条记录，用 SQL count 永远只能数出 1，
     * 日限额会形同虚设。这里用 INCR 原子计数，超出即回退并拒绝。
     *
     * <p>计数键按自然日划分（由 Java 传入 yyyyMMdd），TTL 25 小时覆盖跨日边界。
     */
    private static final DefaultRedisScript<Long> DAILY_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[1])
            if current >= limit then
                return 0
            end
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /**
     * 消费验证码：
     *   1 = 校验通过并已删除
     *   0 = 验证码不存在或已过期
     *  -1 = 错误次数已达上限
     *  -2 = 验证码不匹配（已累加错误次数）
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            local maxAttempts = tonumber(ARGV[2])
            if attempts >= maxAttempts then
                return -1
            end
            if redis.call('HGET', KEYS[1], 'hash') == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            return -2
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeProperties properties;
    private final VerificationCodeSender sender;
    private final VerificationRecordAuditService auditService;
    private final UserService userService;

    public VerificationCodeServiceImpl(StringRedisTemplate redisTemplate,
                                       VerificationCodeProperties properties,
                                       VerificationCodeSender sender,
                                       VerificationRecordAuditService auditService,
                                       UserService userService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.sender = sender;
        this.auditService = auditService;
        this.userService = userService;
    }

    @Override
    public String sendCode(String identifier, VerificationScene scene) {
        String phone = normalize(identifier);
        verifyScenePrecondition(phone, scene);

        // 日发送量限制：Redis 原子「检查并自增」当天计数，超限直接拒绝
        Long withinLimit = redisTemplate.execute(DAILY_LIMIT_SCRIPT,
                List.of(dailyLimitKey(phone, scene)),
                Integer.toString(properties.getDailyLimit()),
                Long.toString(DAILY_LIMIT_TTL.toSeconds()));
        if (!Long.valueOf(1).equals(withinLimit)) {
            throw new BusinessException(429, "今日验证码发送次数已达上限，请明天再试");
        }

        // 最短重发间隔：用 Redis 的 SET NX EX 原子占位，避免并发重复发送
        Long acquired = redisTemplate.execute(ACQUIRE_SEND_SCRIPT,
                List.of(limitKey(phone, scene)),
                Long.toString(properties.getResendInterval().toSeconds()));
        if (!Long.valueOf(1).equals(acquired)) {
            throw new BusinessException(429, "验证码发送过于频繁，请" + properties.getResendInterval().toSeconds() + "秒后再试");
        }

        String code = generateCode(properties.getCodeLength());
        String codeHash = sha256(code);

        try {
            sender.send(phone, scene, code);
        } catch (RuntimeException e) {
            // 投递失败必须释放限流占位，否则用户要白等一个重发间隔
            redisTemplate.delete(limitKey(phone, scene));
            log.warn("验证码投递失败, scene={}", scene, e);
            throw new BusinessException(502, "验证码发送失败，请稍后重试");
        }

        // 验证码本身存 Redis 并设置 TTL；重复发送时覆盖旧值，等价于旧验证码作废
        String codeKey = codeKey(phone, scene);
        redisTemplate.opsForHash().put(codeKey, "hash", codeHash);
        redisTemplate.opsForHash().put(codeKey, "attempts", "0");
        redisTemplate.expire(codeKey, properties.getTtl());

        auditService.recordSend(phone, scene, codeHash, VerificationRequestContextHolder.get(),
                properties.getMaxAttempts(), properties.getTtl());

        log.info("验证码已发送, scene={}, 有效期={}秒", scene, properties.getTtl().toSeconds());
        return maskPhone(phone);
    }

    @Override
    public void consumeCode(String identifier, VerificationScene scene, String code) {
        String phone = normalize(identifier);
        String normalizedCode = code == null ? "" : code.trim();

        Long result = redisTemplate.execute(CONSUME_SCRIPT,
                List.of(codeKey(phone, scene)),
                sha256(normalizedCode),
                Integer.toString(properties.getMaxAttempts()));

        long value = result == null ? 0L : result;
        if (value == 1L) {
            auditService.recordConsumed(phone, scene);
            return;
        }
        auditService.recordFailure(phone, scene);
        if (value == -1L) {
            throw new BusinessException(429, "验证码错误次数过多，已作废，请重新获取");
        }
        if (value == 0L) {
            throw new BusinessException(400, "验证码不存在或已过期，请重新获取");
        }
        throw new BusinessException(400, "验证码不正确");
    }

    /**
     * 发送前的业务前置校验（TODO 3.1）。
     *
     * <p>注册要求手机号未注册；登录与找回密码要求手机号已注册且账号未被禁用。
     * 这里刻意给出明确的「已注册 / 未注册」提示：验证码渠道尚未接入真实短信，
     * 若不提示，用户无法判断是发不出去还是账号不存在。
     * 接入真实短信后应改为统一话术，避免手机号被批量探测，届时需要同步修改本方法。
     */
    private void verifyScenePrecondition(String phone, VerificationScene scene) {
        User user = userService.findByPhone(phone);
        if (scene == VerificationScene.REGISTER) {
            if (user != null) {
                throw new BusinessException(400, "该手机号已注册，请直接登录");
            }
            return;
        }
        if (user == null) {
            throw new BusinessException(400, "该手机号尚未注册");
        }
        if (!user.isEnabled()) {
            throw new BusinessException(403, "账号已被禁用，请联系管理员");
        }
    }

    private String generateCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }

    private static String normalize(String identifier) {
        if (identifier == null) {
            throw new BusinessException(400, "手机号不能为空");
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "手机号不能为空");
        }
        return trimmed;
    }

    static String codeKey(String phone, VerificationScene scene) {
        // 用摘要做 key，避免 Redis 里直接堆积明文手机号
        return CODE_KEY_PREFIX + sha256(phone) + ":" + scene.name();
    }

    static String limitKey(String phone, VerificationScene scene) {
        return LIMIT_KEY_PREFIX + sha256(phone) + ":" + scene.name();
    }

    /** 按自然日划分的计数键；TTL 取 25 小时以覆盖跨日边界。 */
    static String dailyLimitKey(String phone, VerificationScene scene) {
        return DAILY_LIMIT_KEY_PREFIX + sha256(phone) + ":" + scene.name() + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算摘要", e);
        }
    }

    private static String maskPhone(String phone) {
        if (phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}
