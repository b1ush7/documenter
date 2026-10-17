package com.documenter.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.documenter.configuration.JwtProperties;
import com.documenter.configuration.SecurityConfig;
import com.documenter.entity.User;
import com.documenter.mapper.UserMapper;
import com.documenter.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实 JWT 验证器及完整 SecurityFilterChain；仅 Redis 和用户 Mapper 使用替身。 */
@SpringJUnitWebConfig(SecurityFilterChainTest.TestConfig.class)
class SecurityFilterChainTest {
    private static final String TEST_SECRET = "security-chain-test-only-secret-do-not-use-in-production";
    @Autowired private WebApplicationContext context;
    @Autowired private JwtUtil jwt;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserMapper users;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private MockMvc mvc;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reset(redis, users);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        doAnswer(call -> {
            sessions.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> sessions.get(call.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> sessions.remove(call.getArgument(0)) != null);
        user = new User();
        user.setId(7L);
        user.setUsername("current-user");
        user.setRole("USER");
        user.setStatus("ENABLED");
        when(users.selectById(7L)).thenReturn(user);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void validAccessAuthenticatesCurrentDatabaseUserWithoutExtendingSession() throws Exception {
        var tokens = jwt.createTokenPair("7");
        clearInvocations(redis);

        mvc.perform(get("/private/who").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk()).andExpect(content().string("current-user"));

        verify(redis, never()).expire(anyString(), any(Duration.class));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "BAD_SIGNATURE", "REFRESH", "MALFORMED", "WRONG_ISSUER", "MISSING_EXPIRY"})
    void invalidTokensAreRejectedBeforeUserLookup(String reason) throws Exception {
        var tokens = jwt.createTokenPair("7");
        String token = switch (reason) {
            case "EXPIRED" -> signedToken(TEST_SECRET, "documenter", Instant.now().minusSeconds(60));
            case "BAD_SIGNATURE" -> signedToken("different-key", "documenter", Instant.now().plusSeconds(60));
            case "WRONG_ISSUER" -> signedToken(TEST_SECRET, "another-app", Instant.now().plusSeconds(60));
            case "MISSING_EXPIRY" -> signedToken(TEST_SECRET, "documenter", null);
            case "REFRESH" -> tokens.refreshToken();
            default -> "broken.jwt.value";
        };

        mvc.perform(get("/private/who").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(users);
    }

    @Test
    void disabledAndDeletedUsersCannotUsePreviouslyIssuedAccess() throws Exception {
        var tokens = jwt.createTokenPair("7");
        user.setStatus("DISABLED");
        mvc.perform(get("/private/who").header("Authorization", tokens.accessToken()))
                .andExpect(status().isUnauthorized());
        when(users.selectById(7L)).thenReturn(null);
        mvc.perform(get("/private/who").header("Authorization", tokens.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminAuthorizationUsesCurrentDatabaseRoleOnEveryRequest() throws Exception {
        var tokens = jwt.createTokenPair("7");
        mvc.perform(get("/admin/probe").header("Authorization", tokens.accessToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        user.setRole("ADMIN");
        mvc.perform(get("/admin/probe").header("Authorization", tokens.accessToken()))
                .andExpect(status().isOk());
        user.setRole("USER");
        mvc.perform(get("/admin/probe").header("Authorization", tokens.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesStillSignedAccessAndAuthenticationNeverLeaksToNextRequest() throws Exception {
        var tokens = jwt.createTokenPair("7");
        mvc.perform(post("/user/logout").header("Authorization", tokens.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/private/who").header("Authorization", tokens.accessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/private/who")).andExpect(status().isUnauthorized());
        assertThrows(com.documenter.exception.BusinessException.class,
                () -> jwt.getAuthenticatedUserId(tokens.accessToken()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/login", "/user/login/by-code", "/user/login/code", "/user/refresh",
            "/user/register", "/user/register/by-code", "/user/register/code", "/user/verify/code",
            "/user/reset-password", "/user/reset-password/code"})
    void publicEndpointsRemainReachableWithExpiredAccessHeader(String path) throws Exception {
        mvc.perform(get(path).servletPath(path)
                        .header("Authorization", signedToken(TEST_SECRET, "documenter", Instant.now().minusSeconds(60))))
                .andExpect(status().isOk()).andExpect(content().string("public"));
        verifyNoInteractions(users, redis);
    }

    @Test
    void signedAccessCannotReuseSessionOwnedByDifferentUser() throws Exception {
        var tokens = jwt.createTokenPair("7");
        String sid = JWT.decode(tokens.accessToken()).getClaim("sid").asString();
        sessions.put("login:session:" + sid, "70:foreign-refresh-hash");
        mvc.perform(get("/private/who").header("Authorization", tokens.accessToken()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    private static String signedToken(String key, String issuer, Instant expires) {
        var builder = JWT.create().withIssuer(issuer).withClaim("id", "7").withClaim("sid", "test-session")
                .withClaim("token_type", "access").withJWTId(UUID.randomUUID().toString());
        if (expires != null) builder.withExpiresAt(expires);
        return builder.sign(Algorithm.HMAC256(key));
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean JwtProperties jwtProperties() {
            var properties = new JwtProperties();
            properties.setSecretKey(TEST_SECRET);
            return properties;
        }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean UserMapper users() { return mock(UserMapper.class); }
        @Bean JwtUtil jwt(JwtProperties properties, StringRedisTemplate redis) { return new JwtUtil(properties, redis); }
        @Bean JwtAuthenticationFilter filter(JwtUtil jwt, UserMapper users, JwtProperties properties) {
            return new JwtAuthenticationFilter(jwt, users, properties);
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ProbeController probe() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/private/who") String who() {
            return ((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        }
        @GetMapping("/admin/probe") String admin() { return "admin"; }
        @GetMapping({"/user/login", "/user/login/by-code", "/user/login/code", "/user/refresh",
                "/user/register", "/user/register/by-code", "/user/register/code", "/user/verify/code",
                "/user/reset-password", "/user/reset-password/code"})
        String publicEndpoint() { return "public"; }
    }
}
