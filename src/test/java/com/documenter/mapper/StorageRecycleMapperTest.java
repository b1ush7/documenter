package com.documenter.mapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 MyBatis 动态 SQL 展开；MariaDB 语义另见显式启用的集成测试。 */
class StorageRecycleMapperTest {
    private final Configuration configuration = new Configuration();

    StorageRecycleMapperTest() { configuration.addMapper(StorageRecycleMapper.class); }

    @Test
    void noIgnoredIdsNeverEmitsEmptyInAndProtectsAllFourReferenceSources() {
        String sql = sql("protectedReference", Map.of("key", "blob", "ignored", List.of()));
        assertFalse(sql.contains("NOT IN"));
        assertTrue(sql.contains("FROM FILE_ASSET"));
        assertTrue(sql.contains("FROM DOCUMENT_VERSION"));
        assertTrue(sql.contains("FROM DOC_TEMPLATE WHERE"));
        assertTrue(sql.contains("FROM DOC_TEMPLATE_VERSION"));
        assertTrue(sql.contains("A.ID IS NULL OR"));
        assertEquals(4, configuration.getMappedStatement(id("protectedReference"))
                .getBoundSql(Map.of("key", "blob", "ignored", List.of())).getParameterMappings().size());
    }

    @Test
    void previewIgnoresOnlySelectedExpiredOwnersAndNeverTemplateHistory() {
        String sql = sql("protectedReference", Map.of("key", "blob", "ignored", List.of(1L, 2L)));
        assertEquals(2, sql.split("A.ID NOT IN", -1).length - 1);
        assertFalse(sql.substring(sql.indexOf("FROM DOC_TEMPLATE WHERE")).contains("NOT IN"));
    }

    @Test
    void purgeRepeatsAgeStatusAndActiveWorkChecksAtDatabaseWriteTime() {
        var args = Map.of("cutoff", LocalDateTime.now().minusDays(30), "now", LocalDateTime.now(),
                "limit", 100, "ids", List.of(1L));
        for (String operation : List.of("eligibleFiles", "markPurged")) {
            String sql = sql(operation, args);
            assertTrue(sql.contains("A.STATUS = 'DELETED' AND A.STORAGE_PURGED_TIME IS NULL"));
            assertTrue(sql.contains("A.DELETE_TIME < ?"));
            assertTrue(sql.contains("T.STATUS IN ('PENDING', 'RUNNING')"));
            assertTrue(sql.contains("S.USER_ID = A.USER_ID"));
            assertTrue(sql.contains("M.RUN_STATUS = 'RUNNING' OR M.PLAN_STATUS = 'PENDING'"));
        }
    }

    private String sql(String method, Map<String, ?> args) {
        return configuration.getMappedStatement(id(method)).getBoundSql(args).getSql()
                .replaceAll("\\s+", " ").trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String id(String method) { return StorageRecycleMapper.class.getName() + "." + method; }
}
