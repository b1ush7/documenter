package com.documenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 两个真实 JAR 进程；仅杀死本测试创建的进程，所有数据库/存储/Redis 用户键隔离。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_PACKAGED_TEST_JAR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_PACKAGED_CLUSTER_TEST_ENABLED", matches = "true")
class PackagedClusterRecoveryIntegrationTest {
    @TempDir Path workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    @Test
    void survivingJarRecoversReplyAfterOwnerProcessIsForciblyKilled() throws Exception {
        Path jar = Path.of(System.getenv("DOCUMENTER_PACKAGED_TEST_JAR")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar));
        String server = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL");
        assertNotNull(server);
        assertTrue(server.matches("jdbc:mariadb://(?:127\\.0\\.0\\.1|localhost):[0-9]+/"));
        String user = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER");
        String password = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(server, user, password));
        String schema = "documenter_cluster_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        Long ownedUser = null;
        Process first = null, survivor = null;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var modelWorkers = Executors.newVirtualThreadPerTaskExecutor();
        var model = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(modelWorkers);
        model.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().close(); entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
                byte[] body = ("{\"id\":\"process-fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"local\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"local response\"},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException killedClient) { /* 被强杀的客户端已不存在。 */ }
            finally { exchange.close(); }
        });
        model.start();
        try {
            admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci");
            created = true;
            Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--spring.profiles.active=integration",
                    "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.com.documenter=INFO",
                    "--res.task.cluster.enabled=true", "--res.task.cluster.heartbeat=200ms", "--res.task.cluster.lease-duration=3s",
                    "--res.task.cluster.requeue-pending=false", "--res.ai.max-retries=0", "--res.ai.max-model-retries=0");
            builder.environment().putAll(Map.of("SPRING_DATASOURCE_URL", server + schema, "SPRING_DATASOURCE_USERNAME", user,
                    "SPRING_DATASOURCE_PASSWORD", password, "SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.mariadb.jdbc.Driver",
                    "SPRING_DATA_REDIS_HOST", "127.0.0.1", "SPRING_DATA_REDIS_PORT", System.getenv("DOCUMENTER_TEST_REDIS_PORT"),
                    "SPRING_DATA_REDIS_USERNAME", "default", "SPRING_DATA_REDIS_PASSWORD", ""));
            builder.environment().putAll(Map.of("STORAGE_LOCAL_ROOT", workspace.resolve("storage").toString(),
                    "STORAGE_LOCAL_TEMP", workspace.resolve("temp").toString(), "STORAGE_RECYCLE_ENABLED", "false",
                    "VERIFICATION_MOCK_ENABLED", "true", "OCR_ENABLED", "false", "AI_ENABLED", "true",
                    "AI_PROVIDER", "openai", "AI_API_KEY", "local-only", "AI_MODEL_NAME", "local",
                    "AI_BASE_URL", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1"));
            builder.environment().put("AI_TIMEOUT", "30s");
            builder.environment().put("JWT_SECRET_KEY", "process-test-only-" + UUID.randomUUID());
            Path firstLog = workspace.resolve("owner.log");
            first = builder.redirectErrorStream(true).redirectOutput(firstLog.toFile()).start();
            int firstPort = PackagedBackendSmokeIntegrationTest.awaitPort(first, firstLog);
            String firstBase = "http://127.0.0.1:" + firstPort;
            var database = new JdbcTemplate(new DriverManagerDataSource(server + schema, user, password));
            long firstId = 1_000_000_000_000L + new java.security.SecureRandom().nextLong(1_000_000_000_000L);
            database.execute("ALTER TABLE `user` AUTO_INCREMENT=" + firstId);
            api(firstBase, "POST", "/user/register", Map.of("username", "cluster_test", "password", "IntegrationOnly123!"));
            ownedUser = database.queryForObject("SELECT id FROM `user` WHERE username='cluster_test'", Long.class);
            token = api(firstBase, "POST", "/user/login", Map.of("account", "cluster_test", "password", "IntegrationOnly123!")).path("accessToken").asText();
            long session = api(firstBase, "POST", "/ai/sessions", Map.of("title", "process recovery")).path("id").asLong();
            var pending = http.sendAsync(request(firstBase, "POST", "/ai/sessions/" + session + "/replies",
                    Map.of("content", "仅本机夹具", "idempotencyKey", UUID.randomUUID().toString())), HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(entered.await(8, TimeUnit.SECONDS));
            long reply = database.queryForObject("SELECT MAX(id) FROM ai_message WHERE run_status='RUNNING'", Long.class);
            String oldOwner = database.queryForObject("SELECT execution_owner FROM ai_message WHERE id=?", String.class, reply);
            assertNotNull(oldOwner);
            Path survivorLog = workspace.resolve("survivor.log");
            survivor = builder.redirectOutput(survivorLog.toFile()).start();
            String survivorBase = "http://127.0.0.1:" + PackagedBackendSmokeIntegrationTest.awaitPort(survivor, survivorLog);
            Thread.sleep(3200);
            assertEquals("RUNNING", database.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply));
            assertEquals(ownedUser.longValue(), api(survivorBase, "GET", "/user/me", null).path("id").asLong());
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && !"FAILED".equals(database.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply))) Thread.sleep(100);
            assertEquals("FAILED", api(survivorBase, "GET", "/ai/sessions/" + session + "/replies/" + reply, null).path("runStatus").asText());
            assertEquals("NONE", database.queryForObject("SELECT plan_status FROM ai_message WHERE id=?", String.class, reply));
            assertNull(database.queryForObject("SELECT result_json FROM ai_message WHERE id=?", String.class, reply));
            release.countDown();
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            var normal = api(survivorBase, "POST", "/ai/sessions/" + session + "/replies",
                    Map.of("content", "健康实例继续处理", "idempotencyKey", UUID.randomUUID().toString()));
            assertEquals("COMPLETED", normal.path("runStatus").asText());
            assertNotEquals(oldOwner, database.queryForObject("SELECT execution_owner FROM ai_message WHERE id=?", String.class, normal.path("message").path("id").asLong()));
            api(survivorBase, "POST", "/user/logout", null);
        } finally {
            release.countDown();
            stopOwnedProcess(first); stopOwnedProcess(survivor);
            model.stop(0); modelWorkers.shutdownNow(); modelWorkers.awaitTermination(5, TimeUnit.SECONDS);
            try { if (ownedUser != null) cleanOwnedRedisUser(ownedUser); }
            finally {
                if (created) {
                    if (!schema.matches("documenter_cluster_it_[0-9a-f]{32}")) throw new IllegalStateException("拒绝清理非测试库");
                    admin.execute("DROP DATABASE `" + schema + "`");
                }
            }
        }
    }

    private HttpRequest request(String base, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }
    private JsonNode api(String base, String method, String path, Object body) throws Exception {
        var response = json.readTree(http.send(request(base, method, path, body), HttpResponse.BodyHandlers.ofByteArray()).body());
        assertEquals(0, response.path("code").asInt(-1), path);
        return response.path("data");
    }
    private static void stopOwnedProcess(Process process) throws Exception {
        if (process != null && process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
    }
    private static void cleanOwnedRedisUser(long user) {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("DOCUMENTER_TEST_REDIS_PORT")));
        var connections = new LettuceConnectionFactory(configuration, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
        connections.afterPropertiesSet(); connections.start();
        try {
            var redis = new StringRedisTemplate(connections);
            String index = "login:user_sessions:" + user;
            var sessions = redis.opsForSet().members(index);
            if (sessions != null) for (String session : sessions) redis.delete("login:session:" + session);
            redis.delete(index);
            try (var keys = redis.scan(ScanOptions.scanOptions().match("ai:daily-call:" + user + ":*").count(100).build())) {
                while (keys.hasNext()) redis.delete(keys.next());
            }
        } finally { connections.destroy(); }
    }
}
