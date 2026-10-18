package com.documenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 必须先 package，再显式传入刚构建的 JAR；子进程仅连接新建的随机测试库。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_PACKAGED_TEST_JAR", matches = ".+")
class PackagedBackendSmokeIntegrationTest {
    @TempDir Path workspace;

    @Test
    void executableJarServesApiAndRecoversPersistedWorkAfterForcedProcessDeath() throws Exception {
        Path jar = Path.of(System.getenv("DOCUMENTER_PACKAGED_TEST_JAR")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(jar));
        try (var archive = new ZipFile(jar.toFile())) {
            assertNotNull(archive.getEntry("BOOT-INF/classes/application-service.yaml"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/db/migration/V8__storage_recycling.sql"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/db/migration/V9__execution_leases.sql"));
            assertNotNull(archive.getEntry("BOOT-INF/classes/office/office_convert.py"));
        }
        String server = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL");
        assertNotNull(server);
        assertTrue(server.matches("jdbc:mariadb://(?:127\\.0\\.0\\.1|localhost):[0-9]+/"));
        String user = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER");
        String password = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(server, user, password));
        String schema = "documenter_jar_it_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        Process child = null;
        try {
            admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci");
            created = true;
            Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--spring.profiles.active=integration",
                    "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.com.documenter=INFO");
            builder.environment().putAll(Map.of("SPRING_DATASOURCE_URL", server + schema,
                    "SPRING_DATASOURCE_USERNAME", user, "SPRING_DATASOURCE_PASSWORD", password,
                    "SPRING_DATASOURCE_DRIVER_CLASS_NAME", "org.mariadb.jdbc.Driver",
                    "SPRING_DATA_REDIS_HOST", "127.0.0.1", "SPRING_DATA_REDIS_PORT", System.getenv("DOCUMENTER_TEST_REDIS_PORT"),
                    "SPRING_DATA_REDIS_USERNAME", "default", "SPRING_DATA_REDIS_PASSWORD", ""));
            builder.environment().putAll(Map.of("STORAGE_LOCAL_ROOT", workspace.resolve("storage").toString(),
                    "STORAGE_LOCAL_TEMP", workspace.resolve("temp").toString(), "STORAGE_RECYCLE_ENABLED", "false",
                    "VERIFICATION_MOCK_ENABLED", "true", "OCR_ENABLED", "false", "AI_ENABLED", "false",
                    "TASK_RECOVER_ON_STARTUP", "true", "TASK_REQUEUE_PENDING_ON_STARTUP", "true",
                    "TASK_CLUSTER_ENABLED", "false",
                    "JWT_SECRET_KEY", "packaged-test-only-" + UUID.randomUUID()));
            Path log = workspace.resolve("application.log");
            child = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            int port = awaitPort(child, log);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var json = new ObjectMapper();
            var unauthenticated = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user/me"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, unauthenticated.statusCode());
            assertEquals(401, json.readTree(unauthenticated.body()).path("code").asInt());
            // 不登录、不创建 Redis 会话；业务库只有本测试的注册数据。
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user/register"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of(
                            "username", "packaged_test", "password", "IntegrationOnly123!")))).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(0, json.readTree(response.body()).path("code").asInt(-1));
            var database = new JdbcTemplate(new DriverManagerDataSource(server + schema, user, password));
            assertEquals(9, database.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class));
            assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM `user` WHERE username='packaged_test'", Integer.class));

            // 在仍存活的子进程期间落库中断夹具，再强杀；不依赖 Spring 的关闭钩子。
            long owner = database.queryForObject("SELECT id FROM `user` WHERE username='packaged_test'", Long.class);
            Path blob = workspace.resolve("storage/2026/10/17/" + UUID.randomUUID() + ".png");
            Files.createDirectories(blob.getParent());
            byte[] original = new byte[4 * 1024 * 1024];
            new java.util.Random(17).nextBytes(original);
            Files.write(blob, original);
            String key = workspace.resolve("storage").relativize(blob).toString().replace('\\', '/');
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
            database.update("INSERT INTO file_asset (user_id,original_name,display_name,storage_key,content_type,extension,size_bytes,sha256,latest_version) "
                    + "VALUES (?,'crash-fixture.png','crash-fixture.png',?,'image/png','png',?,?,1)", owner, key, original.length, digest);
            long file = database.queryForObject("SELECT MAX(id) FROM file_asset", Long.class);
            database.update("INSERT INTO document_version (file_id,user_id,version_no,storage_key,content_type,extension,size_bytes,sha256) "
                    + "VALUES (?,?,1,?,'image/png','png',?,?)", file, owner, key, original.length, digest);
            database.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status) VALUES (?,?,1,'EXPORT','RUNNING')", owner, file);
            long interrupted = database.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            database.update("INSERT INTO task_step (task_id,step_no,step_name,status,start_time) VALUES (?,1,'completed','SUCCEEDED',NOW(3)),(?,2,'interrupted','RUNNING',NOW(3))",
                    interrupted, interrupted);
            database.update("INSERT INTO processing_task (user_id,task_type,status) VALUES (?,'IMAGE_GEN','PENDING')", owner);
            long pending = database.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            database.update("INSERT INTO ai_session (user_id,file_id) VALUES (?,?)", owner, file);
            long session = database.queryForObject("SELECT MAX(id) FROM ai_session", Long.class);
            database.update("INSERT INTO ai_message (session_id,role,content,run_status,plan_status) VALUES (?,'ASSISTANT','crash-fixture','RUNNING','NONE')", session);
            long reply = database.queryForObject("SELECT MAX(id) FROM ai_message", Long.class);
            assertTrue(child.isAlive());
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "操作系统强杀未完成");

            for (int restart = 1; restart <= 2; restart++) {
                Path restartedLog = workspace.resolve("restart-" + restart + ".log");
                child = builder.redirectOutput(restartedLog.toFile()).start();
                port = awaitPort(child, restartedLog);
                awaitRecovered(database, pending, reply);
                assertEquals("RESTARTED", database.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, interrupted));
                assertEquals("FAILED", database.queryForObject("SELECT status FROM task_step WHERE task_id=? AND step_no=2", String.class, interrupted));
                assertEquals("SUCCEEDED", database.queryForObject("SELECT status FROM task_step WHERE task_id=? AND step_no=1", String.class, interrupted));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM task_step WHERE task_id=? AND step_no=2 AND finish_time IS NOT NULL AND duration_ms>=0", Integer.class, interrupted));
                assertEquals("FAILED", database.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply));
                assertEquals("TASK_TYPE_DISABLED", database.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, pending));
                assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM document_version WHERE file_id=?", Integer.class, file));
                assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(blob))));
                var me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/user/me"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(401, me.statusCode());
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
        } finally {
            if (child != null && child.isAlive()) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "测试子进程未退出，不能清理数据库");
            }
            if (created) {
                if (!schema.matches("documenter_jar_it_[0-9a-f]{32}")) throw new IllegalStateException("拒绝清理非测试库");
                admin.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    private static void awaitRecovered(JdbcTemplate database, long pending, long reply) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if ("FAILED".equals(database.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, pending))
                    && "FAILED".equals(database.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply))) return;
            Thread.sleep(100);
        }
        fail("强杀重启后排队任务未收尾");
    }

    static int awaitPort(Process child, Path log) throws Exception {
        Pattern pattern = Pattern.compile("Tomcat started on port ([0-9]+)");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && child.isAlive()) {
            if (Files.exists(log)) {
                var match = pattern.matcher(Files.readString(log));
                if (match.find()) return Integer.parseInt(match.group(1));
            }
            Thread.sleep(100);
        }
        fail("JAR 未成功启动，日志位于隔离测试目录；不回显可能包含本地配置的完整日志");
        return -1;
    }
}
