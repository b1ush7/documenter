package com.documenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.documenter.component.TaskExecutionEngine;
import com.documenter.component.TaskHandlerRegistry;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskType;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.TaskHandler;
import com.documenter.service.TaskExecutionContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真 HTTP + 完整 Spring 后端 + MariaDB/Redis；仅新建的随机库及本轮随机用户键会被清理。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_APP_TEST_ENABLED", matches = "true")
class ApplicationFlowIntegrationTest {
    @TempDir Path workspace;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Object> configuration = new HashMap<>();
    private ConfigurableApplicationContext app;
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private String schema, base, token;
    private boolean created;

    @BeforeEach
    void prepareIsolatedApplication() {
        schema = "documenter_app_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(datasource("")).execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci");
        created = true;
        jdbc = new JdbcTemplate(datasource(schema));
        var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", redisPort());
        connections = new LettuceConnectionFactory(redisConfig, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ZERO).build());
        connections.afterPropertiesSet(); connections.start();
        redis = new StringRedisTemplate(connections);
        try (var connection = connections.getConnection()) { assertEquals("PONG", connection.ping()); }
        configuration.put("spring.datasource.url", datasource(schema).getUrl());
        configuration.put("spring.datasource.username", System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER"));
        configuration.put("spring.datasource.password", System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD"));
        configuration.put("spring.datasource.driver-class-name", "org.mariadb.jdbc.Driver");
        configuration.put("spring.data.redis.host", "127.0.0.1");
        configuration.put("spring.data.redis.port", redisPort());
        configuration.put("spring.data.redis.username", "default");
        configuration.put("spring.data.redis.password", "");
        configuration.put("spring.data.redis.database", 0);
        configuration.put("spring.flyway.baseline-on-migrate", false);
        configuration.put("spring.servlet.multipart.max-file-size", "1MB");
        configuration.put("spring.servlet.multipart.max-request-size", "2MB");
        configuration.put("res.jwt.secret-key", "local-integration-only-" + UUID.randomUUID());
        configuration.put("res.storage.local.root", workspace.resolve("storage").toString());
        configuration.put("res.storage.local.temp", workspace.resolve("temp").toString());
        configuration.put("res.storage.local.temp-cleanup-enabled", false);
        configuration.put("res.storage.recycle.enabled", false);
        configuration.put("res.storage.upload.max-size-bytes", 1048576);
        configuration.put("res.verification.mock-enabled", true);
        configuration.put("res.ocr.enabled", false);
        configuration.put("res.preview.office-backend", "none");
        configuration.put("res.ai.enabled", true);
        configuration.put("res.ai.provider", "stub");
        configuration.put("res.ai.daily-call-limit-per-user", 10);
        configuration.put("res.task.core-pool-size", 2);
        configuration.put("res.task.max-pool-size", 2);
        configuration.put("res.task.queue-capacity", 8);
        configuration.put("res.task.recover-on-startup", true);
        configuration.put("res.task.requeue-pending-on-startup", true);
        configuration.put("logging.level.com.documenter", "INFO");
        start();
        // 避免测试库自增 ID 与现有 Redis 业务用户 ID 重合。
        long firstUser = 1_000_000_000_000L + new java.security.SecureRandom().nextLong(1_000_000_000_000L);
        jdbc.execute("ALTER TABLE `user` AUTO_INCREMENT = " + firstUser);
    }

    @AfterEach
    void closeAndRemoveOnlyOwnedData() {
        if (app != null) { app.close(); app = null; }
        try {
            if (created && redis != null) {
                for (Long user : jdbc.queryForList("SELECT id FROM `user`", Long.class)) {
                    String index = "login:user_sessions:" + user;
                    Set<String> sessions = redis.opsForSet().members(index);
                    if (sessions != null) for (String session : sessions) redis.delete("login:session:" + session);
                    redis.delete(index);
                    removeOwnKeys("upload:quota:" + user + ":*");
                    removeOwnKeys("ai:daily-call:" + user + ":*");
                }
            }
        } finally {
            if (connections != null) connections.destroy();
            if (created) {
                if (!schema.matches("documenter_app_it_[0-9a-f]{32}")) throw new IllegalStateException("拒绝清理非测试库");
                new JdbcTemplate(datasource("")).execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCUMENTER_LOAD_TEST_ENABLED", matches = "true")
    void configuredFileBoundariesAndConcurrentTransfersKeepContentAndMetadataConsistent() throws Exception {
        app.close(); app = null;
        configuration.put("spring.servlet.multipart.max-file-size", "50MB");
        configuration.put("spring.servlet.multipart.max-request-size", "51MB");
        configuration.put("res.storage.upload.max-size-bytes", 50L * 1024 * 1024);
        configuration.put("res.storage.upload.max-image-pixels", 40_000_000L);
        configuration.put("res.storage.upload.max-pdf-pages", 200);
        start();
        String username = "load_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        api("POST", "/user/register", Map.of("username", username, "password", "IntegrationOnly123!"));
        token = api("POST", "/user/login", Map.of("account", username, "password", "IntegrationOnly123!")).path("accessToken").asText();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", java.time.Instant.now().toString());
        report.put("scope", "synthetic loopback HTTP, isolated MariaDB and storage, local Redis; no production SLA");

        Path large = workspace.resolve("large.png");
        writePng(large, 4000, 4000, true);
        assertTrue(Files.size(large) > 45L * 1024 * 1024);
        assertTrue(Files.size(large) < 50L * 1024 * 1024);
        String digest = sha256(large);
        long started = System.nanoTime();
        JsonNode uploaded = json.readTree(uploadFile(large, "image/png").body());
        assertEquals(0, uploaded.path("code").asInt(-1));
        long largeFile = uploaded.path("data").path("id").asLong();
        assertEquals(digest, uploaded.path("data").path("sha256").asText());
        report.put("largePngBytes", Files.size(large));
        report.put("largeUploadMs", elapsedMillis(started));

        Path pixels = workspace.resolve("pixel-boundary.png");
        writePng(pixels, 8000, 5000, false);
        assertEquals(0, json.readTree(uploadFile(pixels, "image/png").body()).path("code").asInt(-1));
        writePng(pixels, 8000, 5001, false);
        assertEquals(413, json.readTree(uploadFile(pixels, "image/png").body()).path("code").asInt(-1));
        report.put("acceptedImagePixels", 40_000_000);
        report.put("rejectedImagePixels", 40_008_000);

        Path document = workspace.resolve("pages.pdf");
        writePdf(document, 200);
        uploaded = json.readTree(uploadFile(document, "application/pdf").body());
        assertEquals(0, uploaded.path("code").asInt(-1));
        long pdfFile = uploaded.path("data").path("id").asLong();
        started = System.nanoTime();
        var content = api("GET", "/file/" + pdfFile + "/versions/1/pdf/content", null);
        assertEquals(200, content.path("pages").size());
        assertTrue(content.path("pages").get(199).path("text").asText().contains("Load page 200"));
        report.put("pdf200PagesReadMs", elapsedMillis(started));
        writePdf(document, 201);
        assertEquals(413, json.readTree(uploadFile(document, "application/pdf").body()).path("code").asInt(-1));

        Path oversized = workspace.resolve("oversized.png");
        try (var channel = java.nio.channels.FileChannel.open(oversized, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(50L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        var rejected = uploadFile(oversized, "image/png");
        assertEquals(413, rejected.statusCode());
        assertEquals(413, json.readTree(rejected.body()).path("code").asInt(-1));

        Path small = workspace.resolve("parallel.png");
        writePng(small, 1000, 1000, true);
        String smallDigest = sha256(small);
        List<Long> uploadTimes = new ArrayList<>();
        List<Long> downloadTimes = new ArrayList<>();
        Set<Long> files = new HashSet<>();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var ready = new CountDownLatch(8);
            var release = new CountDownLatch(1);
            List<java.util.concurrent.Future<TimedUpload>> uploads = new ArrayList<>();
            for (int i = 0; i < 8; i++) uploads.add(workers.submit(() -> {
                ready.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                long begin = System.nanoTime();
                var result = json.readTree(uploadFile(small, "image/png").body());
                assertEquals(0, result.path("code").asInt(-1));
                assertEquals(smallDigest, result.path("data").path("sha256").asText());
                return new TimedUpload(result.path("data").path("id").asLong(), elapsedMillis(begin));
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            release.countDown();
            for (var future : uploads) {
                var result = future.get(90, TimeUnit.SECONDS);
                assertTrue(files.add(result.fileId()));
                uploadTimes.add(result.millis());
            }
            List<java.util.concurrent.Future<Long>> downloads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Path target = workspace.resolve("download-" + i + ".png");
                downloads.add(workers.submit(() -> {
                    long begin = System.nanoTime();
                    var request = HttpRequest.newBuilder(URI.create(base + "/file/" + largeFile + "/versions/1/download"))
                            .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).GET().build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
                    assertEquals(200, response.statusCode());
                    assertEquals(Files.size(large), Files.size(target));
                    assertEquals(digest, sha256(target));
                    return elapsedMillis(begin);
                }));
            }
            for (var future : downloads) downloadTimes.add(future.get(90, TimeUnit.SECONDS));
        }
        assertEquals(8, files.size());
        assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM file_asset", Integer.class));
        assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM document_version", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE latest_version<>1", Integer.class));
        try (var blobs = Files.walk(workspace.resolve("storage"))) {
            assertEquals(11, blobs.filter(Files::isRegularFile).count(), "失败上传不能留下正式存储孤儿");
        }
        report.put("parallelUploadCount", 8);
        report.put("parallelUploadEachBytes", Files.size(small));
        report.put("parallelUploadMs", uploadTimes);
        report.put("parallelLargeDownloadCount", 4);
        report.put("parallelLargeDownloadMs", downloadTimes);
        report.put("result", "PASS");
        Files.writeString(Path.of("target/backend-load-report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private record TimedUpload(long fileId, long millis) {}

    private HttpResponse<byte[]> uploadFile(Path path, String type) throws Exception {
        String boundary = "load" + UUID.randomUUID();
        var prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + path.getFileName()
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofFile(path), HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        var request = HttpRequest.newBuilder(URI.create(base + "/file/upload")).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void writePng(Path target, int width, int height, boolean random) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, random ? java.awt.image.BufferedImage.TYPE_3BYTE_BGR : java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        try {
            if (random) new Random(17).nextBytes(((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData());
            assertTrue(javax.imageio.ImageIO.write(image, "png", target.toFile()));
        } finally { image.flush(); }
    }

    private static void writePdf(Path target, int pages) throws Exception {
        try (var document = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                var page = new PDPage(); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(font, 12); stream.newLineAtOffset(50, 740);
                    stream.showText("Load page " + i); stream.endText();
                }
            }
            document.save(target.toFile());
        }
    }

    private static String sha256(Path source) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new java.security.DigestInputStream(Files.newInputStream(source), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long elapsedMillis(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCUMENTER_OFFICE_TEST_ENABLED", matches = "true")
    void realOfficeHttpPreviewCompatibilityAndExportProduceReadablePdf() throws Exception {
        app.close(); app = null;
        configuration.put("res.preview.office-backend", "libreoffice");
        String executable = System.getenv("DOCUMENTER_OFFICE_EXECUTABLE");
        if (executable != null) configuration.put("res.preview.office-executable", executable);
        String python = System.getenv("DOCUMENTER_OFFICE_PYTHON");
        if (python != null) configuration.put("res.preview.office-python", python);
        start();
        String username = "office_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
        api("POST","/user/register",Map.of("username",username,"password","IntegrationOnly123!"));
        token=api("POST","/user/login",Map.of("account",username,"password","IntegrationOnly123!")).path("accessToken").asText();
        Path source=workspace.resolve("office.docx"); com.documenter.support.OfficeAcceptanceSamples.docx(source);
        long file=upload(Files.readAllBytes(source),"office.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
        String version="/file/"+file+"/versions/1";
        assertTrue(api("GET",version+"/preview",null).path("previewable").asBoolean());
        var preview=officeGet(version+"/pages/2/preview"); assertEquals(200,preview.statusCode());
        assertNotNull(javax.imageio.ImageIO.read(new ByteArrayInputStream(preview.body())));
        var report=json.readTree(officeRequest("POST",version+"/compatibility-check").body());
        assertEquals(0,report.path("code").asInt(-1));
        assertEquals("libreoffice",report.path("data").path("engine").asText());
        assertEquals(3,report.path("data").path("pageCount").asInt());
        assertTrue(report.path("data").path("compatible").asBoolean());
        var exported=officeGet(version+"/export/pdf"); assertEquals(200,exported.statusCode());
        try(var document=org.apache.pdfbox.Loader.loadPDF(exported.body())) {
            assertEquals(3,document.getNumberOfPages()); assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(document).contains("脚注说明"));
        }
        Path spreadsheet=workspace.resolve("office.xlsx"); com.documenter.support.OfficeAcceptanceSamples.xlsx(spreadsheet);
        long xlsx=upload(Files.readAllBytes(spreadsheet),"office.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").path("id").asLong();
        var xlsxPreview=officeGet("/file/"+xlsx+"/versions/1/pages/1/preview"); assertEquals(200,xlsxPreview.statusCode());
        assertNotNull(javax.imageio.ImageIO.read(new ByteArrayInputStream(xlsxPreview.body())));
        var pdf=officeGet("/file/"+xlsx+"/versions/1/export/pdf"); assertEquals(200,pdf.statusCode());
        try(var document=org.apache.pdfbox.Loader.loadPDF(pdf.body())) { assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(document).contains("121.50")); }
        assertEquals(1,api("GET",version.substring(0,version.lastIndexOf('/')) ,null).size());
    }

    private HttpResponse<byte[]> officeGet(String path) throws Exception {
        return officeRequest("GET",path);
    }

    private HttpResponse<byte[]> officeRequest(String method,String path) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(150))
                .header("Authorization","Bearer "+token).method(method,HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request,HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCUMENTER_CLUSTER_TEST_ENABLED", matches = "true")
    void expiredInstanceIsRecoveredAndItsLateTaskAndModelResultCannotWrite() throws Exception {
        app.close(); app = null;
        var enteredModel = new CountDownLatch(1);
        var enteredTask = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldWorkerFinished = new CountDownLatch(1);
        var lateFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var modelWorkers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var model = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(modelWorkers);
        model.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().close(); enteredModel.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
                byte[] response = ("{\"id\":\"lease-local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"local-lease\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"late result\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* 旧客户端可能已断开。 */ }
            finally { exchange.close(); }
        });
        model.start();
        ConfigurableApplicationContext first = null;
        var processing = pool();
        var coordination = pool();
        try (var requests = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            configuration.put("res.task.cluster.enabled", true);
            configuration.put("res.task.cluster.heartbeat", "200ms");
            configuration.put("res.task.cluster.lease-duration", "3s");
            configuration.put("res.task.cluster.requeue-pending", false);
            configuration.put("res.task.recover-on-startup", true);
            configuration.put("res.task.requeue-pending-on-startup", true);
            configuration.put("res.ai.provider", "openai");
            configuration.put("res.ai.base-url", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
            configuration.put("res.ai.api-key", "local-test-only");
            configuration.put("res.ai.model-name", "local-lease");
            configuration.put("res.ai.timeout", "20s");
            configuration.put("res.ai.max-retries", 0);
            configuration.put("res.ai.max-model-retries", 0);
            start(); first = app;
            var oldLeases = first.getBean(com.documenter.component.ExecutionLeaseService.class);
            String username = "lease_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            api("POST", "/user/register", Map.of("username", username, "password", "IntegrationOnly123!"));
            token = api("POST", "/user/login", Map.of("account", username, "password", "IntegrationOnly123!")).path("accessToken").asText();
            long owner = api("GET", "/user/me", null).path("id").asLong();
            long file = upload(pdf(), "lease.pdf", "application/pdf").path("id").asLong();
            long session = api("POST", "/ai/sessions", Map.of("title", "lease model")).path("id").asLong();
            String oldBase = base;
            var modelRequest = HttpRequest.newBuilder(URI.create(oldBase + "/ai/sessions/" + session + "/replies"))
                    .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("content", "本地租约验收", "idempotencyKey", UUID.randomUUID().toString())))).build();
            var ai = requests.submit(() -> http.send(modelRequest, HttpResponse.BodyHandlers.ofByteArray()));
            assertTrue(enteredModel.await(5, TimeUnit.SECONDS));
            long reply = jdbc.queryForObject("SELECT MAX(id) FROM ai_message WHERE run_status='RUNNING'", Long.class);
            jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status,max_retry,timeout_seconds) VALUES (?,?,1,'CONVERT','PENDING',0,20)", owner, file);
            long task = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            jdbc.update("INSERT INTO processing_task (user_id,task_type,status) VALUES (?,'EXPORT','RUNNING')", owner);
            long legacy = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            var converter = first.getBean(com.documenter.service.PdfToDocxService.class);
            var blocking = new TaskHandler() {
                @Override public String name() { return "Unresponsive old execution fixture"; }
                @Override public boolean supports(TaskType type) { return type == TaskType.CONVERT; }
                @Override public void handle(ProcessingTask item, TaskExecutionContext context) {
                    context.startStep("等待旧执行者恢复", "暂停心跳后仍尝试提交"); enteredTask.countDown();
                    try {
                        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                        while (release.getCount() > 0 && System.nanoTime() < deadline) {
                            try { release.await(100, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { /* 模拟不合作的暂停/恢复执行者，后续必须由数据库隔离。 */ }
                        }
                        Thread.interrupted();
                        var request = new com.documenter.dto.ConvertPdfToDocxDTO();
                        request.setMode(com.documenter.enums.PdfConversionMode.EDITABLE);
                        converter.convert(owner, file, 1, request, item.getId(), 1);
                    } catch (Throwable failure) { lateFailure.set(failure); }
                    finally { oldWorkerFinished.countDown(); }
                }
            };
            var engine = new TaskExecutionEngine(first.getBean(ProcessingTaskMapper.class), first.getBean(TaskStepMapper.class),
                    new TaskHandlerRegistry(List.of(blocking)), first.getBean(TaskExecutorProperties.class), coordination, processing);
            engine.setExecutionLeases(oldLeases);
            var execution = requests.submit(() -> engine.execute(task));
            assertTrue(enteredTask.await(5, TimeUnit.SECONDS));
            start();
            var survivor = app.getBean(com.documenter.component.ExecutionLeaseService.class);
            assertNotEquals(oldLeases.ownerId(), survivor.ownerId());
            Thread.sleep(3200); // 超过初始 3 秒有效期，证明实际在续期，而非仅尚未过期。
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, task));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, legacy));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task WHERE id=? AND lease_until>NOW(3)", Integer.class, task));

            // 当前实例不能冒用仍有效的另一个实例的任务标识写版本。
            var asset = jdbc.queryForMap("SELECT storage_key,size_bytes,sha256 FROM file_asset WHERE id=?", file);
            var candidate = new com.documenter.service.VersionWriter.VersionContent((String) asset.get("storage_key"),
                    ((Number) asset.get("size_bytes")).longValue(), (String) asset.get("sha256"), "application/pdf", "pdf");
            var newWriter = app.getBean(com.documenter.service.VersionWriter.class);
            assertEquals(409, assertThrows(com.documenter.exception.BusinessException.class, () -> newWriter.appendVersion(
                    file, owner, candidate, com.documenter.enums.ChangeType.CONVERT, null, "wrong owner", 1, task, 1)).getCode());

            jdbc.update("INSERT INTO processing_task (user_id,task_type,status) VALUES (?,'EXPORT','PENDING')", owner);
            long live = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            assertEquals(1, survivor.claimTask(live));
            try (var active = survivor.trackTask(live)) {
                oldLeases.close(); // 暂停 A 的心跳，B 的恢复器和心跳继续运行。
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (System.nanoTime() < deadline) {
                    if ("FAILED".equals(jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, task))
                            && "FAILED".equals(jdbc.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply))) break;
                    Thread.sleep(100);
                }
                assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, task));
                assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM task_step WHERE task_id=?", String.class, task));
                assertEquals("FAILED", jdbc.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply));
                assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, live));
                oldLeases.heartbeat(); // 失效租约即使旧线程恢复也不能续回。
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE id=? AND lease_until>NOW(3)", Integer.class, reply));
                release.countDown();
                assertTrue(oldWorkerFinished.await(8, TimeUnit.SECONDS));
                assertInstanceOf(com.documenter.exception.BusinessException.class, lateFailure.get());
                assertEquals(409, ((com.documenter.exception.BusinessException) lateFailure.get()).getCode());
                execution.get(8, TimeUnit.SECONDS);
                assertEquals(409, json.readTree(ai.get(8, TimeUnit.SECONDS).body()).path("code").asInt());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE file_id=?", Integer.class, file));
                assertNull(jdbc.queryForObject("SELECT result_json FROM ai_message WHERE id=?", String.class, reply));
                assertEquals("NONE", jdbc.queryForObject("SELECT plan_status FROM ai_message WHERE id=?", String.class, reply));
                try (var blobs = Files.walk(workspace.resolve("storage"))) { assertEquals(1, blobs.filter(Files::isRegularFile).count()); }
            }

            // 即使进入追加时租约有效，外层事务提交前失效也必须整体回滚。
            jdbc.update("INSERT INTO processing_task (user_id,file_id,task_type,status) VALUES (?,?,'CONVERT','PENDING')", owner, file);
            long commitTask = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            assertEquals(1, survivor.claimTask(commitTask));
            var transactions = new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            var transactionalJdbc = new JdbcTemplate(app.getBean(javax.sql.DataSource.class));
            assertThrows(com.documenter.exception.BusinessException.class, () -> transactions.executeWithoutResult(status -> {
                newWriter.appendVersion(file, owner, candidate, com.documenter.enums.ChangeType.CONVERT, null, "expire before commit", 1, commitTask, 1);
                transactionalJdbc.update("UPDATE processing_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", commitTask);
            }));
            assertEquals(1, jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=?", Integer.class, file));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE file_id=?", Integer.class, file));
            // 过期记录保持终态；健康实例仍可接收新回复，并在背压允许时排空持久化队列。
            var normal = api("POST", "/ai/sessions/" + session + "/replies", Map.of("content", "正常的后续回复", "idempotencyKey", UUID.randomUUID().toString()));
            assertEquals("COMPLETED", normal.path("runStatus").asText());
            app.getBean(TaskExecutorProperties.class).getCluster().setRequeuePending(true);
            jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status,max_retry) VALUES (?,?,1,'CONVERT','PENDING',0)", owner, file);
            long queued = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            var completed = terminal(queued);
            assertEquals("SUCCEEDED", completed.path("status").asText());
            assertEquals(2, completed.path("outputVersion").asInt());
            assertEquals(survivor.ownerId(), jdbc.queryForObject("SELECT execution_owner FROM processing_task WHERE id=?", String.class, queued));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE task_id=?", Integer.class, queued));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, legacy));
            // 两个恢复器同时以小批量回收过期记录，跳过对方持锁记录，保留有效租约。
            survivor.close();
            app.getBean(TaskExecutorProperties.class).getCluster().setBatchSize(3);
            first.getBean(TaskExecutorProperties.class).getCluster().setBatchSize(3);
            List<Long> expiredIds = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                jdbc.update("INSERT INTO processing_task (user_id,task_type,status,execution_owner,lease_until) VALUES (?,'EXPORT','RUNNING',?,TIMESTAMPADD(SECOND,-1,NOW(3)))", owner, oldLeases.ownerId());
                long expired = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
                expiredIds.add(expired);
                jdbc.update("INSERT INTO task_step (task_id,step_no,step_name,status,start_time) VALUES (?,1,'expired-batch','RUNNING',NOW(3))", expired);
            }
            jdbc.update("INSERT INTO processing_task (user_id,task_type,status,execution_owner,lease_until) VALUES (?,'EXPORT','RUNNING',?,TIMESTAMPADD(MINUTE,1,NOW(3)))", owner, survivor.ownerId());
            long protectedTask = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            var recoveringA = requests.submit(() -> { for (int i = 0; i < 10; i++) oldLeases.recoverExpired(); });
            var recoveringB = requests.submit(() -> { for (int i = 0; i < 10; i++) survivor.recoverExpired(); });
            recoveringA.get(10, TimeUnit.SECONDS); recoveringB.get(10, TimeUnit.SECONDS);
            for (long expired : expiredIds) {
                assertEquals("LEASE_EXPIRED", jdbc.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, expired));
                assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM task_step WHERE task_id=?", String.class, expired));
            }
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, protectedTask));
            // 心跳跳过持锁行；等到旧事务释放后也不能把实际已经过期的租约续回。
            jdbc.update("INSERT INTO processing_task (user_id,task_type,status) VALUES (?,'EXPORT','PENDING')", owner);
            long lockedTask = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            assertEquals(1, survivor.claimTask(lockedTask));
            try (var active = survivor.trackTask(lockedTask)) {
                transactions.executeWithoutResult(status -> {
                    transactionalJdbc.queryForObject("SELECT id FROM processing_task WHERE id=? FOR UPDATE", Long.class, lockedTask);
                    var skipped = requests.submit(survivor::heartbeat);
                    try {
                        skipped.get(1, TimeUnit.SECONDS);
                        Thread.sleep(3200);
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                });
                survivor.heartbeat();
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task WHERE id=? AND lease_until>NOW(3)", Integer.class, lockedTask));
            }
            // 幂等快路径不更新 MyBatis 数据；提交前校验也必须绕过会话缓存读取数据库时间。
            jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status) VALUES (?,?,2,'CONVERT','PENDING')", owner, file);
            long replayTask = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
            assertEquals(1, survivor.claimTask(replayTask));
            newWriter.appendVersion(file, owner, candidate, com.documenter.enums.ChangeType.CONVERT, null, "replay fixture", 2, replayTask, 1);
            assertThrows(com.documenter.exception.BusinessException.class, () -> transactions.executeWithoutResult(status -> {
                newWriter.appendVersion(file, owner, candidate, com.documenter.enums.ChangeType.CONVERT, null, "idempotent replay", 2, replayTask, 1);
                transactionalJdbc.update("UPDATE processing_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", replayTask);
            }));
            assertEquals(3, jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=?", Integer.class, file));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE file_id=?", Integer.class, file));
        } finally {
            release.countDown();
            coordination.shutdown(); processing.shutdown();
            if (first != null) first.close();
            model.stop(0); modelWorkers.shutdownNow(); modelWorkers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCUMENTER_MULTI_INSTANCE_TEST_ENABLED", matches = "true")
    void twoApplicationInstancesShareSessionsAndClaimTasksOnceWhenStartupRecoveryIsDisabled() throws Exception {
        app.close(); app = null;
        configuration.put("res.task.recover-on-startup", false);
        configuration.put("res.task.requeue-pending-on-startup", false);
        start();
        String username = "dual_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        api("POST", "/user/register", Map.of("username", username, "password", "IntegrationOnly123!"));
        token = api("POST", "/user/login", Map.of("account", username, "password", "IntegrationOnly123!")).path("accessToken").asText();
        long owner = api("GET", "/user/me", null).path("id").asLong();
        jdbc.update("INSERT INTO processing_task (user_id,task_type,status) VALUES (?,'EXPORT','RUNNING')", owner);
        long running = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
        jdbc.update("INSERT INTO task_step (task_id,step_no,step_name,status,start_time) VALUES (?,1,'live-other-instance','RUNNING',NOW(3))", running);
        jdbc.update("INSERT INTO ai_session (user_id) VALUES (?)", owner);
        long session = jdbc.queryForObject("SELECT MAX(id) FROM ai_session", Long.class);
        jdbc.update("INSERT INTO ai_message (session_id,role,run_status,plan_status) VALUES (?,'ASSISTANT','RUNNING','NONE')", session);
        long reply = jdbc.queryForObject("SELECT MAX(id) FROM ai_message", Long.class);
        var first = app;
        String firstBase = base;
        try {
            start();
            assertNotSame(first.getBean(ProcessingTaskMapper.class), app.getBean(ProcessingTaskMapper.class));
            assertEquals(owner, api("GET", "/user/me", null).path("id").asLong());
            var firstMe = http.send(HttpRequest.newBuilder(URI.create(firstBase + "/user/me"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(owner, json.readTree(firstMe.body()).path("data").path("id").asLong());
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, running));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM task_step WHERE task_id=?", String.class, running));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, reply));

            var engines = List.of(first.getBean(TaskExecutionEngine.class), app.getBean(TaskExecutionEngine.class));
            List<Long> taskIds = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                long file = upload(pdf(), "dual.pdf", "application/pdf").path("id").asLong();
                jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status,max_retry) VALUES (?,?,1,'CONVERT','PENDING',0)", owner, file);
                taskIds.add(jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class));
            }
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var ready = new CountDownLatch(8);
                var release = new CountDownLatch(1);
                List<java.util.concurrent.Future<?>> executions = new ArrayList<>();
                for (long task : taskIds) for (var engine : engines) executions.add(workers.submit(() -> {
                    ready.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("实例执行屏障超时"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    engine.execute(task);
                }));
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                release.countDown();
                for (var future : executions) future.get(30, TimeUnit.SECONDS);
            }
            for (long task : taskIds) {
                var status = api("GET", "/task/" + task, null).path("task");
                assertEquals("SUCCEEDED", status.path("status").asText());
                assertEquals(2, status.path("outputVersion").asInt());
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE task_id=?", Integer.class, task));
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM task_step WHERE task_id=? AND status='SUCCEEDED'", Integer.class, task));
                try (var document = new XWPFDocument(new ByteArrayInputStream(download("/file/" + status.path("fileId").asLong() + "/versions/2/download")))) {
                    assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("Restart recovery sample")));
                }
            }
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, running));
        } finally { first.close(); }
    }

    @Test
    void realHttpWorkflowAndRestartRecovery() throws Exception {
        String username = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        api("POST", "/user/register", Map.of("username", username, "password", "IntegrationOnly123!"));
        var login = api("POST", "/user/login", Map.of("account", username, "password", "IntegrationOnly123!"));
        token = login.path("accessToken").asText();
        String refresh = login.path("refreshToken").asText();
        long user = api("GET", "/user/me", null).path("id").asLong();
        assertTrue(user > 1_000_000_000_000L);
        byte[] original = docx();
        long file = upload(original, "sample.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
        assertTrue(file > 0);
        String source = "/file/" + file + "/versions/1";
        var structure = api("GET", source + "/structure", null);
        String target = null;
        for (JsonNode block : structure.path("blocks")) if ("原始内容".equals(block.path("text").asText())) target = block.path("blockId").asText();
        assertNotNull(target);
        long session = api("POST", "/ai/sessions", Map.of("title", "完整流程", "fileId", file)).path("id").asLong();
        String instruction = "[stub-tool] edit_paragraph " + json.writeValueAsString(Map.of(
                "operation", "REPLACE_TEXT", "target", target, "content", "确认后的内容", "reason", "集成验收"));
        var reply = api("POST", "/ai/sessions/" + session + "/replies", Map.of(
                "content", instruction, "versionNo", 1, "idempotencyKey", UUID.randomUUID().toString()));
        assertEquals("COMPLETED", reply.path("runStatus").asText());
        assertEquals("PENDING", reply.path("planStatus").asText());
        assertFalse(reply.path("changes").isEmpty());
        String confirm = "/ai/sessions/" + session + "/replies/" + reply.path("message").path("id").asLong() + "/confirm";
        assertEquals(2, api("POST", confirm, null).path("outputVersion").asInt());
        assertEquals(2, api("POST", confirm, null).path("outputVersion").asInt());
        assertEquals(2, api("GET", "/file/" + file + "/versions", null).size());
        byte[] edited = download("/file/" + file + "/versions/2/download");
        try (var document = new XWPFDocument(new ByteArrayInputStream(edited))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().equals("确认后的内容")));
        }
        assertArrayEquals(original, download(source + "/download"));
        assertEquals(3, api("POST", source + "/restore?expectVersion=2", null).path("versionNo").asInt());
        assertArrayEquals(original, download("/file/" + file + "/versions/3/download"));
        assertFalse(api("GET", "/file/" + file + "/versions/3/preview", null).path("previewable").asBoolean());
        assertEquals(503, jsonRequest("GET", "/file/" + file + "/versions/3/pages/1/preview", null).path("code").asInt());
        var failed = api("POST", "/task", Map.of("taskType", "EXPORT", "fileId", file, "inputVersion", 3,
                "idempotencyKey", UUID.randomUUID().toString()));
        var failure = terminal(failed.path("id").asLong());
        assertEquals("FAILED", failure.path("status").asText());
        assertTrue(failure.path("outputVersion").isNull());
        assertEquals(3, api("GET", "/file/" + file + "/versions", null).size());
        var retry = api("POST", "/task/" + failed.path("id").asLong() + "/retry", null);
        assertNotEquals(failed.path("id").asLong(), retry.path("id").asLong());
        assertEquals("FAILED", terminal(retry.path("id").asLong()).path("status").asText());
        assertEquals(3, api("GET", "/file/" + file + "/versions", null).size());

        var pdf = upload(pdf(), "native.pdf", "application/pdf");
        long pdfFile = pdf.path("id").asLong();
        byte[] page = download("/file/" + pdfFile + "/versions/1/pdf/pages/1/preview");
        assertEquals(0x89, page[0] & 255);
        assertEquals('P', page[1]);
        assertEquals(413, uploadResponse(new byte[2 * 1024 * 1024], "large.png", "image/png").path("code").asInt());
        assertEquals(400, json.readTree(send("POST", "/file/upload", "invalid".getBytes(StandardCharsets.UTF_8),
                "multipart/form-data").body()).path("code").asInt());
        assertEquals(400, json.readTree(send("POST", "/file/upload", "--empty--\r\n".getBytes(StandardCharsets.UTF_8),
                "multipart/form-data; boundary=empty").body()).path("code").asInt());

        long freeSession = api("POST", "/ai/sessions", Map.of("title", "SSE")).path("id").asLong();
        var stream = send("POST", "/ai/sessions/" + freeSession + "/replies/stream", json.writeValueAsBytes(Map.of(
                "content", "你好", "idempotencyKey", UUID.randomUUID().toString())), "application/json");
        assertTrue(new String(stream.body(), StandardCharsets.UTF_8).contains("event:completed"));
        String streamRequestId = stream.headers().firstValue("X-Request-ID").orElseThrow();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='AI_REPLY_CREATE' "
                + "AND user_id=? AND target_id=? AND request_id=?", Integer.class, user, Long.toString(freeSession), streamRequestId));
        String beforeRefresh = token;
        var rotated = api("POST", "/user/refresh", Map.of("refreshToken", refresh));
        token = rotated.path("accessToken").asText();
        assertNotEquals(beforeRefresh, token);
        assertEquals(401, jsonRequest("POST", "/user/refresh", Map.of("refreshToken", refresh)).path("code").asInt());

        String other = username + "b";
        api("POST", "/user/register", Map.of("username", other, "password", "IntegrationOnly123!"));
        String ownerToken = token;
        token = api("POST", "/user/login", Map.of("account", other, "password", "IntegrationOnly123!")).path("accessToken").asText();
        assertEquals(404, jsonRequest("GET", source + "/structure", null).path("code").asInt());
        assertEquals(403, send("POST", "/admin/storage/recycle", null, null).statusCode());
        token = ownerToken;

        // 关闭进程后构造持久化中断状态，再启动同一配置验证实际 ApplicationRunner。
        app.close(); app = null;
        jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status) VALUES (?,?,1,'CONVERT','RUNNING')", user, pdfFile);
        long interrupted = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
        jdbc.update("INSERT INTO processing_task (user_id,file_id,input_version,task_type,status) VALUES (?,?,1,'CONVERT','PENDING')", user, pdfFile);
        long pending = jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
        jdbc.update("INSERT INTO ai_message (session_id,role,content,run_status,plan_status) VALUES (?,'ASSISTANT','interrupted','RUNNING','NONE')", session);
        long interruptedReply = jdbc.queryForObject("SELECT MAX(id) FROM ai_message", Long.class);
        start();
        assertEquals(user, api("GET", "/user/me", null).path("id").asLong());
        assertEquals("RESTARTED", api("GET", "/task/" + interrupted, null).path("task").path("errorCode").asText());
        var converted = terminal(pending);
        assertEquals("SUCCEEDED", converted.path("status").asText());
        assertEquals(2, converted.path("outputVersion").asInt());
        assertEquals("FAILED", jdbc.queryForObject("SELECT run_status FROM ai_message WHERE id=?", String.class, interruptedReply));
        try (var document = new XWPFDocument(new ByteArrayInputStream(download("/file/" + pdfFile + "/versions/2/download")))) {
            assertTrue(document.getParagraphs().stream().anyMatch(p -> p.getText().contains("Restart recovery sample")));
        }
        assertArrayEquals(original, download("/file/" + file + "/versions/3/download"));
        api("POST", "/user/logout", null);
        assertEquals(401, send("GET", "/user/me", null, null).statusCode());
    }

    @Test
    void taskEnginePersistsTimeoutCancellationAndSaturationAgainstRealDatabase() throws Exception {
        var workers = pool();
        var coordinator = pool();
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var handler = new TaskHandler() {
            @Override public String name() { return "Controlled blocking integration fixture"; }
            @Override public boolean supports(TaskType type) { return type == TaskType.EXPORT; }
            @Override public void handle(ProcessingTask task, TaskExecutionContext context) {
                context.startStep("等待测试中断", "确定性阻塞，不调用外部服务");
                try { release.await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            }
        };
        var properties = new TaskExecutorProperties();
        properties.setDefaultMaxRetry(0);
        var engine = new TaskExecutionEngine(app.getBean(ProcessingTaskMapper.class), app.getBean(TaskStepMapper.class),
                new TaskHandlerRegistry(List.of(handler)), properties, coordinator, workers);
        try {
            long timeout = seedTask(false, 1);
            engine.execute(timeout);
            assertEquals("TIMEOUT", jdbc.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, timeout));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "超时必须中断处理线程");
            long idleDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (workers.getActiveCount() != 0 && System.nanoTime() < idleDeadline) Thread.sleep(10);
            assertEquals(0, workers.getActiveCount());
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM task_step WHERE task_id=?", String.class, timeout));
            workers.execute(() -> awaitRelease(release));
            long saturated = seedTask(false, 10);
            engine.execute(saturated);
            assertEquals("QUEUE_FULL", jdbc.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, saturated));
            long cancelled = seedTask(true, 10);
            engine.execute(cancelled);
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM processing_task WHERE id=?", String.class, cancelled));
            coordinator.execute(() -> awaitRelease(release));
            long rejected = seedTask(false, 10);
            engine.submit(rejected);
            assertEquals("QUEUE_FULL", jdbc.queryForObject("SELECT error_code FROM processing_task WHERE id=?", String.class, rejected));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM processing_task WHERE output_version IS NOT NULL", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM document_version", Integer.class));
        } finally {
            release.countDown();
            workers.shutdown(); coordinator.shutdown();
            assertTrue(workers.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(coordinator.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void localModelFailureAndTimeoutPreserveDocumentAndReturnFailedReply() throws Exception {
        String username = "fault_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        api("POST", "/user/register", Map.of("username", username, "password", "IntegrationOnly123!"));
        token = api("POST", "/user/login", Map.of("account", username, "password", "IntegrationOnly123!")).path("accessToken").asText();
        long file = upload(docx(), "fault.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
        var slow = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var release = new CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var model = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(executor);
        model.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().close();
            try {
                if (slow.get()) release.await(5, TimeUnit.SECONDS);
                byte[] error = "{\"error\":{\"message\":\"controlled local outage\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, error.length);
                exchange.getResponseBody().write(error);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* 超时客户端已断开。 */ }
            finally { exchange.close(); }
        });
        model.start();
        try {
            app.close(); app = null;
            configuration.put("res.ai.provider", "openai");
            configuration.put("res.ai.base-url", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
            configuration.put("res.ai.api-key", "local-test-placeholder");
            configuration.put("res.ai.model-name", "local-outage-fixture");
            configuration.put("res.ai.max-retries", 0);
            configuration.put("res.ai.max-model-retries", 0);
            configuration.put("res.ai.timeout", "500ms");
            start();
            long session = api("POST", "/ai/sessions", Map.of("title", "受控故障", "fileId", file)).path("id").asLong();
            for (boolean timeout : List.of(false, true)) {
                slow.set(timeout);
                var result = api("POST", "/ai/sessions/" + session + "/replies", Map.of("content", "修改第一段", "versionNo", 1,
                        "idempotencyKey", UUID.randomUUID().toString()));
                assertEquals("FAILED", result.path("runStatus").asText());
                assertEquals("MODEL_UNAVAILABLE", result.path("result").path("termination").asText());
                assertEquals("NONE", result.path("planStatus").asText());
                assertEquals(1, api("GET", "/file/" + file + "/versions", null).size());
            }
            assertEquals(2, calls.get());
        } finally {
            release.countDown(); model.stop(0); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private long seedTask(boolean cancelled, int timeout) {
        jdbc.update("INSERT INTO processing_task (user_id,task_type,status,cancel_requested,timeout_seconds,max_retry) "
                + "VALUES (1,'EXPORT','PENDING',?,?,0)", cancelled, timeout);
        return jdbc.queryForObject("SELECT MAX(id) FROM processing_task", Long.class);
    }

    private static ThreadPoolTaskExecutor pool() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1); pool.setMaxPoolSize(1); pool.setQueueCapacity(0); pool.initialize();
        return pool;
    }

    private static void awaitRelease(CountDownLatch release) {
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Test
    void templateHistoryFailureRollsBackMetadataAndStoredFiles() throws Exception {
        var service=app.getBean(com.documenter.service.DocTemplateService.class);
        var dto=new com.documenter.dto.SaveDocTemplateDTO(); dto.setCode("tx_template"); dto.setName("transaction test");
        byte[] bytes=transactionDocx();
        var file=new org.springframework.mock.web.MockMultipartFile("file","test.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes);
        jdbc.execute("CREATE TRIGGER reject_template_history BEFORE INSERT ON doc_template_version FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected history failure'");
        assertThrows(RuntimeException.class,()->service.create(dto,file));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM doc_template",Integer.class));
        assertEquals(0,storedFileCount());
        jdbc.execute("DROP TRIGGER reject_template_history");
        var template=service.create(dto,file);
        String key=jdbc.queryForObject("SELECT storage_key FROM doc_template WHERE id=?",String.class,template.getId());
        jdbc.execute("CREATE TRIGGER reject_template_history BEFORE INSERT ON doc_template_version FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected history failure'");
        assertThrows(RuntimeException.class,()->service.replaceContent(template.getId(),file));
        assertEquals(key,jdbc.queryForObject("SELECT storage_key FROM doc_template WHERE id=?",String.class,template.getId()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM doc_template_version",Integer.class));
        assertEquals(1,storedFileCount());
        jdbc.execute("DROP TRIGGER reject_template_history");
        assertThrows(IllegalStateException.class,()->transactions().executeWithoutResult(status->{
            service.replaceContent(template.getId(),file); throw new IllegalStateException("outer rollback");
        }));
        assertEquals(1,jdbc.queryForObject("SELECT version_no FROM doc_template WHERE id=?",Integer.class,template.getId()));
        assertEquals(1,storedFileCount());
    }

    @Test
    void defaultModelSwitchFailureRestoresPreviousDefaultAndConcurrentSwitchesStayUnique() throws Exception {
        var models=app.getBean(com.documenter.service.ModelConfigService.class);
        var first=modelDto("first",true); var second=modelDto("second",false);
        long original=models.create(first).getId(), replacement=models.create(second).getId();
        jdbc.execute("CREATE TRIGGER reject_new_default BEFORE UPDATE ON model_config FOR EACH ROW BEGIN IF NEW.id="+replacement+
                " AND NEW.is_default=1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected default failure'; END IF; END");
        second.setIsDefault(true);
        assertThrows(RuntimeException.class,()->models.update(replacement,second));
        assertEquals(original,jdbc.queryForObject("SELECT id FROM model_config WHERE is_default=1 AND scope='TEXT'",Long.class));
        jdbc.execute("DROP TRIGGER reject_new_default");
        var start=new CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++) { final boolean useFirst=i%2==0;
                results.add(workers.submit(()->{ awaitRelease(start);
                    try { models.update(useFirst?original:replacement,modelDto(useFirst?"first":"second",true)); }
                    catch(org.springframework.dao.DataAccessException conflict) {
                        assertEquals(409,app.getBean(com.documenter.exception.GlobalExceptionHandler.class).handleException(conflict).getBody().getCode());
                    }
                    catch(com.documenter.exception.BusinessException conflict) { assertEquals(409,conflict.getCode()); }
                }));
            }
            start.countDown(); for(var result:results) result.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_config WHERE is_default=1 AND scope='TEXT'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM model_config",Integer.class));
    }

    @Test
    void uploadAndGenerationCleanStoredFilesWhenCommitFailsAfterServiceReturns() throws Exception {
        var files=app.getBean(com.documenter.service.FileService.class);
        long owner=transactionOwner();
        byte[] bytes=transactionDocx();
        for(boolean generated:List.of(false,true)) {
            assertThrows(IllegalStateException.class,()->transactions().executeWithoutResult(status->{
                if(generated) files.createGeneratedDocx(owner,"generated.docx",bytes,"transaction test");
                else files.upload(owner,new org.springframework.mock.web.MockMultipartFile("file","upload.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",bytes));
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("injected commit failure"); }
                        });
            }));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_asset",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
            assertEquals(0,storedFileCount(),"提交前失败必须同时回收上传/生成文件");
        }
    }

    @Test
    void localOcrCorrectionInsertAndUpdateRollBackWithoutChangingDocument() throws Exception {
        var files=app.getBean(com.documenter.service.FileService.class);
        long owner=transactionOwner();
        long file=files.createGeneratedDocx(owner,"ocr.docx",transactionDocx(),"test").getId();
        var corrections=app.getBean(com.documenter.service.OcrCorrectionService.class);
        var request=new com.documenter.dto.SaveOcrCorrectionsDTO(); request.setFingerprint("a".repeat(64));
        var page=new com.documenter.dto.SaveOcrCorrectionsDTO.PageCorrection(); page.setPageNo(1); page.setText("original correction");
        request.setPages(List.of(page));
        assertThrows(IllegalStateException.class,()->transactions().executeWithoutResult(status->{
            corrections.save(owner,file,1,request); throw new IllegalStateException("rollback insert");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ocr_correction",Integer.class));
        corrections.save(owner,file,1,request); page.setText("must rollback");
        assertThrows(IllegalStateException.class,()->transactions().executeWithoutResult(status->{
            corrections.save(owner,file,1,request); throw new IllegalStateException("rollback update");
        }));
        assertEquals("original correction",corrections.get(owner,file,1,request.getFingerprint()).pages().get(0).text());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
    }

    @Test
    void accountMutationRollbackPreservesPasswordRoleAndStatusAndRevokesOldSessions() throws Exception {
        String username="account_tx";
        api("POST","/user/register",Map.of("username",username,"password","IntegrationOnly123!"));
        long owner=jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",Long.class,username);
        var admin=app.getBean(com.documenter.service.AdminUserService.class);
        var users=app.getBean(com.documenter.service.UserService.class);
        for(String action:List.of("status","role","password")) {
            token=api("POST","/user/login",Map.of("account",username,"password","IntegrationOnly123!")).path("accessToken").asText();
            assertThrows(IllegalStateException.class,()->transactions().executeWithoutResult(status->{
                switch(action) {
                    case "status" -> admin.updateStatus(owner+1,owner,"DISABLED");
                    case "role" -> admin.updateRole(owner+1,owner,"ADMIN");
                    default -> users.changePassword(owner,"IntegrationOnly123!","ReplacementOnly123!");
                }
                throw new IllegalStateException("rollback account mutation");
            }));
            assertEquals("ENABLED",jdbc.queryForObject("SELECT status FROM `user` WHERE id=?",String.class,owner));
            assertEquals("USER",jdbc.queryForObject("SELECT role FROM `user` WHERE id=?",String.class,owner));
            assertEquals(401,send("GET","/user/me",null,null).statusCode(),"回滚不能恢复已撤销的旧凭据");
        }
        token=api("POST","/user/login",Map.of("account",username,"password","IntegrationOnly123!")).path("accessToken").asText();
        assertEquals(owner,api("GET","/user/me",null).path("id").asLong());
        assertNotEquals(0,jsonRequest("POST","/user/login",Map.of("account",username,"password","ReplacementOnly123!")).path("code").asInt());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action IN ('ADMIN_ROLE_CHANGE','ADMIN_USER_DISABLE','USER_PASSWORD_CHANGED') AND result='SUCCESS'",Integer.class));
    }

    @Test
    void aiConfirmationDatabaseFailureRollsBackVersionAndConcurrentReplayCreatesOnlyOne() throws Exception {
        api("POST","/user/register",Map.of("username","confirm_tx","password","IntegrationOnly123!"));
        token=api("POST","/user/login",Map.of("account","confirm_tx","password","IntegrationOnly123!")).path("accessToken").asText();
        long owner=api("GET","/user/me",null).path("id").asLong();
        byte[] original=docx();
        long file=upload(original,"confirm.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
        String target=null;
        for(var block:api("GET","/file/"+file+"/versions/1/structure",null).path("blocks"))
            if("原始内容".equals(block.path("text").asText())) target=block.path("blockId").asText();
        assertNotNull(target);
        long session=api("POST","/ai/sessions",Map.of("title","confirm transaction","fileId",file)).path("id").asLong();
        String instruction="[stub-tool] edit_paragraph "+json.writeValueAsString(Map.of("operation","REPLACE_TEXT","target",target,"content","committed once","reason","transaction check"));
        long reply=api("POST","/ai/sessions/"+session+"/replies",Map.of("content",instruction,"versionNo",1,"idempotencyKey",UUID.randomUUID().toString())).path("message").path("id").asLong();
        var replies=app.getBean(com.documenter.service.AiReplyService.class);
        jdbc.execute("CREATE TRIGGER reject_confirmation BEFORE UPDATE ON ai_message FOR EACH ROW BEGIN IF NEW.plan_status='CONFIRMED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected confirmation failure'; END IF; END");
        assertThrows(RuntimeException.class,()->replies.confirm(owner,session,reply));
        assertEquals("PENDING",jdbc.queryForObject("SELECT plan_status FROM ai_message WHERE id=?",String.class,reply));
        assertEquals(1,jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=?",Integer.class,file));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
        assertEquals(1,storedFileCount());
        assertArrayEquals(original,download("/file/"+file+"/versions/1/download"));
        jdbc.execute("DROP TRIGGER reject_confirmation");
        var start=new CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<4;i++) results.add(workers.submit(()->{awaitRelease(start);return replies.confirm(owner,session,reply).outputVersion();}));
            start.countDown(); for(var result:results) assertEquals(2,result.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT plan_status FROM ai_message WHERE id=?",String.class,reply));
        assertEquals(2,storedFileCount());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DOCUMENTER_LOAD_TEST_ENABLED",matches="true")
    void sustainedConcurrentUploadDownloadAndHistoryPreserveEveryVersion() throws Exception {
        app.close(); app=null;
        configuration.put("res.storage.upload.hourly-limit",10000); start();
        long owner=transactionOwner();
        String username=jdbc.queryForObject("SELECT username FROM `user` WHERE id=?",String.class,owner);
        token=api("POST","/user/login",Map.of("account",username,"password","IntegrationOnly123!")).path("accessToken").asText();
        Path input=workspace.resolve("sustained.png"); writePng(input,512,512,true);
        byte[] bytes=Files.readAllBytes(input); String digest=sha256(input);
        var uploads=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var downloads=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var histories=new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var ids=java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var start=new CountDownLatch(1);
        int workers=4, rounds=40;
        long started=System.nanoTime();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(workers)) {
            var results=new ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<workers;worker++) results.add(pool.submit(()->{
                awaitRelease(start); long origin=System.nanoTime();
                try {
                    for(int round=0;round<rounds;round++) {
                        long begin=System.nanoTime();
                        var created=upload(bytes,"sustained.png","image/png"); uploads.add(elapsedMillis(begin));
                        long id=created.path("id").asLong(); assertTrue(ids.add(id));
                        assertEquals(digest,created.path("sha256").asText());
                        begin=System.nanoTime(); assertArrayEquals(bytes,download("/file/"+id+"/versions/1/download")); downloads.add(elapsedMillis(begin));
                        begin=System.nanoTime(); assertEquals(1,api("GET","/file/"+id+"/versions",null).size()); histories.add(elapsedMillis(begin));
                        long remaining=origin+Duration.ofMillis(750L*(round+1)).toNanos()-System.nanoTime();
                        if(remaining>0) TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                } catch(Exception failure) { throw new IllegalStateException(failure); }
            }));
            start.countDown(); for(var result:results) result.get(60,TimeUnit.SECONDS);
        }
        long elapsed=elapsedMillis(started); assertTrue(elapsed>=30000);
        assertEquals(workers*rounds,ids.size());
        assertEquals(ids.size(),jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE user_id=? AND latest_version=1",Integer.class,owner));
        assertEquals(ids.size(),jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE sha256<>?",Integer.class,digest));
        assertEquals(ids.size(),storedFileCount(),"持续读写后不应有孤儿文件");
        var report=new LinkedHashMap<String,Object>(); report.put("scope","30-second paced loopback HTTP baseline; not a production capacity test");
        report.put("timestamp",java.time.Instant.now().toString()); report.put("concurrency",workers); report.put("roundsPerWorker",rounds);
        report.put("elapsedMs",elapsed); report.put("files",ids.size()); report.put("requests",ids.size()*3); report.put("failedRequests",0);
        report.put("sampleBytes",bytes.length); report.put("transferredBytes",2L*bytes.length*ids.size());
        report.put("uploadMs",latencySummary(uploads)); report.put("downloadMs",latencySummary(downloads)); report.put("historyMs",latencySummary(histories));
        Path output=Path.of("target/sustained-load-report.json"); Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static Map<String,Long> latencySummary(java.util.Collection<Long> values) {
        var sorted=values.stream().sorted().toList();
        return Map.of("p50",sorted.get(sorted.size()/2),"p95",sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*0.95)-1)),"max",sorted.getLast());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DOCUMENTER_TEST_REDIS_SERVER",matches=".+")
    void isolatedRedisOutageFailsClosedAndRecoversWithoutRevivingTokens() throws Exception {
        Path executable=Path.of(System.getenv("DOCUMENTER_TEST_REDIS_SERVER")).toAbsolutePath();
        assertTrue(Files.isRegularFile(executable));
        int port; try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {port=socket.getLocalPort();}
        Path directory=Files.createDirectories(workspace.resolve("private-redis"));
        Files.writeString(directory.resolve("redis.conf"),"bind 127.0.0.1\nport "+port+"\nprotected-mode yes\nsave \"\"\nappendonly no\ndir ./\ndaemonize no\nlogfile \"\"\n");
        var builder=new ProcessBuilder(executable.toString(),"redis.conf").directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile());
        Process child=builder.start();
        try {
            awaitRedisPort(child,port);
            app.close(); app=null;
            configuration.put("spring.data.redis.port",port); configuration.put("spring.data.redis.timeout","500ms");
            configuration.put("spring.data.redis.connect-timeout","500ms"); start();
            api("POST","/user/register",Map.of("username","redis_outage","password","IntegrationOnly123!"));
            var login=api("POST","/user/login",Map.of("account","redis_outage","password","IntegrationOnly123!"));
            token=login.path("accessToken").asText(); String oldAccess=token, oldRefresh=login.path("refreshToken").asText();
            long owner=api("GET","/user/me",null).path("id").asLong();
            child.destroyForcibly(); assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            var denied=send("GET","/user/me",null,null);
            assertEquals(503,denied.statusCode(),"Redis 不可用应返回明确 503，不能绕过认证或成为普通 500");
            assertEquals(503,json.readTree(denied.body()).path("code").asInt());
            assertEquals(503,jsonRequest("POST","/user/login",Map.of("account","redis_outage","password","IntegrationOnly123!")).path("code").asInt());
            assertEquals(503,uploadResponse(docx(),"denied.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("code").asInt());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_asset",Integer.class));
            assertEquals(0,storedFileCount());
            child=builder.start(); awaitRedisPort(child,port);
            long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            JsonNode recovered=null;
            while(System.nanoTime()<deadline) {
                recovered=jsonRequest("POST","/user/login",Map.of("account","redis_outage","password","IntegrationOnly123!"));
                if(recovered.path("code").asInt(-1)==0) break;
                assertEquals(503,recovered.path("code").asInt()); Thread.sleep(100);
            }
            assertNotNull(recovered); assertEquals(0,recovered.path("code").asInt(-1));
            String newAccess=recovered.path("data").path("accessToken").asText();
            token=oldAccess; assertEquals(401,send("GET","/user/me",null,null).statusCode());
            assertEquals(401,jsonRequest("POST","/user/refresh",Map.of("refreshToken",oldRefresh)).path("code").asInt());
            token=newAccess; assertEquals(owner,api("GET","/user/me",null).path("id").asLong());
            byte[] bytes=docx(); long file=upload(bytes,"recovered.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
            assertArrayEquals(bytes,download("/file/"+file+"/versions/1/download"));
        } finally {
            if(app!=null) {app.close(); app=null;}
            if(child.isAlive()) {child.destroyForcibly(); assertTrue(child.waitFor(5,TimeUnit.SECONDS));}
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"aof","rdb"})
    @EnabledIfEnvironmentVariable(named="DOCUMENTER_TEST_REDIS_SERVER",matches=".+")
    void redisPersistencePreservesSessionsRevocationsAndQuotaAfterForcedRestart(String mode) throws Exception {
        Path executable=Path.of(System.getenv("DOCUMENTER_TEST_REDIS_SERVER")).toAbsolutePath();
        assertTrue(Files.isRegularFile(executable));
        int port; try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {port=socket.getLocalPort();}
        Path directory=Files.createDirectories(workspace.resolve("persistent-redis"));
        Files.writeString(directory.resolve("redis.conf"),"bind 127.0.0.1\nport "+port+"\nprotected-mode yes\nsave \"\"\n"+
                (mode.equals("aof")?"appendonly yes\nappendfsync always\n":"appendonly no\n")+"dir ./\ndaemonize no\nlogfile \"\"\n");
        var builder=new ProcessBuilder(executable.toString(),"redis.conf").directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile());
        Process child=builder.start();
        var privateFactory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        try {
            awaitRedisPort(child,port); privateFactory.afterPropertiesSet(); privateFactory.start();
            var privateRedis=new StringRedisTemplate(privateFactory);
            app.close(); app=null; configuration.put("spring.data.redis.port",port);
            configuration.put("spring.data.redis.timeout","500ms"); configuration.put("spring.data.redis.connect-timeout","500ms"); start();
            api("POST","/user/register",Map.of("username","persist_test","password","IntegrationOnly123!"));
            var first=api("POST","/user/login",Map.of("account","persist_test","password","IntegrationOnly123!"));
            String oldRefresh=first.path("refreshToken").asText();
            var rotated=api("POST","/user/refresh",Map.of("refreshToken",oldRefresh));
            String liveAccess=rotated.path("accessToken").asText(),liveRefresh=rotated.path("refreshToken").asText();
            var revoked=api("POST","/user/login",Map.of("account","persist_test","password","IntegrationOnly123!"));
            token=revoked.path("accessToken").asText(); String revokedAccess=token,revokedRefresh=revoked.path("refreshToken").asText();
            api("POST","/user/logout",null);
            token=liveAccess; long owner=api("GET","/user/me",null).path("id").asLong();
            byte[] bytes=docx(); long file=upload(bytes,"persist.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
            long session=api("POST","/ai/sessions",Map.of("title","persist quota")).path("id").asLong();
            assertEquals("COMPLETED",api("POST","/ai/sessions/"+session+"/replies",Map.of("content","hello","idempotencyKey",UUID.randomUUID().toString())).path("runStatus").asText());
            var keys=new ArrayList<String>();
            for(String pattern:List.of("upload:quota:"+owner+":*","ai:daily-call:"+owner+":*"))
                try(var cursor=privateRedis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {while(cursor.hasNext()) keys.add(cursor.next());}
            assertEquals(2,keys.size());
            var quotas=new HashMap<String,String>(); for(String key:keys) {quotas.put(key,privateRedis.opsForValue().get(key));assertEquals("1",quotas.get(key));}
            var sessions=privateRedis.opsForSet().members("login:user_sessions:"+owner); assertNotNull(sessions); assertEquals(1,sessions.size());
            var ttlKeys=new ArrayList<>(keys);ttlKeys.add("login:user_sessions:"+owner);
            for(String id:sessions) ttlKeys.add("login:session:"+id);
            var ttls=new HashMap<String,Long>();
            for(String key:ttlKeys) {long ttl=privateRedis.getExpire(key);assertTrue(ttl>0);ttls.put(key,ttl);}
            if(mode.equals("rdb")) {
                privateRedis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection->{connection.serverCommands().save();return null;});
                assertTrue(Files.isRegularFile(directory.resolve("dump.rdb")));
            } else try(var paths=Files.walk(directory)) {
                assertTrue(paths.anyMatch(path->path.toString().endsWith(".aof")),"AOF 文件必须实际生成");
            }
            child.destroyForcibly(); assertTrue(child.waitFor(5,TimeUnit.SECONDS)); child=builder.start(); awaitRedisPort(child,port);
            long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            HttpResponse<byte[]> restored=null;
            while(System.nanoTime()<deadline) {
                restored=send("GET","/user/me",null,null); if(restored.statusCode()==200) break;
                assertEquals(503,restored.statusCode()); Thread.sleep(100);
            }
            assertNotNull(restored); assertEquals(200,restored.statusCode());
            assertEquals(owner,json.readTree(restored.body()).path("data").path("id").asLong());
            for(var quota:quotas.entrySet()) assertEquals(quota.getValue(),privateRedis.opsForValue().get(quota.getKey()));
            assertEquals(sessions,privateRedis.opsForSet().members("login:user_sessions:"+owner));
            for(var ttl:ttls.entrySet()) {
                long restoredTtl=privateRedis.getExpire(ttl.getKey());
                assertTrue(restoredTtl>0 && restoredTtl<=ttl.getValue(),"恢复后不能丢失或延长原 TTL");
            }
            assertEquals(401,jsonRequest("POST","/user/refresh",Map.of("refreshToken",oldRefresh)).path("code").asInt());
            token=revokedAccess; assertEquals(401,send("GET","/user/me",null,null).statusCode());
            assertEquals(401,jsonRequest("POST","/user/refresh",Map.of("refreshToken",revokedRefresh)).path("code").asInt());
            token=api("POST","/user/refresh",Map.of("refreshToken",liveRefresh)).path("accessToken").asText();
            assertArrayEquals(bytes,download("/file/"+file+"/versions/1/download"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
        } finally {
            if(app!=null) {app.close();app=null;}
            privateFactory.destroy();
            if(child.isAlive()) {child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @EnabledIfEnvironmentVariable(named="DOCUMENTER_TEST_REDIS_SERVER",matches=".+")
    void staleRdbRecoveryInvalidatesRestoredSessionsBeforeApplicationStarts() throws Exception {
        Path executable=Path.of(System.getenv("DOCUMENTER_TEST_REDIS_SERVER")).toAbsolutePath();
        Path cli=executable.resolveSibling("redis-cli.exe"); assertTrue(Files.isRegularFile(cli));
        int port; try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {port=socket.getLocalPort();}
        Path directory=Files.createDirectories(workspace.resolve("stale-redis"));
        Files.writeString(directory.resolve("redis.conf"),"bind 127.0.0.1\nport "+port+"\nprotected-mode yes\nsave \"\"\nappendonly no\ndir ./\ndaemonize no\nlogfile \"\"\n");
        var builder=new ProcessBuilder(executable.toString(),"redis.conf").directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile());
        Process child=builder.start();
        var privateFactory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ZERO).build());
        try {
            awaitRedisPort(child,port); privateFactory.afterPropertiesSet(); privateFactory.start();
            var privateRedis=new StringRedisTemplate(privateFactory);
            app.close();app=null;configuration.put("spring.data.redis.port",port);start();
            api("POST","/user/register",Map.of("username","stale_test","password","IntegrationOnly123!"));
            var login=api("POST","/user/login",Map.of("account","stale_test","password","IntegrationOnly123!"));
            token=login.path("accessToken").asText(); String oldAccess=token,oldRefresh=login.path("refreshToken").asText();
            long owner=api("GET","/user/me",null).path("id").asLong();
            byte[] bytes=docx(); long file=upload(bytes,"stale.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
            String quota="upload:quota:"+owner+":"+java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            privateRedis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection->{connection.serverCommands().save();return null;});
            api("POST","/user/logout",null);assertEquals(401,send("GET","/user/me",null,null).statusCode());
            app.close();app=null;
            child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));child=builder.start();awaitRedisPort(child,port);
            // 应用保持停止；先证明旧快照恢复了本应撤销的会话，再执行恢复前清理。
            privateFactory.resetConnection();
            assertEquals(1,privateRedis.opsForSet().size("login:user_sessions:"+owner));
            var preview=runSessionRecovery(cli,port,false); assertFalse(preview.path("applied").asBoolean());
            assertEquals(2,preview.path("matched").asInt());assertEquals(0,preview.path("deleted").asInt());
            assertEquals(1,privateRedis.opsForSet().size("login:user_sessions:"+owner));
            var applied=runSessionRecovery(cli,port,true);assertEquals(2,applied.path("deleted").asInt());
            assertEquals(0,runSessionRecovery(cli,port,true).path("deleted").asInt(),"恢复清理应可重复执行");
            assertEquals("1",privateRedis.opsForValue().get(quota));assertTrue(privateRedis.getExpire(quota)>0);
            assertEquals(0,privateRedis.opsForSet().size("login:user_sessions:"+owner));
            start();token=oldAccess;
            assertEquals(401,send("GET","/user/me",null,null).statusCode());
            assertEquals(401,jsonRequest("POST","/user/refresh",Map.of("refreshToken",oldRefresh)).path("code").asInt());
            token=api("POST","/user/login",Map.of("account","stale_test","password","IntegrationOnly123!")).path("accessToken").asText();
            assertArrayEquals(bytes,download("/file/"+file+"/versions/1/download"));
            privateRedis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection->{
                connection.execute("ACL","SETUSER".getBytes(StandardCharsets.UTF_8),"default".getBytes(StandardCharsets.UTF_8),"-scan".getBytes(StandardCharsets.UTF_8));return null;
            });
            runSessionRecovery(cli,port,true,false);
        } finally {
            if(app!=null) {app.close();app=null;}privateFactory.destroy();
            if(child.isAlive()) {child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}
        }
    }

    private JsonNode runSessionRecovery(Path cli,int port,boolean apply) throws Exception {
        return runSessionRecovery(cli,port,apply,true);
    }

    private JsonNode runSessionRecovery(Path cli,int port,boolean apply,boolean expectedSuccess) throws Exception {
        Path shell=Path.of(System.getenv().getOrDefault("DOCUMENTER_TEST_POWERSHELL","pwsh"));
        var command=new ArrayList<>(List.of(shell.toString(),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts/invalidate-restored-sessions.ps1").toAbsolutePath().toString(),"-RedisCli",cli.toString(),"-Port",Integer.toString(port)));
        if(apply) command.add("-Apply");
        Path log=workspace.resolve(apply?"session-recovery-apply.json":"session-recovery-preview.json");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().remove("REDISCLI_AUTH");
        Process helper=builder.start();
        try {
            assertTrue(helper.waitFor(15,TimeUnit.SECONDS));
            if(!expectedSuccess) {assertNotEquals(0,helper.exitValue(),"缺少 Redis 权限时恢复脚本必须失败");return null;}
            assertEquals(0,helper.exitValue(),()->{try{return Files.readString(log);}catch(Exception failure){return "Recovery script failed";}});
            return json.readTree(Files.readAllBytes(log));
        } finally {if(helper.isAlive()) {helper.destroyForcibly();assertTrue(helper.waitFor(5,TimeUnit.SECONDS));}}
    }

    @Test
    void unavailableStorageDirectoryLeavesExistingVersionUntouchedAndRecovers() throws Exception {
        long owner=transactionOwner(); String username=jdbc.queryForObject("SELECT username FROM `user` WHERE id=?",String.class,owner);
        token=api("POST","/user/login",Map.of("account",username,"password","IntegrationOnly123!")).path("accessToken").asText();
        byte[] bytes=docx(); long file=upload(bytes,"original.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document").path("id").asLong();
        String key=jdbc.queryForObject("SELECT storage_key FROM file_asset WHERE id=?",String.class,file);
        Path root=workspace.resolve("storage"),backup=workspace.resolve("storage-offline");
        Files.move(root,backup);
        try {
            Files.writeString(root,"isolated unavailable directory fixture");
            var rejected=uploadResponse(bytes,"failed.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertEquals(500,rejected.path("code").asInt());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_asset",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
            assertEquals(com.documenter.util.Digests.sha256Hex(bytes),sha256(backup.resolve(key)));
        } finally {
            if(Files.exists(root)) {assertTrue(Files.isRegularFile(root));Files.delete(root);}
            Files.move(backup,root);
        }
        assertArrayEquals(bytes,download("/file/"+file+"/versions/1/download"));
        upload(bytes,"recovered.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM document_version",Integer.class));
        assertEquals(2,storedFileCount());
    }

    private static void awaitRedisPort(Process child,int port) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(child.isAlive() && System.nanoTime()<deadline) {
            try(var socket=new java.net.Socket()) {socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),100); return;}
            catch(java.io.IOException unavailable) {Thread.sleep(50);}
        }
        fail("Isolated Redis did not start");
    }

    private long transactionOwner() {
        var request=new com.documenter.dto.UserRegisterDTO();
        request.setUsername("tx_"+UUID.randomUUID().toString().replace("-", "").substring(0,12));
        request.setPassword("IntegrationOnly123!");
        return app.getBean(com.documenter.service.UserService.class).register(request).getId();
    }

    private org.springframework.transaction.support.TransactionTemplate transactions() {
        return new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }
    private byte[] transactionDocx() throws Exception {
        try(var document=new XWPFDocument(); var output=new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("transaction fixture {{name}}"); document.write(output); return output.toByteArray();
        }
    }
    private long storedFileCount() throws Exception {
        Path root=workspace.resolve("storage"); if(!Files.exists(root)) return 0;
        try(var paths=Files.walk(root)) { return paths.filter(Files::isRegularFile).count(); }
    }
    private com.documenter.dto.SaveModelConfigDTO modelDto(String code,boolean isDefault) {
        var dto=new com.documenter.dto.SaveModelConfigDTO(); dto.setCode(code); dto.setName(code); dto.setScope("TEXT");
        dto.setProvider("local-test"); dto.setModelName("unused"); dto.setIsDefault(isDefault); return dto;
    }

    private void start() {
        app = new SpringApplicationBuilder(DocumenterApplication.class).initializers(context ->
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-http-test", configuration)))
                .run("--spring.profiles.active=integration", "--server.port=0", "--server.address=127.0.0.1",
                        "--logging.level.com.documenter=INFO", "--spring.main.banner-mode=off");
        base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private JsonNode terminal(long task) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            var status = api("GET", "/task/" + task, null).path("task");
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status.path("status").asText())) return status;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("异步任务未在限定时间内结束"); return null;
    }

    private JsonNode api(String method, String path, Object body) throws Exception {
        var result = jsonRequest(method, path, body);
        assertEquals(0, result.path("code").asInt(-1), () -> path + ": " + result.path("msg").asText());
        return result.path("data");
    }

    private JsonNode jsonRequest(String method, String path, Object body) throws Exception {
        return json.readTree(send(method, path, body == null ? null : json.writeValueAsBytes(body), "application/json").body());
    }

    private byte[] download(String path) throws Exception {
        var result = send("GET", path, null, null);
        assertEquals(200, result.statusCode(), path);
        return result.body();
    }

    private JsonNode upload(byte[] bytes, String name, String type) throws Exception {
        var result = uploadResponse(bytes, name, type);
        assertEquals(0, result.path("code").asInt(-1), () -> result.path("msg").asText());
        return result.path("data");
    }

    private JsonNode uploadResponse(byte[] bytes, String name, String type) throws Exception {
        String boundary = "integration" + UUID.randomUUID();
        var payload = new ByteArrayOutputStream();
        payload.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        payload.write(bytes);
        payload.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return json.readTree(send("POST", "/file/upload", payload.toByteArray(), "multipart/form-data; boundary=" + boundary).body());
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(25))
                .header("X-Request-ID", UUID.randomUUID().toString());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private DriverManagerDataSource datasource(String name) {
        String server = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL");
        assertNotNull(server);
        assertTrue(server.matches("jdbc:mariadb://(?:127\\.0\\.0\\.1|localhost):[0-9]+/"));
        return new DriverManagerDataSource(server + name + "?connectTimeout=5000&socketTimeout=30000",
                System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER"), System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD"));
    }

    private static int redisPort() {
        String port = System.getenv("DOCUMENTER_TEST_REDIS_PORT");
        assertNotNull(port, "需显式指定本地 Redis 测试端口");
        return Integer.parseInt(port);
    }

    private void removeOwnKeys(String pattern) {
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) redis.delete(cursor.next());
        }
    }

    private static byte[] docx() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("原始内容");
            document.createTable(2, 2).getRow(0).getCell(0).setText("表格保留");
            document.write(output); return output.toByteArray();
        }
    }

    private static byte[] pdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 740); content.showText("Restart recovery sample"); content.endText();
            }
            document.save(output); return output.toByteArray();
        }
    }
}
