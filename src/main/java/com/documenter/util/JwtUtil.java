package com.documenter.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.documenter.configuration.JwtProperties;
import com.documenter.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtUtil {
    private static final String SESSION_PREFIX = "login:session:";
    /** 用户会话索引：记录该用户当前所有活跃会话，用于「退出全部设备」与改密后撤销。 */
    private static final String USER_SESSIONS_PREFIX = "login:user_sessions:";
    private static final String SESSION_CLAIM = "sid";
    private static final String TYPE_CLAIM = "token_type";
    // 校验旧值和替换新值必须在 Redis 内原子执行，防止同一个 Refresh Token 被并发使用。
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);
    // ARGV[1] 固定为用户 ID，ARGV[2..] 为待撤销的会话 ID。
    // 逐个检查「会话当前值是否仍属于该用户」，属于才删除。
    // 这样即便用 SMEMBERS 取快照时该会话已被新一轮刷新替换，也不会误删新签发但尚未返回给前端的会话。
    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>("""
            local userId = ARGV[1]
            local removed = 0
            for i = 2, #ARGV do
                local sid = ARGV[i]
                local key = KEYS[1] .. sid
                local current = redis.call('GET', key)
                if current and string.sub(current, 1, #userId + 1) == userId .. ':' then
                    redis.call('DEL', key)
                    redis.call('SREM', KEYS[2], sid)
                    removed = removed + 1
                end
            end
            return removed
            """, Long.class);

    private final JwtProperties properties;
    private final StringRedisTemplate redisTemplate;
    private Algorithm algorithm;

    public JwtUtil(JwtProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        if (properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new IllegalArgumentException("JWT secret-key 不能为空");
        }
        requirePositiveTtl(properties.getAccessTokenTtl());
        requirePositiveTtl(properties.getRefreshTokenTtl());
        algorithm = Algorithm.HMAC256(properties.getSecretKey());
    }

    /** 后续登录接口调用此方法，取得同一会话的两个 Token。 */
    public TokenPair createTokenPair(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        String sessionId = UUID.randomUUID().toString();
        TokenPair pair = buildTokenPair(userId, sessionId);
        redisTemplate.opsForValue().set(sessionKey(sessionId),
                sessionValue(userId, pair.refreshToken()), properties.getRefreshTokenTtl());
        trackUserSession(userId, sessionId);
        return pair;
    }

    /** 兼容原有签发方法；需要长期登录时应使用 createTokenPair。 */
    public String createToken(String userId) {
        return createTokenPair(userId).accessToken();
    }

    /** 仅凭有效 Refresh Token 即可刷新，不要求 Access Token 尚未过期。 */
    public TokenPair refreshTokenPair(String refreshToken) {
        DecodedJWT jwt = verifyRefreshToken(refreshToken);
        String userId = jwt.getClaim(properties.getKey()).asString();
        String sessionId = jwt.getClaim(SESSION_CLAIM).asString();
        TokenPair pair = buildTokenPair(userId, sessionId);
        Long rotated = redisTemplate.execute(ROTATE_SCRIPT, List.of(sessionKey(sessionId)),
                sessionValue(userId, refreshToken), sessionValue(userId, pair.refreshToken()),
                Long.toString(properties.getRefreshTokenTtl().toSeconds()));
        if (!Long.valueOf(1).equals(rotated)) {
            throw new BusinessException(401, "Refresh Token 已失效，请重新登录");
        }
        trackUserSession(userId, sessionId);
        return pair;
    }

    /** 普通访问只校验 Access Token，Refresh Token 不能用于访问受保护资源。 */
    public DecodedJWT verify(String token) {
        return verifyToken(token, "access");
    }

    public DecodedJWT verifyRefreshToken(String token) {
        return verifyToken(token, "refresh");
    }

    public String getOpenid(String token) {
        return verify(token).getClaim(properties.getKey()).asString();
    }

    /** 同时检查 JWT 和 Redis 会话；退出后尚未过期的 Access Token 也不能访问。 */
    public String getAuthenticatedUserId(String token) {
        DecodedJWT jwt = verify(token);
        String userId = jwt.getClaim(properties.getKey()).asString();
        String cached = redisTemplate.opsForValue().get(sessionKey(jwt.getClaim(SESSION_CLAIM).asString()));
        if (cached == null || !cached.startsWith(userId + ":")) {
            throw new BusinessException(401, "未授权访问");
        }
        return userId;
    }

    public boolean validateToken(String token) {
        getAuthenticatedUserId(token);
        return true;
    }

    /** 删除会话，同时撤销该会话下的 Access Token 与 Refresh Token。 */
    public void revokeTokens(String accessToken) {
        DecodedJWT jwt = verify(accessToken);
        String userId = jwt.getClaim(properties.getKey()).asString();
        revokeSession(userId, jwt.getClaim(SESSION_CLAIM).asString());
    }

    /**
     * 撤销该用户在指定会话之外的全部会话，即「退出全部设备」。
     *
     * <p>撤销包含两部分：逐个删除 Redis 中的会话记录，以及删除会话自身的 Access Token
     * 与 Refresh Token。只删后者会导致用户索引里残留失效的会话 ID。
     *
     * @return 实际被撤销的会话数量
     */
    public long revokeAllSessions(String userId, String keepSessionId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        Set<String> sessionIds = redisTemplate.opsForSet().members(userSessionsKey(userId));
        List<String> targets = sessionIds == null ? Collections.emptyList()
                : sessionIds.stream().filter(id -> !id.equals(keepSessionId)).toList();
        if (targets.isEmpty()) {
            return 0L;
        }
        // 用 Lua 在 Redis 内原子执行「校验归属 → 删除会话 → 移出索引」，
        // 避免与并发的刷新操作互相覆盖。
        List<String> args = new java.util.ArrayList<>(targets.size() + 1);
        args.add(userId);
        args.addAll(targets);
        Long removed = redisTemplate.execute(REVOKE_ALL_SCRIPT,
                List.of(SESSION_PREFIX, userSessionsKey(userId)), args.toArray());
        return removed == null ? 0L : removed;
    }

    private void revokeSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.delete(sessionKey(sessionId));
        if (userId != null && !userId.isBlank()) {
            redisTemplate.opsForSet().remove(userSessionsKey(userId), sessionId);
        }
    }

    /** 把会话登记到用户索引，TTL 与会话一致，避免索引无限增长。 */
    private void trackUserSession(String userId, String sessionId) {
        String key = userSessionsKey(userId);
        redisTemplate.opsForSet().add(key, sessionId);
        redisTemplate.expire(key, properties.getRefreshTokenTtl());
    }

    private static String userSessionsKey(String userId) {
        return USER_SESSIONS_PREFIX + userId;
    }

    private TokenPair buildTokenPair(String userId, String sessionId) {
        Instant now = Instant.now();
        return new TokenPair(
                createJwt(userId, sessionId, "access", now, properties.getAccessTokenTtl()),
                createJwt(userId, sessionId, "refresh", now, properties.getRefreshTokenTtl()),
                properties.getAccessTokenTtl().toSeconds(), properties.getRefreshTokenTtl().toSeconds());
    }

    private String createJwt(String userId, String sessionId, String type, Instant now, Duration ttl) {
        return JWT.create()
                .withIssuer(properties.getIssuer())
                .withClaim(properties.getKey(), userId)
                .withClaim(SESSION_CLAIM, sessionId)
                .withClaim(TYPE_CLAIM, type)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(ttl))
                .sign(algorithm);
    }

    private DecodedJWT verifyToken(String token, String type) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "未授权访问");
        }
        try {
            DecodedJWT jwt = JWT.require(algorithm)
                    .withIssuer(properties.getIssuer())
                    .withClaim(TYPE_CLAIM, type)
                    .withClaimPresence("exp")
                    .withClaimPresence("jti")
                    .withClaimPresence(SESSION_CLAIM)
                    .withClaimPresence(properties.getKey())
                    .build().verify(token);
            String userId = jwt.getClaim(properties.getKey()).asString();
            String sessionId = jwt.getClaim(SESSION_CLAIM).asString();
            if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
                throw new BusinessException(401, "未授权访问");
            }
            return jwt;
        } catch (JWTVerificationException e) {
            throw new BusinessException(401, "Token 无效或已过期");
        }
    }

    private static String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private static String sessionValue(String userId, String refreshToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return userId + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算 Refresh Token 摘要", e);
        }
    }

    private static void requirePositiveTtl(Duration ttl) {
        if (ttl == null || ttl.toSeconds() < 1 || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Token 有效期必须为正整数秒");
        }
    }

    public record TokenPair(String accessToken, String refreshToken,
                            long expiresIn, long refreshExpiresIn) {
    }
}
