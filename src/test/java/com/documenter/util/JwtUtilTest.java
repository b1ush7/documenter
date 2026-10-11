package com.documenter.util;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.documenter.configuration.JwtProperties;
import com.documenter.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JwtUtil 双 Token 行为测试（TODO 3.1）。
 *
 * <p>本机没有 Redis，因此用 Mockito + 内存 Map 构造一个最小可用的 Redis 替身：
 * 只实现 JwtUtil 实际用到的 GET/SET/DEL/EXPIRE、Set 的 add/remove/members，
 * 以及两段 Lua 脚本的等价语义。脚本语义是在 Java 里重写的，与 Lua 同构，
 * 用于验证轮换、撤销这些<b>逻辑</b>是否正确。
 *
 * <p>局限必须说清：这不能替代真实 Redis 验证。Lua 的语法错误、
 * 命令细节与 TTL 的真实行为都只能在真 Redis 上确认。
 */
class JwtUtilTest {

    private static final String USER_A = "1001";
    private static final String USER_B = "1002";

    private StringRedisTemplate redis;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        redis = FakeRedis.create();
        jwtUtil = jwtUtil(redis);
    }

    private static JwtUtil jwtUtil(StringRedisTemplate template) {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey("unit-test-secret-key-please-do-not-use");
        properties.setIssuer("documenter");
        properties.setKey("id");
        properties.setAuthorizationName("Authorization");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        JwtUtil util = new JwtUtil(properties, template);
        util.init();
        return util;
    }

    @Test
    @DisplayName("createTokenPair 同时签发 Access 与 Refresh，有效期与配置一致")
    void createTokenPairIssuesBothTokens() {
        JwtUtil.TokenPair pair = jwtUtil.createTokenPair(USER_A);

        assertEquals(15 * 60, pair.expiresIn());
        assertEquals(7 * 24 * 3600, pair.refreshExpiresIn());
        assertEquals(USER_A, jwtUtil.getAuthenticatedUserId(pair.accessToken()));
        assertNotEquals(pair.accessToken(), pair.refreshToken());
    }

    @Test
    @DisplayName("Refresh Token 不能当作 Access Token 用于访问受保护资源")
    void refreshTokenCannotBeUsedForAccess() {
        JwtUtil.TokenPair pair = jwtUtil.createTokenPair(USER_A);

        // token_type 声明不同，应被拒绝
        assertThrows(BusinessException.class, () -> jwtUtil.verify(pair.refreshToken()));
        assertThrows(BusinessException.class, () -> jwtUtil.getAuthenticatedUserId(pair.refreshToken()));
    }

    @Test
    @DisplayName("刷新后旧的 Refresh Token 立即失效，新的可用")
    void rotationInvalidatesOldRefreshToken() {
        JwtUtil.TokenPair first = jwtUtil.createTokenPair(USER_A);

        JwtUtil.TokenPair rotated = jwtUtil.refreshTokenPair(first.refreshToken());

        // 新 Token 可用
        assertEquals(USER_A, jwtUtil.getAuthenticatedUserId(rotated.accessToken()));
        // 旧 Refresh Token 不能再用（TODO 3.1 要求的旧 Token 失效）
        assertThrows(BusinessException.class, () -> jwtUtil.refreshTokenPair(first.refreshToken()));
        // 同一个 Refresh Token 也不能重复使用，否则等于没有轮换
        assertThrows(BusinessException.class, () -> jwtUtil.refreshTokenPair(first.refreshToken()));
    }

    @Test
    @DisplayName("刷新后旧 Access Token 仍可访问，因为会话未被撤销")
    void oldAccessTokenStaysValidAfterRotation() {
        JwtUtil.TokenPair first = jwtUtil.createTokenPair(USER_A);
        jwtUtil.refreshTokenPair(first.refreshToken());

        // 轮换只换 Refresh Token，会话本身还在，旧 Access Token 在有效期内仍应放行
        assertEquals(USER_A, jwtUtil.getAuthenticatedUserId(first.accessToken()));
    }

    @Test
    @DisplayName("退出当前设备后 Access 与 Refresh 都失效")
    void revokeTokensKillsSession() {
        JwtUtil.TokenPair pair = jwtUtil.createTokenPair(USER_A);

        jwtUtil.revokeTokens(pair.accessToken());

        assertThrows(BusinessException.class, () -> jwtUtil.getAuthenticatedUserId(pair.accessToken()));
        assertThrows(BusinessException.class, () -> jwtUtil.refreshTokenPair(pair.refreshToken()));
    }

    @Test
    @DisplayName("退出全部设备：保留当前会话，撤销其它会话")
    void revokeAllSessionsKeepsCurrent() {
        JwtUtil.TokenPair device1 = jwtUtil.createTokenPair(USER_A);
        JwtUtil.TokenPair device2 = jwtUtil.createTokenPair(USER_A);
        JwtUtil.TokenPair device3 = jwtUtil.createTokenPair(USER_A);

        String currentSessionId = sessionIdOf(device1.accessToken());
        long revoked = jwtUtil.revokeAllSessions(USER_A, currentSessionId);

        assertEquals(2L, revoked, "应只撤销另外两个会话");
        // 当前设备保持登录
        assertEquals(USER_A, jwtUtil.getAuthenticatedUserId(device1.accessToken()));
        // 其它设备被登出
        assertThrows(BusinessException.class, () -> jwtUtil.getAuthenticatedUserId(device2.accessToken()));
        assertThrows(BusinessException.class, () -> jwtUtil.getAuthenticatedUserId(device3.accessToken()));
        assertThrows(BusinessException.class, () -> jwtUtil.refreshTokenPair(device2.refreshToken()));
    }

    @Test
    @DisplayName("退出全部设备只影响本人，不影响其他用户会话")
    void revokeAllSessionsIsolatedPerUser() {
        JwtUtil.TokenPair mine = jwtUtil.createTokenPair(USER_A);
        JwtUtil.TokenPair others = jwtUtil.createTokenPair(USER_B);

        jwtUtil.revokeAllSessions(USER_A, sessionIdOf(mine.accessToken()));

        // 另一个用户的会话必须完好无损
        assertEquals(USER_B, jwtUtil.getAuthenticatedUserId(others.accessToken()));
        assertEquals(USER_B, jwtUtil.getAuthenticatedUserId(
                jwtUtil.refreshTokenPair(others.refreshToken()).accessToken()));
    }

    @Test
    @DisplayName("退出全部设备后没有残留会话，重复调用返回 0")
    void revokeAllSessionsHasNoResidue() {
        JwtUtil.TokenPair device1 = jwtUtil.createTokenPair(USER_A);
        jwtUtil.createTokenPair(USER_A);
        jwtUtil.createTokenPair(USER_A);

        String currentSessionId = sessionIdOf(device1.accessToken());
        assertEquals(2L, jwtUtil.revokeAllSessions(USER_A, currentSessionId));
        // 索引被正确清理：没有残留的会话 ID 可撤销
        assertEquals(0L, jwtUtil.revokeAllSessions(USER_A, currentSessionId));
    }

    @Test
    @DisplayName("伪造或篡改的 Token 被拒绝")
    void tamperedTokenRejected() {
        JwtUtil.TokenPair pair = jwtUtil.createTokenPair(USER_A);

        assertThrows(BusinessException.class, () -> jwtUtil.verify(pair.accessToken() + "x"));
        assertThrows(BusinessException.class, () -> jwtUtil.verify("not-a-jwt"));
        assertThrows(BusinessException.class, () -> jwtUtil.verify(""));

        // 用不同密钥签发的 Token 不能被接受
        JwtProperties other = new JwtProperties();
        other.setSecretKey("a-completely-different-secret-key-value");
        other.setIssuer("documenter");
        other.setKey("id");
        other.setAccessTokenTtl(Duration.ofMinutes(15));
        other.setRefreshTokenTtl(Duration.ofDays(7));
        JwtUtil foreign = new JwtUtil(other, FakeRedis.create());
        foreign.init();
        String foreignToken = foreign.createTokenPair(USER_A).accessToken();
        assertThrows(BusinessException.class, () -> jwtUtil.verify(foreignToken));
    }

    @Test
    @DisplayName("Redis 会话被清空后 Access Token 立即失效")
    void accessTokenRequiresLiveSession() {
        JwtUtil.TokenPair pair = jwtUtil.createTokenPair(USER_A);
        assertEquals(USER_A, jwtUtil.getAuthenticatedUserId(pair.accessToken()));

        // 模拟运维直接清库：会话键消失
        FakeRedis.clear(redis);

        assertThrows(BusinessException.class, () -> jwtUtil.getAuthenticatedUserId(pair.accessToken()));
    }

    /** 从 Access Token 中取出 sid，业务代码里由 UserController 做同样的事。 */
    private String sessionIdOf(String accessToken) {
        DecodedJWT jwt = jwtUtil.verify(accessToken);
        return jwt.getClaim("sid").asString();
    }

    /**
     * 最小 Redis 替身。
     *
     * <p>关键坑：Mockito 对形如 {@code execute(script, keys, Object... args)} 的调用，
     * 会把 varargs <b>展平</b>放进 {@code getArguments()}，因此不能按
     * {@code getArgument(2)} 当成数组来取，必须从下标 2 开始收集剩余参数。
     * 一开始就是踩了这个坑导致 ClassCastException。
     */
    private static final class FakeRedis {

        private static final ConcurrentHashMap<StringRedisTemplate, ConcurrentHashMap<String, String>> STORE =
                new ConcurrentHashMap<>();

        static void clear(StringRedisTemplate template) {
            // 必须原地清空：真 Redis 里 FLUSHDB 是清内容，不是换掉数据库句柄。
            // 若这里把整个 map 从静态表移除，JwtUtil 持有的旧引用仍然有效，测试就失去意义。
            ConcurrentHashMap<String, String> values = STORE.get(template);
            if (values != null) {
                values.clear();
            }
        }

        @SuppressWarnings("unchecked")
        static StringRedisTemplate create() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Set<String>> sets = new ConcurrentHashMap<>();
            STORE.put(template, values);

            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
            // set 返回 void，只能用 doAnswer 打桩
            doAnswer(inv -> {
                values.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
            doAnswer(inv -> {
                values.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOps).set(anyString(), anyString());

            SetOperations<String, String> setOps = mock(SetOperations.class);
            when(template.opsForSet()).thenReturn(setOps);
            when(setOps.add(anyString(), any())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                Set<String> set = sets.computeIfAbsent(key, x -> new LinkedHashSet<>());
                long added = 0;
                for (String member : stringVarargs(inv, 1)) {
                    if (set.add(member)) {
                        added++;
                    }
                }
                return added;
            });
            when(setOps.remove(anyString(), any())).thenAnswer(inv -> {
                Set<String> set = sets.get(inv.getArgument(0));
                if (set == null) {
                    return 0L;
                }
                long removed = 0;
                for (Object member : stringVarargs(inv, 1)) {
                    if (set.remove(member)) {
                        removed++;
                    }
                }
                return removed;
            });
            when(setOps.members(anyString())).thenAnswer(inv -> {
                Set<String> set = sets.get(inv.getArgument(0));
                return set == null ? Set.of() : new LinkedHashSet<>(set);
            });

            when(template.delete(anyString())).thenAnswer(inv -> values.remove(inv.getArgument(0)) != null);
            when(template.delete(nullable(Collection.class))).thenAnswer(inv -> {
                long count = 0;
                for (Object key : (Collection<Object>) inv.getArgument(0)) {
                    if (values.remove(String.valueOf(key)) != null) {
                        count++;
                    }
                }
                return count;
            });
            when(template.expire(anyString(), anyLong(), any())).thenReturn(true);
            when(template.expire(anyString(), any(Duration.class))).thenReturn(true);

            when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenAnswer((org.mockito.stubbing.Answer<Long>) inv -> {
                        RedisScript<Long> script = inv.getArgument(0);
                        List<String> keys = inv.getArgument(1);
                        List<Object> args = remainingArguments(inv, 2);
                        return evaluate(script.getScriptAsString(), keys, args, values, sets);
                    });

            return template;
        }

        /** varargs 被 Mockito 展平，把下标 from 之后的参数收集成列表。 */
        private static List<Object> remainingArguments(InvocationOnMock invocation, int from) {
            Object[] all = invocation.getArguments();
            List<Object> rest = new ArrayList<>();
            for (int i = from; i < all.length; i++) {
                rest.add(all[i]);
            }
            return rest;
        }

        private static List<String> stringVarargs(InvocationOnMock invocation, int from) {
            List<String> result = new ArrayList<>();
            for (Object value : remainingArguments(invocation, from)) {
                result.add(String.valueOf(value));
            }
            return result;
        }

        /** 按脚本文本分派。两段脚本的语义在这里用 Java 重写。 */
        private static Long evaluate(String lua, List<String> keys, List<Object> args,
                                     ConcurrentHashMap<String, String> values,
                                     ConcurrentHashMap<String, Set<String>> sets) {
            String sessionKey = keys.get(0);

            if (lua.contains("SREM")) {
                // REVOKE_ALL_SCRIPT：ARGV[1] 是用户 ID，其后是待撤销会话
                String userId = String.valueOf(args.get(0));
                String userSessionsKey = keys.get(1);
                long removed = 0;
                for (int i = 1; i < args.size(); i++) {
                    String sid = String.valueOf(args.get(i));
                    String fullKey = sessionKey + sid;
                    String current = values.get(fullKey);
                    if (current != null && current.startsWith(userId + ":")) {
                        values.remove(fullKey);
                        Set<String> index = sets.get(userSessionsKey);
                        if (index != null) {
                            index.remove(sid);
                        }
                        removed++;
                    }
                }
                return removed;
            }

            // ROTATE_SCRIPT：值相等才替换，等价于 compare-and-swap
            String expectedCurrent = String.valueOf(args.get(0));
            String newValue = String.valueOf(args.get(1));
            String existing = values.get(sessionKey);
            if (existing == null || !existing.equals(expectedCurrent)) {
                return 0L;
            }
            values.put(sessionKey, newValue);
            return 1L;
        }

        private FakeRedis() {
        }
    }
}
