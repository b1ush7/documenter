package com.documenter.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Loads the shipped YAML and exercises placeholder resolution plus Boot's real property binding. */
class DeploymentConfigurationTest {
    @Test
    void absentEnvironmentPreservesExistingDevelopmentDefaults() throws IOException {
        var environment = environment(Map.of());
        var verification = bind(environment, "res.verification", VerificationCodeProperties.class);
        assertEquals(6, verification.getCodeLength());
        assertEquals(Duration.ofMinutes(5), verification.getTtl());
        assertEquals(Duration.ofSeconds(60), verification.getResendInterval());
        assertEquals(10, verification.getDailyLimit());
        assertEquals(5, verification.getMaxAttempts());
        assertEquals("true", environment.getProperty("res.verification.mock-enabled"));
        var task = bind(environment, "res.task", TaskExecutorProperties.class);
        assertEquals(2, task.getCorePoolSize());
        assertEquals(4, task.getMaxPoolSize());
        assertEquals(50, task.getQueueCapacity());
        assertEquals(Duration.ofMinutes(10), task.getDefaultTimeout());
        assertTrue(task.isRecoverOnStartup());
        assertFalse(task.isRequeuePendingOnStartup());
        assertEquals("true", environment.getProperty("res.task.placeholder-enabled"));
        assertEquals(8080, environment.getProperty("server.port", Integer.class));
    }

    @Test
    void uploadByteLimitIsSharedByMultipartAndBusinessValidation() throws IOException {
        for (var values : java.util.List.of(Map.<String, String>of(), Map.of(
                "STORAGE_MAX_UPLOAD_BYTES", "104857600", "STORAGE_MAX_REQUEST_BYTES", "110100480",
                "UPLOAD_DAILY_LIMIT", "23"))) {
            var environment = environment(values);
            var storage = bind(environment, "res.storage", StorageProperties.class);
            long multipart = bind(environment, "spring.servlet.multipart.max-file-size", DataSize.class).toBytes();
            long request = bind(environment, "spring.servlet.multipart.max-request-size", DataSize.class).toBytes();
            assertEquals(storage.getUpload().getMaxSizeBytes(), multipart);
            assertTrue(request > multipart);
            assertEquals(values.isEmpty() ? 52428800L : 104857600L, multipart);
            assertEquals(values.isEmpty() ? 60 : 23, storage.getUpload().getHourlyLimit());
        }
    }

    @Test
    void verificationEnvironmentOverridesAllSendingPoliciesAndSwitch() throws IOException {
        var environment = environment(Map.of("VERIFICATION_CODE_LENGTH", "8", "VERIFICATION_TTL", "2m",
                "VERIFICATION_RESEND_INTERVAL", "30s", "VERIFICATION_DAILY_LIMIT", "20",
                "VERIFICATION_MAX_ATTEMPTS", "3", "VERIFICATION_MOCK_ENABLED", "false"));
        var verification = bind(environment, "res.verification", VerificationCodeProperties.class);
        assertEquals(8, verification.getCodeLength());
        assertEquals(Duration.ofMinutes(2), verification.getTtl());
        assertEquals(Duration.ofSeconds(30), verification.getResendInterval());
        assertEquals(20, verification.getDailyLimit());
        assertEquals(3, verification.getMaxAttempts());
        assertEquals("false", environment.getProperty("res.verification.mock-enabled"));
    }

    @Test
    void taskEnvironmentControlsBothPoolCapacityAndLifecyclePolicy() throws IOException {
        var overrides = new HashMap<String, String>();
        overrides.put("TASK_CORE_POOL_SIZE", "3");
        overrides.put("TASK_MAX_POOL_SIZE", "6");
        overrides.put("TASK_QUEUE_CAPACITY", "15");
        overrides.put("TASK_KEEP_ALIVE", "45s");
        overrides.put("TASK_DEFAULT_TIMEOUT", "90s");
        overrides.put("TASK_DEFAULT_MAX_RETRY", "0");
        overrides.put("TASK_SHUTDOWN_AWAIT", "10s");
        overrides.put("TASK_RECOVER_ON_STARTUP", "false");
        overrides.put("TASK_REQUEUE_PENDING_ON_STARTUP", "true");
        overrides.put("TASK_PLACEHOLDER_ENABLED", "false");
        var environment = environment(overrides);
        var task = bind(environment, "res.task", TaskExecutorProperties.class);
        assertEquals(3, task.getCorePoolSize());
        assertEquals(6, task.getMaxPoolSize());
        assertEquals(15, task.getQueueCapacity());
        assertEquals(Duration.ofSeconds(45), task.getKeepAlive());
        assertEquals(Duration.ofSeconds(90), task.getDefaultTimeout());
        assertEquals(0, task.getDefaultMaxRetry());
        assertEquals(Duration.ofSeconds(10), task.getShutdownAwait());
        assertFalse(task.isRecoverOnStartup());
        assertTrue(task.isRequeuePendingOnStartup());
        assertEquals("false", environment.getProperty("res.task.placeholder-enabled"));
    }

