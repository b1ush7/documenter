package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.documenter.configuration.StorageProperties;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 完整真实库验收。只创建/删除本轮随机库，绝不迁移或清空应用业务库。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_LIFECYCLE_DB_TEST_ENABLED", matches = "true")
class DatabaseLifecycleIntegrationTest {
    @TempDir Path workspace;
    private final List<String> ownedSchemas = new ArrayList<>();
    private String schema;
    private DriverManagerDataSource datasource;
    private JdbcTemplate jdbc;
    private LocalFileStorage storage;
    private VersionWriter writer;
    private DocumentVersionService versions;
    private com.documenter.mapper.StorageRecycleMapper recycleMapper;

    @BeforeEach
    void createIsolatedSchema() {
        schema = createSchema();
        datasource = dataSource(schema);
        jdbc = new JdbcTemplate(datasource);
    }

    @AfterEach
    void dropOnlySchemasCreatedByThisTest() {
        var admin = new JdbcTemplate(dataSource(""));
        for (String name : ownedSchemas) {
            if (!name.matches("documenter_it_[0-9a-f]{32}")) throw new IllegalStateException("拒绝清理非测试库");
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }

    @Test
    void upgradesPopulatedV1ThroughV9AndValidatesSecondStartup() {
        migrate(schema, "1");
        jdbc.update("INSERT INTO file_asset (id,user_id,original_name,display_name,storage_key,content_type,extension,size_bytes,sha256,latest_version) "
                + "VALUES (1,1,'old.docx','old.docx','old-key','application/test','docx',4,?,1)", "a".repeat(64));
        jdbc.update("INSERT INTO document_version (file_id,user_id,version_no,storage_key,size_bytes,sha256) "
                + "VALUES (1,1,1,'old-key',4,?)", "a".repeat(64));
        migrate(schema, "5");
        jdbc.update("INSERT INTO doc_template (id,code,name,storage_key,version_no) VALUES (1,'legacy','旧模板','template-key',4)");
        var migration = migrate(schema, null);
        assertEquals(0, migration.migrate().migrationsExecuted);
        migration.validate();
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class));
        assertEquals("application/test", jdbc.queryForObject("SELECT content_type FROM document_version WHERE id=1", String.class));
        assertEquals(4, jdbc.queryForObject("SELECT version_no FROM doc_template_version WHERE template_id=1", Integer.class));
        assertEquals("BASELINE", jdbc.queryForObject("SELECT change_type FROM doc_template_version WHERE template_id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ocr_correction", Integer.class));
        assertNull(jdbc.queryForObject("SELECT storage_purged_time FROM file_asset WHERE id=1", java.sql.Timestamp.class));
    }

    @Test
    void realServiceRollsBackVersionAllocationOnDatabaseFailureAndReplaysTaskIdempotently() throws Exception {
        prepareVersionService();
        var invalid = new VersionWriter.VersionContent("x".repeat(600), 4, "a".repeat(64));
        assertThrows(RuntimeException.class, () -> writer.appendVersion(1L, 1L, invalid,
                ChangeType.MANUAL_EDIT, null, "must rollback", 1));
        assertEquals(1, latest());
        assertEquals(1, versionCount());
        var content = content("task result");
        var first = writer.appendVersion(1L, 1L, content, ChangeType.CONVERT, null, "task", 1, 101L, 1);
        var replay = writer.appendVersion(1L, 1L, content, ChangeType.CONVERT, null, "replay", 1, 101L, 1);
        assertEquals(first.getId(), replay.getId());
        assertEquals(2, latest());
        assertEquals(2, versionCount());
        assertThrows(BusinessException.class, () -> versions.downloadVersion(2L, 1L, 1));
        var restored = versions.restore(1L, 1L, 1, 2);
        assertEquals(3, restored.getVersionNo());
        try (var input = versions.downloadVersion(1L, 1L, 3).resource().getInputStream()) {
            assertArrayEquals("original".getBytes(java.nio.charset.StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void concurrentRealServiceEditsHaveOneWinnerAndNoPartialVersions() throws Exception {
        prepareVersionService();
        var content = content("concurrent");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    writer.appendVersion(1L, 1L, content, ChangeType.MANUAL_EDIT, null, "concurrent", 1);
                    return true;
                } catch (BusinessException conflict) {
                    assertEquals(409, conflict.getCode());
                    return false;
                }
            }));
            start.countDown();
            int winners = 0;
            for (var result : results) if (result.get(20, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(2, latest());
            assertEquals(2, versionCount());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOCUMENTER_TEST_MARIADB_BIN", matches = ".+")
    void dumpAndRestoreDatabaseWithMatchingStorageArchive() throws Exception {
        prepareVersionService();
        writer.appendVersion(1L, 1L, content("中文备份内容"), ChangeType.MANUAL_EDIT, null, "backup", 1);
        Path dump = workspace.resolve("database.sql");
        Path zip = workspace.resolve("storage.zip");
        Path errors = workspace.resolve("client-errors.log");
        Path binaries = Path.of(System.getenv("DOCUMENTER_TEST_MARIADB_BIN"));
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        var backup = client(binaries.resolve("mariadb-dump" + suffix), "--single-transaction", "--skip-comments", "--hex-blob", schema)
                .redirectOutput(dump.toFile()).redirectError(errors.toFile()).start();
        awaitClient(backup, errors);
        Path root = storage.resolvePath(".");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip)); var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        String restoredSchema = createSchema();
        var restore = client(binaries.resolve("mariadb" + suffix), "--default-character-set=utf8mb4", restoredSchema)
                .redirectInput(dump.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start();
        awaitClient(restore, errors);
        Path restoredRoot = workspace.resolve("restored-storage");
        Files.createDirectories(restoredRoot);
        try (var input = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path target = restoredRoot.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(restoredRoot));
                Files.createDirectories(target.getParent());
                Files.copy(input, target);
            }
        }
        var restoredJdbc = new JdbcTemplate(dataSource(restoredSchema));
        assertEquals(2, restoredJdbc.queryForObject("SELECT COUNT(*) FROM document_version", Integer.class));
        assertEquals(2, restoredJdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=1", Integer.class));
        restoredJdbc.query("SELECT storage_key,sha256 FROM document_version", result -> {
            try (var input = Files.newInputStream(restoredRoot.resolve(result.getString(1)))) {
                assertEquals(result.getString(2), Digests.sha256Hex(input));
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        var restoredFlyway = Flyway.configure().dataSource(dataSource(restoredSchema)).cleanDisabled(true).load();
        restoredFlyway.validate();
        assertEquals(0, restoredFlyway.migrate().migrationsExecuted);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"storage","before","after"})
    void forcedJvmDeathAtWriteAndCommitBoundariesPreservesAtomicVersions(String phase) throws Exception {
        prepareVersionService();
        var entries=new java.util.LinkedHashSet<String>();
        String testClasspath=System.getProperty("surefire.test.class.path");
        if(testClasspath!=null) entries.addAll(List.of(testClasspath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent()) {
            if(loader instanceof java.net.URLClassLoader urls) for(var url:urls.getURLs())
                if(url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toString());
        }
        assertFalse(entries.isEmpty(),"Missing child JVM test classpath");
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java");
        Path signal=workspace.resolve("boundary-ready"), log=workspace.resolve("crash-child.log");
        var builder=new ProcessBuilder(javaExecutable.toString(),"-cp",String.join(java.io.File.pathSeparator,entries),
                "com.documenter.support.CrashWriteProbe",workspace.resolve("storage").toString(),signal.toString(),phase);
        builder.environment().put("DOCUMENTER_CRASH_DB_URL",System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL")+schema);
        Process child=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(!Files.exists(signal) && child.isAlive() && System.nanoTime()<deadline) Thread.sleep(50);
            assertTrue(Files.exists(signal),()->{ try{return Files.readString(log);}catch(Exception failure){return "Child did not reach boundary";} });
            assertTrue(child.isAlive()); child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS));
            // 等待已被杀进程的数据库连接释放行锁，防止只读快照掩盖未结束事务。
            jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=1 FOR UPDATE WAIT 5",Integer.class);
            int expected=phase.equals("after")?2:1; assertEquals(expected,latest()); assertEquals(expected,versionCount());
            jdbc.query("SELECT storage_key,sha256 FROM document_version",row->{
                try(var input=storage.read(row.getString(1))) { assertEquals(row.getString(2),Digests.sha256Hex(input)); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            });
            try(var input=versions.downloadVersion(1L,1L,1).resource().getInputStream()) {
                assertArrayEquals("original".getBytes(java.nio.charset.StandardCharsets.UTF_8),input.readAllBytes());
            }
            try(var paths=Files.walk(workspace.resolve("storage"))) {
                assertEquals(2,paths.filter(Files::isRegularFile).count(),"强杀遗留候选文件与已提交原文件均可追踪");
            }
            if(!phase.equals("storage")) {
                String candidate=Files.readString(signal);
                assertEquals(phase.equals("after")?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE storage_key=?",Integer.class,candidate));
            }
            Flyway.configure().dataSource(datasource).load().validate();
            // 真实回收只清理强杀后的无引用候选文件，已提交版本即使很旧也必须保留。
            try(var paths=Files.walk(workspace.resolve("storage"))) {
                for(var path:paths.filter(Files::isRegularFile).toList())
                    Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2))));
            }
            var policy=new StorageProperties(); policy.getLocal().setRoot(workspace.resolve("storage").toString());
            policy.getLocal().setTemp(workspace.resolve("temp").toString());
            policy.getRecycle().setEnabled(true); policy.getRecycle().setDryRun(false);
            var report=new StorageRecycleService(storage,policy,recycleMapper).recycle(false);
            assertEquals(phase.equals("after")?0:1,report.deletedBlobs());
            assertEquals(expected,versionCount());
            jdbc.query("SELECT storage_key,sha256 FROM document_version",row->{
                try(var input=storage.read(row.getString(1))) { assertEquals(row.getString(2),Digests.sha256Hex(input)); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            });
        } finally {
            if(child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(10,TimeUnit.SECONDS)); }
        }
    }

    @Test
    void disconnectedWriterRollsBackAndRetryProducesExactlyOneVersion() throws Exception {
        prepareVersionService();
        var candidate=content("connection-loss candidate");
        var ready=new CountDownLatch(1); var release=new CountDownLatch(1);
        var connectionId=new java.util.concurrent.atomic.AtomicLong();
        var transactions=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(datasource));
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(()->transactions.executeWithoutResult(status->{
                writer.appendVersion(1L,1L,candidate,ChangeType.CONVERT,null,"disconnect",1,9001L,1);
                connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)); ready.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("disconnect barrier timeout"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                jdbc.queryForObject("SELECT 1",Integer.class);
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            assertEquals(1,latest()); assertEquals(1,versionCount());
            // 只终止本测试事务报告的连接，且先由数据库核对它确实属于随机隔离库。
            assertTrue(connectionId.get()>0);
            assertEquals(schema,jdbc.queryForObject("SELECT DB FROM information_schema.PROCESSLIST WHERE ID=?",String.class,connectionId.get()));
            jdbc.execute("KILL CONNECTION "+connectionId.get()); release.countDown();
            assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));
            jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=1 FOR UPDATE WAIT 5",Integer.class);
            assertEquals(1,latest()); assertEquals(1,versionCount());
            var retried=writer.appendVersion(1L,1L,candidate,ChangeType.CONVERT,null,"retry",1,9001L,1);
            var replay=writer.appendVersion(1L,1L,candidate,ChangeType.CONVERT,null,"replay",1,9001L,1);
            assertEquals(retried.getId(),replay.getId()); assertEquals(2,latest()); assertEquals(2,versionCount());
            jdbc.query("SELECT storage_key,sha256 FROM document_version",row->{
                try(var input=storage.read(row.getString(1))) { assertEquals(row.getString(2),Digests.sha256Hex(input)); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            });
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));
        }
    }

    private void prepareVersionService() throws Exception {
        migrate(schema, null);
        var properties = new StorageProperties();
        properties.getLocal().setRoot(workspace.resolve("storage").toString());
        properties.getLocal().setTemp(workspace.resolve("temp").toString());
        storage = new LocalFileStorage(properties);
        var original = content("original");
        jdbc.update("INSERT INTO file_asset (id,user_id,original_name,display_name,storage_key,content_type,extension,size_bytes,sha256,latest_version) "
                + "VALUES (1,1,'sample.docx','sample.docx',?,'application/test','docx',?,?,1)", original.storageKey(), original.sizeBytes(), original.sha256());
        jdbc.update("INSERT INTO document_version (file_id,user_id,version_no,storage_key,content_type,extension,size_bytes,sha256) "
                + "VALUES (1,1,1,?,'application/test','docx',?,?)", original.storageKey(), original.sizeBytes(), original.sha256());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        factory.setConfiguration(configuration);
        var sessions = factory.getObject();
        assertNotNull(sessions);
        sessions.getConfiguration().addMapper(FileAssetMapper.class);
        sessions.getConfiguration().addMapper(DocumentVersionMapper.class);
        sessions.getConfiguration().addMapper(com.documenter.mapper.StorageRecycleMapper.class);
        var template = new SqlSessionTemplate(sessions);
        recycleMapper=template.getMapper(com.documenter.mapper.StorageRecycleMapper.class);
        var files = template.getMapper(FileAssetMapper.class);
        var fileService = new FileServiceImpl(files, storage, properties, null, null, null);
        var target = new DocumentVersionServiceImpl(template.getMapper(DocumentVersionMapper.class), files, fileService, storage);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource), new AnnotationTransactionAttributeSource()));
        Object service = proxy.getProxy();
        writer = (VersionWriter) service;
        versions = (DocumentVersionService) service;
    }

    private VersionWriter.VersionContent content(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new VersionWriter.VersionContent(storage.store(bytes, "docx"), bytes.length, Digests.sha256Hex(bytes));
    }

    private int latest() { return jdbc.queryForObject("SELECT latest_version FROM file_asset WHERE id=1", Integer.class); }
    private int versionCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM document_version WHERE file_id=1", Integer.class); }

    private Flyway migrate(String name, String target) {
        var config = Flyway.configure().dataSource(dataSource(name)).baselineOnMigrate(false).cleanDisabled(true);
        if (target != null) config.target(target);
        Flyway flyway = config.load();
        flyway.migrate();
        return flyway;
    }

    private String createSchema() {
        String name = "documenter_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource("")).execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci");
        ownedSchemas.add(name);
        return name;
    }

    private DriverManagerDataSource dataSource(String name) {
        String base = System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL");
        assertNotNull(base, "需显式设置测试服务器 URL");
        assertTrue(base.matches("jdbc:mariadb://(?:127\\.0\\.0\\.1|localhost):[0-9]+/"), "只允许本机服务器级 URL，不接受业务库名");
        return new DriverManagerDataSource(base + name + "?connectTimeout=5000&socketTimeout=30000",
                System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER"), System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD"));
    }

    private ProcessBuilder client(Path executable, String... arguments) {
        assertTrue(Files.isRegularFile(executable), "缺少 MariaDB 客户端：" + executable);
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--no-defaults", "--host=127.0.0.1",
                "--port=" + System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_URL").replaceAll(".*:([0-9]+)/$", "$1"),
                "--user=" + System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_USER")));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command);
        builder.environment().put("MYSQL_PWD", System.getenv("DOCUMENTER_LIFECYCLE_DB_TEST_PASSWORD"));
        return builder;
    }

    private static void awaitClient(Process process, Path errors) throws Exception {
        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(finished, "MariaDB 客户端超时");
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(errors); } catch (Exception failure) { return "无法读取客户端错误"; }
        });
    }
}
