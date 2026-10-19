package com.documenter.mapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.documenter.configuration.StorageProperties;
import com.documenter.service.impl.LocalFileStorage;
import com.documenter.service.impl.StorageRecycleService;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 仅创建连接级临时表；不会清库或修改正式表。需要显式提供测试库和 CREATE TEMPORARY TABLE 权限。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTER_RECYCLE_DB_TEST_ENABLED", matches = "true")
class StorageRecycleMariaDbIntegrationTest {
    @TempDir Path workspace;
    @Test
    void realMariaDbProtectsSharedHistoryTemplatesAndActiveWorkAcrossPurge() throws Exception {
        String url = System.getenv("DOCUMENTER_RECYCLE_DB_TEST_URL");
        assertNotNull(url, "请配置专用测试库 DOCUMENTER_RECYCLE_DB_TEST_URL");
        assertTrue(url.startsWith("jdbc:mariadb:"));
        try (var connection = DriverManager.getConnection(url,
                System.getenv("DOCUMENTER_RECYCLE_DB_TEST_USER"), System.getenv("DOCUMENTER_RECYCLE_DB_TEST_PASSWORD"))) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE file_asset (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(16), storage_key VARCHAR(512), delete_time DATETIME(3))");
                // 使用实际迁移添加墓碑及引用索引，且所有同名表均为连接隔离的临时表。
                statement.execute("CREATE TEMPORARY TABLE document_version (file_id BIGINT, storage_key VARCHAR(512))");
                statement.execute("CREATE TEMPORARY TABLE doc_template (storage_key VARCHAR(512))");
                statement.execute("CREATE TEMPORARY TABLE doc_template_version (storage_key VARCHAR(512))");
                statement.execute("CREATE TEMPORARY TABLE processing_task (file_id BIGINT, status VARCHAR(16))");
                statement.execute("CREATE TEMPORARY TABLE ai_session (id BIGINT, user_id BIGINT, file_id BIGINT)");
                statement.execute("CREATE TEMPORARY TABLE ai_message (session_id BIGINT, run_status VARCHAR(16), plan_status VARCHAR(16))");
                try (var migration = getClass().getResourceAsStream("/db/migration/V8__storage_recycling.sql")) {
                    assertNotNull(migration);
                    String ddl = new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("(?m)^--.*$", "");
                    for (String sql : ddl.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
                statement.execute("INSERT INTO file_asset (id, status, storage_key, delete_time) VALUES "
                        + "(1,'READY','shared',NULL),(2,'DELETED','shared',CURRENT_TIMESTAMP - INTERVAL 60 DAY),"
                        + "(3,'DELETED','recent',CURRENT_TIMESTAMP),(4,'DELETED','task',CURRENT_TIMESTAMP - INTERVAL 60 DAY),"
                        + "(5,'DELETED','ai',CURRENT_TIMESTAMP - INTERVAL 60 DAY),(6,'DELETED','collect',CURRENT_TIMESTAMP - INTERVAL 60 DAY),"
                        + "(7,'DELETED','missing-time',NULL),(8,'DELETED','pending-image',CURRENT_TIMESTAMP - INTERVAL 60 DAY)");
                statement.execute("UPDATE file_asset SET user_id = id");
                statement.execute("UPDATE file_asset SET user_id = 5 WHERE id = 8");
                statement.execute("INSERT INTO document_version VALUES (1,'history'),(2,'deleted-history'),(99,'dangling'),(6,'collect-old')");
                statement.execute("INSERT INTO doc_template VALUES ('template')");
                statement.execute("INSERT INTO doc_template_version VALUES ('removed-template-history')");
                statement.execute("INSERT INTO processing_task VALUES (4,'RUNNING')");
                statement.execute("INSERT INTO ai_session VALUES (10,5,5)");
                statement.execute("INSERT INTO ai_message VALUES (10,'COMPLETED','PENDING')");
            }
            var datasource = new SingleConnectionDataSource(connection, true);
            var config = new Configuration(new Environment("recycle-integration", new JdbcTransactionFactory(), datasource));
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.addMapper(StorageRecycleMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var mapper = session.getMapper(StorageRecycleMapper.class);
                var cutoff = LocalDateTime.now().minusDays(30);
                assertEquals(List.of(2L, 6L), mapper.eligibleFiles(cutoff, 100));
                assertEquals(1, mapper.protectedReference("shared", List.of(2L, 6L)));
                assertEquals(0, mapper.protectedReference("collect-old", List.of(2L, 6L)));
                // 最终 UPDATE 再次判断活跃任务；即使候选清单过时也不能标记。
                assertEquals(2, mapper.markPurged(List.of(2L, 4L, 5L, 6L, 7L, 8L), cutoff, LocalDateTime.now()));
                assertEquals(List.of(), mapper.eligibleFiles(cutoff, 100));
                for (String key : List.of("shared", "history", "recent", "task", "ai", "missing-time",
                        "dangling", "template", "removed-template-history", "pending-image")) {
                    assertEquals(1, mapper.protectedReference(key, List.of()), key);
                }
                for (String key : List.of("deleted-history", "collect", "collect-old", "orphan")) {
                    assertEquals(0, mapper.protectedReference(key, List.of()), key);
                }

                // 用真实 Mapper + 隔离磁盘验证完整回收，不访问正式存储目录。
                var properties = new StorageProperties();
                properties.getLocal().setRoot(workspace.resolve("storage").toString());
                properties.getLocal().setTemp(workspace.resolve("temp").toString());
                properties.getRecycle().setEnabled(true);
                properties.getRecycle().setDryRun(false);
                var storage = new LocalFileStorage(properties);
                String shared = oldBlob(storage, "shared");
                String previouslyPurged = oldBlob(storage, "previously purged");
                String expired = oldBlob(storage, "expired");
                String pendingImage = oldBlob(storage, "pending plan image");
                try (var update = connection.prepareStatement("UPDATE file_asset SET storage_key = ? WHERE id IN (1,2)")) {
                    update.setString(1, shared); update.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE file_asset SET storage_key = ? WHERE id = 6")) {
                    update.setString(1, previouslyPurged); update.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE file_asset SET storage_key = ? WHERE id = 8")) {
                    update.setString(1, pendingImage); update.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO file_asset (id,user_id,status,storage_key,delete_time) "
                        + "VALUES (9,9,'DELETED',?,CURRENT_TIMESTAMP - INTERVAL 60 DAY)")) {
                    insert.setString(1, expired); insert.executeUpdate();
                }
                var service = new StorageRecycleService(storage, properties, mapper);
                var preview = service.recycle(true);
                assertEquals(1, preview.expiredFiles());
                assertEquals(2, preview.entries().size());
                assertEquals(0, preview.deletedBlobs());
                assertTrue(storage.exists(expired));
                assertEquals(List.of(9L), mapper.eligibleFiles(cutoff, 100));
                var actual = service.recycle(false);
                assertEquals(1, actual.expiredFiles());
                assertEquals(2, actual.deletedBlobs());
                assertFalse(storage.exists(expired));
                assertFalse(storage.exists(previouslyPurged));
                assertTrue(storage.exists(shared));
                assertTrue(storage.exists(pendingImage));
                assertEquals(0, service.recycle(false).deletedBlobs());
                assertEquals(List.of(), mapper.eligibleFiles(cutoff, 100));
            }
        }
    }

    private static String oldBlob(LocalFileStorage storage, String content) throws Exception {
        String key = storage.store(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), "docx");
        Files.setLastModifiedTime(storage.resolvePath(key),
                FileTime.from(Instant.now().minus(java.time.Duration.ofDays(2))));
        return key;
    }
}
