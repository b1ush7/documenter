package com.documenter.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConflictResponseTest {
    @org.junit.jupiter.api.Test
    void lostDatabaseConnectionAndRedisTimeoutReturn503WithoutLeakingDetails() {
        for(Exception failure:java.util.List.of(
                new org.springframework.dao.QueryTimeoutException("private redis endpoint"),
                new org.springframework.data.redis.RedisConnectionFailureException("private redis endpoint"),
                new org.springframework.jdbc.UncategorizedSQLException("write","private SQL",
                        new java.sql.SQLException("private database endpoint","08006",0)))) {
            var response=new GlobalExceptionHandler().handleException(failure);
            assertEquals(503,response.getStatusCode().value()); assertEquals(503,response.getBody().getCode());
            assertFalse(response.getBody().getMsg().contains("private"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints={1020,1205,1213})
    void databaseWriteConflictsReturn409ThroughWrappedException(int code) {
        var failure=new org.springframework.jdbc.UncategorizedSQLException("write","private SQL",
                new java.sql.SQLException("private database detail","HY000",code));
        var response=new GlobalExceptionHandler().handleException(failure);
        assertEquals(409,response.getBody().getCode());
        assertFalse(response.getBody().getMsg().contains("private"));
    }
}
