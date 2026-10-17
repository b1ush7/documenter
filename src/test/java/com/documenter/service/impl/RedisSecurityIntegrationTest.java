package com.documenter.service.impl;

import com.auth0.jwt.JWT;
import com.documenter.component.AiDailyCallQuota;
import com.documenter.configuration.AiModelProperties;
import com.documenter.configuration.JwtProperties;
import com.documenter.configuration.VerificationCodeProperties;
import com.documenter.entity.User;
import com.documenter.enums.VerificationScene;
import com.documenter.exception.BusinessException;
import com.documenter.service.UserService;
import com.documenter.service.VerificationCodeSender;
import com.documenter.util.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只连接显式提供端口的本机测试 Redis，不访问应用配置，不执行 FLUSHDB/FLUSHALL。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisSecurityIntegrationTest {
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final String phone = "redis-test-" + UUID.randomUUID();
    private final Set<String> cleanup = new HashSet<>();
    private final AtomicReference<String> code = new AtomicReference<>();
    private VerificationCodeServiceImpl verification;
    private Long quotaUser;

    @BeforeAll
    static void connectToDedicatedRedis() {
        int port = Integer.parseInt(System.getenv("DOCUMENTER_TEST_REDIS_PORT"));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ZERO).build();
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port), client);
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        try (var connection = connections.getConnection()) {
            assertEquals("PONG", connection.ping(), "显式指定的测试 Redis 必须可用");
        }
    }

    @AfterAll
    static void closeConnections() {
        if (connections != null) connections.destroy();
    }

    @BeforeEach
    void setUp() {
        var properties = new VerificationCodeProperties();
        properties.setMaxAttempts(3);
        var sender = mock(VerificationCodeSender.class);
        doAnswer(call -> { code.set(call.getArgument(2)); return null; })
                .when(sender).send(anyString(), any(), anyString());
        var users = mock(UserService.class);
        var user = new User();
        user.setStatus("ENABLED");
        when(users.findByPhone(phone)).thenReturn(user);
        verification = new VerificationCodeServiceImpl(redis, properties, sender,
                mock(VerificationRecordAuditService.class), users);
        for (var scene : VerificationScene.values()) {
            cleanup.add(VerificationCodeServiceImpl.codeKey(phone, scene));
            cleanup.add(VerificationCodeServiceImpl.limitKey(phone, scene));
            cleanup.add(VerificationCodeServiceImpl.dailyLimitKey(phone, scene));
        }
    }

    @AfterEach
    void removeOnlyKeysCreatedByThisTest() {
        for (var scene : VerificationScene.values()) {
            cleanup.add(VerificationCodeServiceImpl.dailyLimitKey(phone, scene));
        }
        if (quotaUser != null) {
            // 随机用户 ID 只属于本例；涵盖测试执行时跨日的键，不扫描其他用户。
            Set<String> keys = redis.keys("ai:daily-call:" + quotaUser + ":*");
            if (keys != null) cleanup.addAll(keys);
        }
        if (!cleanup.isEmpty()) redis.delete(cleanup);
    }

    @Test
    void publishesDigestAttemptResetAndExpiryAndConsumesExactlyOnce() {
        verification.sendCode(phone, VerificationScene.LOGIN);
        String key = VerificationCodeServiceImpl.codeKey(phone, VerificationScene.LOGIN);
        assertEquals(VerificationCodeServiceImpl.sha256(code.get()), redis.opsForHash().get(key, "hash"));
        assertEquals("0", redis.opsForHash().get(key, "attempts"));
        assertTrue(redis.getExpire(key) > 0);
        verification.consumeCode(phone, VerificationScene.LOGIN, code.get());
        assertFalse(redis.hasKey(key));
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, code.get()));
    }

    @Test
    void finalAllowedFailureDeletesCodeAndCorrectCodeCanNoLongerPass() {
        verification.sendCode(phone, VerificationScene.LOGIN);
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, "wrong"));
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, "wrong"));
        assertCode(429, () -> verification.consumeCode(phone, VerificationScene.LOGIN, "wrong"));
        assertFalse(redis.hasKey(VerificationCodeServiceImpl.codeKey(phone, VerificationScene.LOGIN)));
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, code.get()));
    }

    @Test
    void codeConsumptionIsAtomicUnderConcurrentReplay() throws Exception {
        verification.sendCode(phone, VerificationScene.LOGIN);
        assertEquals(1, successes(24, 400,
                () -> verification.consumeCode(phone, VerificationScene.LOGIN, code.get())));
    }

    @Test
    void wrongSceneCannotConsumeLoginCodeAndExpiredCodeCannotPass() {
        verification.sendCode(phone, VerificationScene.LOGIN);
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.RESET_PASSWORD, code.get()));
        String key = VerificationCodeServiceImpl.codeKey(phone, VerificationScene.LOGIN);
        assertTrue(redis.hasKey(key));
        // Redis 对非正 TTL 立即作过期处理，避免使用真实等待或更改服务器时间。
        redis.expire(key, Duration.ZERO);
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, code.get()));
    }

    @Test
    void republishingNewCodeResetsAttemptsAndReplacesExpiry() {
        verification.sendCode(phone, VerificationScene.LOGIN);
        assertCode(400, () -> verification.consumeCode(phone, VerificationScene.LOGIN, "wrong"));
        String key = VerificationCodeServiceImpl.codeKey(phone, VerificationScene.LOGIN);
        redis.expire(key, Duration.ofSeconds(30));
        redis.delete(VerificationCodeServiceImpl.limitKey(phone, VerificationScene.LOGIN));

        verification.sendCode(phone, VerificationScene.LOGIN);

        assertEquals("0", redis.opsForHash().get(key, "attempts"));
        assertEquals(VerificationCodeServiceImpl.sha256(code.get()), redis.opsForHash().get(key, "hash"));
        assertTrue(redis.getExpire(key) > 30);
        verification.consumeCode(phone, VerificationScene.LOGIN, code.get());
    }

    @Test
    void refreshRotationHasOneWinnerAndLogoutRevokesAccessAndRefresh() throws Exception {
        var properties = new JwtProperties();
        properties.setSecretKey("redis-integration-only-test-key");
        var jwt = new JwtUtil(properties, redis);
        jwt.init();
        String userId = "jwt-test-" + UUID.randomUUID();
        String index = "login:user_sessions:" + userId;
        cleanup.add(index);
        var tokens = jwt.createTokenPair(userId);
        cleanup.add("login:session:" + JWT.decode(tokens.accessToken()).getClaim("sid").asString());
        var winner = new AtomicReference<JwtUtil.TokenPair>();

        assertEquals(1, successes(24, 401, () -> winner.set(jwt.refreshTokenPair(tokens.refreshToken()))));
        assertCode(401, () -> jwt.refreshTokenPair(tokens.refreshToken()));
        assertEquals(userId, jwt.getAuthenticatedUserId(winner.get().accessToken()));
        jwt.revokeTokens(winner.get().accessToken());
        assertCode(401, () -> jwt.getAuthenticatedUserId(tokens.accessToken()));
        assertCode(401, () -> jwt.refreshTokenPair(winner.get().refreshToken()));
    }

    @Test
    void aiQuotaCannotBeExceededByConcurrentAcquisitions() throws Exception {
        var properties = new AiModelProperties();
        properties.setDailyCallLimitPerUser(5);
        // 将并发测试的当前时刻置于正午附近，避免测试过程跨自然日。
        int utcHour = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).getHour();
        properties.setQuotaZone(java.time.ZoneOffset.ofHours(12 - utcHour));
        quotaUser = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) + 1;
        if (quotaUser <= 0) quotaUser = 1L;
        var quota = new AiDailyCallQuota(redis, properties);

        assertEquals(5, successes(24, 429, () -> quota.acquire(quotaUser)));
    }

    private static int successes(int count, int rejectedCode, Runnable action) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try { action.run(); return true; }
                    catch (BusinessException rejected) { assertEquals(rejectedCode, rejected.getCode()); return false; }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) succeeded++;
            return succeeded;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertCode(int code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getCode());
    }
}
