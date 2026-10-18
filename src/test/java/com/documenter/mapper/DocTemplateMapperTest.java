package com.documenter.mapper;

import com.documenter.entity.DocTemplate;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real MyBatis mapper/parameter handling; the JDBC boundary is substituted, not a MariaDB integration test. */
class DocTemplateMapperTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(1);
        configuration = new Configuration(new Environment("mapper-test", new JdbcTransactionFactory(), source));
        configuration.addMapper(DocTemplateMapper.class);
    }

    @Test
    void metadataClearingBindsExplicitSqlNullAndDoesNotWriteContentOrStatus() throws Exception {
        DocTemplate metadata = new DocTemplate();
        metadata.setId(7L);
        metadata.setCode("weekly");
        metadata.setName("周报");
        // Even accidental values on these entity fields must not overwrite content/status through metadata PUT.
        metadata.setStorageKey("unrelated");
        metadata.setVersionNo(99);
        metadata.setEnabled(false);

        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(1, session.getMapper(DocTemplateMapper.class).updateMetadata(metadata));
        }
        verify(statement).setString(1, "weekly");
        verify(statement).setString(2, "周报");
        verify(statement).setNull(3, Types.VARCHAR);
        verify(statement).setNull(4, Types.VARCHAR);
        verify(statement).setNull(5, Types.VARCHAR);
        verify(statement).setLong(6, 7L);
        String sql = executedSql();
        assertFalse(sql.contains("STORAGE_KEY"));
        assertFalse(sql.contains("ENABLED"));
        assertFalse(sql.contains("VERSION_NO"));
        verify(statement).execute();
    }

    @Test
    void statusWriteCannotClearNullableMetadata() throws Exception {
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(1, session.getMapper(DocTemplateMapper.class).updateEnabled(7L, true));
        }
        verify(statement).setBoolean(1, true);
        verify(statement).setLong(2, 7L);
        assertEquals("UPDATE DOC_TEMPLATE SET ENABLED = ? WHERE ID = ?", executedSql());
    }

    @Test
    void deleteEnforcesDisabledPredicateAtJdbcBoundaryAndReturnsAffectedCount() throws Exception {
        when(statement.getUpdateCount()).thenReturn(0);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(0, session.getMapper(DocTemplateMapper.class).deleteDisabled(7L));
        }
        assertEquals("DELETE FROM DOC_TEMPLATE WHERE ID = ? AND ENABLED = 0", executedSql());
        verify(statement).setLong(1, 7L);
    }

    @Test
    void contentUpdateUsesExpectedVersionAndNeverWritesEditableMetadata() throws Exception {
        when(statement.getUpdateCount()).thenReturn(0);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(0, session.getMapper(DocTemplateMapper.class).replaceContent(7L, "history-key", "old.docx",
                    "application/test", "docx", 12L, 4));
        }
        verify(statement).setString(1, "history-key");
        verify(statement).setLong(5, 12L);
        verify(statement).setLong(6, 7L);
        verify(statement).setInt(7, 4);
        String sql = executedSql();
        assertTrue(sql.endsWith("WHERE ID = ? AND VERSION_NO = ?"));
        assertTrue(sql.contains("VERSION_NO = VERSION_NO + 1"));
        assertFalse(sql.contains("VARIABLES"));
        assertFalse(sql.contains("ENABLED"));
        assertFalse(sql.contains("CATEGORY"));
    }

    @Test
    void historicalLookupBindsBothTemplateAndVersion() throws Exception {
        configuration.addMapper(DocTemplateVersionMapper.class);
        when(statement.getUpdateCount()).thenReturn(-1);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertNull(session.getMapper(DocTemplateVersionMapper.class).findVersion(7L, 4));
        }
        verify(statement).setLong(1, 7L);
        verify(statement).setInt(2, 4);
        assertEquals("SELECT * FROM DOC_TEMPLATE_VERSION WHERE TEMPLATE_ID = ? AND VERSION_NO = ?", executedSql());
    }

    private String executedSql() throws Exception {
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