    @Test
    void databaseRedisSecretsAndPortAreResolvedFromDeploymentEnvironment() throws IOException {
        var environment = environment(Map.of("DB_HOST", "db.internal", "DB_PORT", "3307", "DB_NAME", "demo",
                "DB_USERNAME", "app", "DB_PASSWORD", "test-db-password", "REDIS_HOST", "cache.internal",
                "REDIS_PORT", "6380", "REDIS_PASSWORD", "test-cache-password", "JWT_SECRET_KEY", "test-jwt-secret",
                "SERVER_PORT", "18080"));
        assertEquals("jdbc:mariadb://db.internal:3307/demo?useSSL=false&serverTimezone=UTC",
                environment.getProperty("spring.datasource.url"));
        assertEquals("app", environment.getProperty("spring.datasource.username"));
        assertEquals("test-db-password", environment.getProperty("spring.datasource.password"));
        assertEquals("cache.internal", environment.getProperty("spring.data.redis.host"));
        assertEquals("6380", environment.getProperty("spring.data.redis.port"));
        assertEquals("test-cache-password", environment.getProperty("spring.data.redis.password"));
        assertEquals("test-jwt-secret", bind(environment, "res.jwt", JwtProperties.class).getSecretKey());
        assertEquals(18080, environment.getProperty("server.port", Integer.class));
    }

    @Test
    void storageOfficeAndModelSettingsBindWithoutContactingExternalServices() throws IOException {
        var overrides = Map.of("STORAGE_LOCAL_ROOT", "./demo/storage", "STORAGE_LOCAL_TEMP", "./demo/tmp",
                "PREVIEW_OFFICE_BACKEND", "kit", "PREVIEW_OFFICE_KIT_NODE", "./runtime/node",
                "PREVIEW_OFFICE_KIT_CLI", "./runtime/cli.js", "AI_ENABLED", "true", "AI_PROVIDER", "stub",
                "AI_DAILY_CALL_LIMIT", "50", "AI_QUOTA_ZONE", "Asia/Shanghai");
        var environment = environment(overrides);
        var storage = bind(environment, "res.storage", StorageProperties.class);
        assertEquals("./demo/storage", storage.getLocal().getRoot());
        assertEquals("./demo/tmp", storage.getLocal().getTemp());
        var preview = bind(environment, "res.preview", PreviewProperties.class);
        assertEquals("kit", preview.getOfficeBackend());
        assertEquals("./runtime/node", preview.getOfficeKitNode());
        assertEquals("./runtime/cli.js", preview.getOfficeKitCli());
        var ai = bind(environment, "res.ai", AiModelProperties.class);
        assertTrue(ai.isEnabled());
        assertEquals("stub", ai.getProvider());
        assertEquals(50, ai.getDailyCallLimitPerUser());
        assertEquals(ZoneId.of("Asia/Shanghai"), ai.getQuotaZone());
    }

    private static <T> T bind(StandardEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment).bind(prefix, type)
                .orElseThrow(() -> new IllegalStateException("Missing configuration: " + prefix));
    }

    private static StandardEnvironment environment(Map<String, String> overrides) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("deployment-test", new HashMap<>(overrides)));
        var loader = new YamlPropertySourceLoader();
        for (String resource : new String[]{"application-dev.yaml", "application.yaml"}) {
            for (var source : loader.load(resource, new ClassPathResource(resource))) {
                environment.getPropertySources().addLast(source);
            }
        }
        return environment;
    }
}
