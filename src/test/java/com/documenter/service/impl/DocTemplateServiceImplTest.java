package com.documenter.service.impl;

import com.documenter.component.StorageWriter;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.dto.TemplateVersionQueryDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.mapper.DocTemplateVersionMapper;
import com.documenter.entity.DocTemplateVersion;
import com.documenter.service.AuditService;
import com.documenter.service.DocTemplateService;
import com.documenter.service.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real storage and Spring transaction advice; Mapper substitutes do not verify MariaDB SQL. */
class DocTemplateServiceImplTest {
    @TempDir Path directory;
    private final DocTemplateMapper mapper = mock(DocTemplateMapper.class);
    private final DocTemplateVersionMapper versions = mock(DocTemplateVersionMapper.class);
    private final StorageWriter uploads = mock(StorageWriter.class);
    private final AuditService audit = mock(AuditService.class);
    private final AtomicReference<DocTemplate> row = new AtomicReference<>();
    private final AtomicReference<String> newKey = new AtomicReference<>();
    private final TestTransactions transactions = new TestTransactions();
    private final MultipartFile upload = new MockMultipartFile("file", "new.docx", "application/octet-stream",
            new byte[]{1, 2, 3});
    private FileStorage storage;
    private String oldKey;
    private DocTemplateService service;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setRoot(directory.resolve("storage").toString());
        properties.getLocal().setTemp(directory.resolve("temp").toString());
        storage = spy(new LocalFileStorage(properties));
        oldKey = storage.store(new byte[]{9, 8}, "docx");
        DocTemplate existing = new DocTemplate();
        existing.setId(1L);
        existing.setCode("weekly");
        existing.setName("周报");
        existing.setStorageKey(oldKey);
        existing.setVersionNo(1);
        existing.setEnabled(false);
        row.set(existing);

        when(uploads.store(upload)).thenAnswer(invocation -> {
            String key = storage.store(upload.getBytes(), "docx");
            newKey.set(key);
            return new StorageWriter.StoredFile(key, "new.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "docx", 3L, "digest");
        });
        when(mapper.insert(any(DocTemplate.class))).thenAnswer(invocation -> {
            DocTemplate created = invocation.getArgument(0);
            created.setId(1L);
            row.set(created);
            return 1;
        });
        when(mapper.selectById(1L)).thenAnswer(invocation -> row.get());
        when(mapper.updateMetadata(any(DocTemplate.class))).thenAnswer(invocation -> {
            DocTemplate metadata = invocation.getArgument(0);
            row.get().setCode(metadata.getCode());
            row.get().setName(metadata.getName());
            row.get().setCategory(metadata.getCategory());
            row.get().setDescription(metadata.getDescription());
            row.get().setVariables(metadata.getVariables());
            return 1;
        });
        when(mapper.updateEnabled(eq(1L), anyBoolean())).thenAnswer(invocation -> {
            row.get().setEnabled(invocation.getArgument(1));
            return 1;
        });
        when(mapper.deleteDisabled(1L)).thenAnswer(invocation -> {
            if (Boolean.TRUE.equals(row.get().getEnabled())) return 0;
            row.set(null);
            return 1;
        });
        when(versions.insert(any(DocTemplateVersion.class))).thenReturn(1);
        when(mapper.replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    row.get().setStorageKey(invocation.getArgument(1));
                    row.get().setOriginalName(invocation.getArgument(2));
                    row.get().setContentType(invocation.getArgument(3));
                    row.get().setExtension(invocation.getArgument(4));
                    row.get().setSizeBytes(invocation.getArgument(5));
                    row.get().setVersionNo(row.get().getVersionNo() + 1);
                    return 1;
                });
        var target = new DocTemplateServiceImpl(mapper, versions, uploads, storage, audit);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (DocTemplateService) proxy.getProxy();
    }

    @Test
    void createCommitsNormalizedMetadataAndKeepsStoredFile() {
        var result = service.create(dto(), upload);
        assertEquals("weekly", result.getCode());
        assertEquals("周报", result.getName());
        assertEquals("[{\"name\":\"title\",\"required\":true}]", result.getVariables());
        assertEquals(1, result.getVersionNo());
        assertFalse(result.getEnabled());
        assertTrue(result.getHasFile());
        assertTrue(storage.exists(newKey.get()));
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
        verify(storage, never()).delete(anyString());
        var snapshot = org.mockito.ArgumentCaptor.forClass(DocTemplateVersion.class);
        verify(versions).insert(snapshot.capture());
        assertEquals(1, snapshot.getValue().getVersionNo());
        assertEquals("CREATE", snapshot.getValue().getChangeType());
        assertEquals(newKey.get(), snapshot.getValue().getStorageKey());
    }

    @Test
    void invalidVariablesAreRejectedBeforeUploadingAnyBytes() {
        var invalid = dto();
        invalid.setVariables("[{\"name\":\"title\"},{\"name\":\" title \"}]");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(invalid, upload)).getCode());
        verifyNoInteractions(uploads, audit);
        verify(mapper, never()).insert(any(DocTemplate.class));
        assertTrue(storage.exists(oldKey));
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DUPLICATE", "DATABASE", "NO_ROW", "NO_ID", "AUDIT", "RELOAD"})
    void everyCreateFailureDeletesNewFileExactlyOnce(String failure) {
        RuntimeException injected = new DataAccessResourceFailureException("database unavailable");
        switch (failure) {
            case "DUPLICATE" -> doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any(DocTemplate.class));
            case "DATABASE" -> doThrow(injected).when(mapper).insert(any(DocTemplate.class));
            case "NO_ROW" -> doReturn(0).when(mapper).insert(any(DocTemplate.class));
            case "NO_ID" -> doReturn(1).when(mapper).insert(any(DocTemplate.class));
            case "AUDIT" -> doThrow(injected).when(audit).success(eq(AuditAction.ADMIN_TEMPLATE_CREATE), anyString(), eq(1L), anyString());
            case "RELOAD" -> when(mapper.selectById(1L)).thenThrow(injected);
            default -> fail("unknown failure");
        }
        RuntimeException result = assertThrows(RuntimeException.class, () -> service.create(dto(), upload));
        if (failure.equals("DUPLICATE")) {
            assertEquals(409, ((BusinessException) result).getCode());
        } else if (failure.equals("NO_ROW") || failure.equals("NO_ID")) {
            assertEquals(500, ((BusinessException) result).getCode());
        } else {
            assertSame(injected, result);
        }
        assertNewFileCleaned();
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
    }

    @Test
    void replacementCommitsOneNewVersionAndRetainsOldFile() {
        var result = service.replaceContent(1L, upload);
        assertEquals(2, result.getVersionNo());
        assertEquals("new.docx", result.getOriginalName());
        assertTrue(storage.exists(newKey.get()));
        assertTrue(storage.exists(oldKey));
        assertEquals(1, transactions.commits);
        verify(storage, never()).delete(anyString());
        var snapshot = org.mockito.ArgumentCaptor.forClass(DocTemplateVersion.class);
        verify(versions).insert(snapshot.capture());
        assertEquals(2, snapshot.getValue().getVersionNo());
        assertEquals("REPLACE", snapshot.getValue().getChangeType());
        assertEquals(newKey.get(), snapshot.getValue().getStorageKey());
        verify(mapper).replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), eq(3L), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE", "NO_ROW", "AUDIT", "RELOAD"})
    void everyReplacementFailureDeletesOnlyNewFile(String failure) {
        RuntimeException injected = new DataAccessResourceFailureException("write failure");
        switch (failure) {
            case "DATABASE" -> doThrow(injected).when(mapper)
                    .replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
            case "NO_ROW" -> doReturn(0).when(mapper)
                    .replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
            case "AUDIT" -> doThrow(injected).when(audit).success(eq(AuditAction.ADMIN_TEMPLATE_CONTENT_REPLACE), anyString(), eq(1L), anyString());
            case "RELOAD" -> when(mapper.selectById(1L)).thenReturn(row.get()).thenThrow(injected);
            default -> fail("unknown failure");
        }
        RuntimeException result = assertThrows(RuntimeException.class, () -> service.replaceContent(1L, upload));
        if (failure.equals("NO_ROW")) {
            assertEquals(409, ((BusinessException) result).getCode());
        } else {
            assertSame(injected, result);
        }
        assertNewFileCleaned();
        assertEquals(1, transactions.rollbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "REPLACE"})
    void successfulOperationCleansNewFileWhenOuterTransactionRollsBack(String operation) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (operation.equals("CREATE")) service.create(dto(), upload);
            else service.replaceContent(1L, upload);
            assertTrue(storage.exists(newKey.get()));
            assertEquals(0, transactions.commits);
            status.setRollbackOnly();
        });
        assertNewFileCleaned();
        assertEquals(1, transactions.rollbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "REPLACE"})
    void commitFailureWithConfirmedRollbackCleansNewFile(String operation) {
        transactions.failCommit = true;
        assertThrows(TransactionSystemException.class, () -> {
            if (operation.equals("CREATE")) service.create(dto(), upload);
            else service.replaceContent(1L, upload);
        });
        assertNewFileCleaned();
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void unknownCommitOutcomeKeepsFileToAvoidDeletingPotentiallyCommittedContent() {
        transactions.failCommit = true;
        transactions.setRollbackOnCommitFailure(false);
        assertThrows(TransactionSystemException.class, () -> service.replaceContent(1L, upload));
        assertTrue(storage.exists(newKey.get()));
        assertTrue(storage.exists(oldKey));
        assertEquals(0, transactions.rollbacks);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void invalidVariableUpdateDoesNotWriteMetadataOrAudit() {
        var invalid = dto();
        invalid.setVariables("[{\"name\":true}]");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.update(1L, invalid)).getCode());
        verify(mapper, never()).updateMetadata(any(DocTemplate.class));
        verifyNoInteractions(uploads, audit);
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "null"})
    void metadataUpdateClearsOptionalFieldsWithoutTouchingContentOrStatus(String variables) {
        row.get().setCategory("old category");
        row.get().setDescription("old description");
        row.get().setVariables("[{\"name\":\"old\"}]");
        row.get().setEnabled(true);
        var metadata = dto();
        metadata.setVariables(variables);
        metadata.setCategory("  ");
        metadata.setDescription(null);

        var result = service.update(1L, metadata);
        assertNull(result.getVariables());
        assertNull(result.getCategory());
        assertNull(result.getDescription());
        assertEquals("周报", result.getName());
        assertTrue(result.getEnabled());
        assertEquals(1, result.getVersionNo());
        assertEquals(oldKey, row.get().getStorageKey());
        assertEquals(1, transactions.commits);
        verify(audit).success(eq(AuditAction.ADMIN_TEMPLATE_UPDATE), eq("TEMPLATE"), eq(1L), anyString());
        verifyNoInteractions(uploads);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void metadataUpdateStoresNormalizedVariables() {
        var result = service.update(1L, dto());
        assertEquals("[{\"name\":\"title\",\"required\":true}]", result.getVariables());
        assertEquals(1, transactions.commits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"METADATA", "STATUS", "DELETE"})
    void zeroAffectedRowsReportsConflictWithoutSuccessAudit(String operation) {
        switch (operation) {
            case "METADATA" -> doReturn(0).when(mapper).updateMetadata(any(DocTemplate.class));
            case "STATUS" -> doReturn(0).when(mapper).updateEnabled(1L, true);
            case "DELETE" -> doReturn(0).when(mapper).deleteDisabled(1L);
            default -> fail("unknown operation");
        }
        var error = assertThrows(BusinessException.class, () -> mutate(operation));
        assertEquals(409, error.getCode());
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        verifyNoInteractions(audit, uploads);
        assertTrue(storage.exists(oldKey));
    }

    @Test
    void concurrentDuplicateCodeUpdateReturnsConflictWithoutSuccessAudit() {
        doThrow(new DuplicateKeyException("concurrent duplicate")).when(mapper).updateMetadata(any(DocTemplate.class));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.update(1L, dto())).getCode());
        verifyNoInteractions(audit);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void templateEnabledAfterInitialReadCannotBeDeleted() {
        doAnswer(invocation -> {
            row.get().setEnabled(true);
            return 0;
        }).when(mapper).deleteDisabled(1L);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.delete(1L)).getCode());
        assertTrue(row.get().getEnabled());
        verifyNoInteractions(audit);
        verify(mapper, never()).deleteById(anyLong());
        assertTrue(storage.exists(oldKey));
    }

    @Test
    void changingStatusPreservesAllMetadataAndContent() {
        row.get().setVariables("[{\"name\":\"title\"}]");
        var enabled = service.updateEnabled(1L, true);
        assertTrue(enabled.getEnabled());
        assertEquals("[{\"name\":\"title\"}]", enabled.getVariables());
        assertEquals("周报", enabled.getName());
        assertEquals(1, enabled.getVersionNo());
        var disabled = service.updateEnabled(1L, false);
        assertFalse(disabled.getEnabled());
        assertEquals(oldKey, row.get().getStorageKey());
        assertEquals(2, transactions.commits);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void deletingDisabledTemplateRetainsStoredFile() {
        service.delete(1L);
        assertNull(row.get());
        assertTrue(storage.exists(oldKey));
        assertEquals(1, transactions.commits);
        verify(audit).success(eq(AuditAction.ADMIN_TEMPLATE_DELETE), eq("TEMPLATE"), eq(1L), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"METADATA", "STATUS", "DELETE"})
    void mutationFailureRollsBackAndPreservesOriginalException(String operation) {
        var injected = new DataAccessResourceFailureException("audit unavailable");
        doThrow(injected).when(audit).success(any(AuditAction.class), anyString(), eq(1L), anyString());
        assertSame(injected, assertThrows(DataAccessResourceFailureException.class, () -> mutate(operation)));
        assertEquals(1, transactions.rollbacks);
        assertEquals(0, transactions.commits);
        assertTrue(storage.exists(oldKey));
    }

    private void mutate(String operation) {
        switch (operation) {
            case "METADATA" -> service.update(1L, dto());
            case "STATUS" -> service.updateEnabled(1L, true);
            case "DELETE" -> service.delete(1L);
            default -> fail("unknown operation");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE_ZERO", "CREATE_DUPLICATE", "CREATE_ERROR", "REPLACE_ZERO", "REPLACE_DUPLICATE", "REPLACE_ERROR"})
    void historyIndexFailureRollsBackAndCleansOnlyNewUpload(String failure) {
        var injected = new DataAccessResourceFailureException("history insert failed");
        if (failure.endsWith("ZERO")) doReturn(0).when(versions).insert(any(DocTemplateVersion.class));
        else if (failure.endsWith("DUPLICATE")) doThrow(new DuplicateKeyException("duplicate history"))
                .when(versions).insert(any(DocTemplateVersion.class));
        else doThrow(injected).when(versions).insert(any(DocTemplateVersion.class));
        RuntimeException result = assertThrows(RuntimeException.class, () -> {
            if (failure.startsWith("CREATE")) service.create(dto(), upload);
            else service.replaceContent(1L, upload);
        });
        if (failure.endsWith("ZERO")) assertEquals(500, ((BusinessException) result).getCode());
        else if (failure.endsWith("DUPLICATE")) assertEquals(409, ((BusinessException) result).getCode());
        else assertSame(injected, result);
        assertNewFileCleaned();
        verifyNoInteractions(audit);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void restorationReusesHistoricalDocxAndPreservesCurrentMetadataAndEnabledStatus() throws Exception {
        var historical = history();
        String historicalKey = historical.getStorageKey();
        row.get().setVersionNo(2);
        row.get().setExtension("png");
        row.get().setContentType("image/png");
        row.get().setCategory("report");
        row.get().setVariables("[{\"name\":\"current\"}]");
        row.get().setEnabled(true);
        clearInvocations(storage);

        var restored = service.restoreVersion(1L, 1, 2);
        assertEquals(3, restored.getVersionNo());
        assertEquals("docx", restored.getExtension());
        assertEquals(historical.getContentType(), restored.getContentType());
        assertEquals("report", restored.getCategory());
        assertEquals("[{\"name\":\"current\"}]", restored.getVariables());
        assertTrue(restored.getEnabled());
        var snapshot = org.mockito.ArgumentCaptor.forClass(DocTemplateVersion.class);
        verify(versions).insert(snapshot.capture());
        assertEquals("RESTORE", snapshot.getValue().getChangeType());
        assertEquals(1, snapshot.getValue().getRestoredFromVersion());
        assertEquals(3, snapshot.getValue().getVersionNo());
        assertEquals(historicalKey, snapshot.getValue().getStorageKey());
        when(versions.findVersion(1L, 3)).thenReturn(snapshot.getValue());
        var download = service.downloadVersion(1L, 3);
        assertEquals("周报-v3.docx", download.downloadFileName());
        assertEquals(historical.getContentType(), download.contentType());
        try (var input = download.resource().getInputStream();
             var docx = new org.apache.poi.xwpf.usermodel.XWPFDocument(input)) {
            assertEquals("历史模板正文", docx.getParagraphs().getFirst().getText());
        }
        verify(storage, never()).store(any(byte[].class), anyString());
        verify(storage, never()).delete(anyString());
        verifyNoInteractions(uploads);
        assertTrue(storage.exists(oldKey));
        assertTrue(storage.exists(historicalKey));
        assertEquals(1, transactions.commits);

        assertEquals(409, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 1, 2)).getCode());
        verify(versions, times(1)).insert(any(DocTemplateVersion.class));
    }

    @Test
    void restorationRejectsStaleVersionBeforeLookingUpHistory() {
        assertEquals(409, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 1, 5)).getCode());
        verify(versions, never()).findVersion(anyLong(), anyInt());
        verify(versions, never()).insert(any(DocTemplateVersion.class));
        verifyNoInteractions(audit, uploads);
    }

    @Test
    void concurrentContentWriteIsRejectedWithoutAddingHistory() throws Exception {
        history();
        doReturn(0).when(mapper).replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong(), eq(1));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 1, 1)).getCode());
        verify(versions, never()).insert(any(DocTemplateVersion.class));
        verifyNoInteractions(audit, uploads);
        verify(storage, never()).delete(anyString());
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void restoreIndexFailureRollsBackWithoutDeletingSharedHistoricalBytes() throws Exception {
        var historical = history();
        var injected = new DataAccessResourceFailureException("history failure");
        doThrow(injected).when(versions).insert(any(DocTemplateVersion.class));
        assertSame(injected, assertThrows(DataAccessResourceFailureException.class,
                () -> service.restoreVersion(1L, 1, 1)));
        assertTrue(storage.exists(historical.getStorageKey()));
        assertTrue(storage.exists(oldKey));
        verify(storage, never()).delete(anyString());
        verifyNoInteractions(audit, uploads);
        assertEquals(1, transactions.rollbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_FILE", "CONTENT_TYPE", "EXTENSION", "SIZE"})
    void unusableHistoricalContentCannotBeDownloadedOrRestored(String failure) throws Exception {
        var historical = history();
        switch (failure) {
            case "MISSING_FILE" -> historical.setStorageKey("missing.docx");
            case "CONTENT_TYPE" -> historical.setContentType(null);
            case "EXTENSION" -> historical.setExtension(null);
            case "SIZE" -> historical.setSizeBytes(null);
            default -> fail("unknown failure");
        }
        assertEquals(500, assertThrows(BusinessException.class, () -> service.downloadVersion(1L, 1)).getCode());
        assertEquals(500, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 1, 1)).getCode());
        verify(versions, never()).insert(any(DocTemplateVersion.class));
        verify(mapper, never()).replaceContent(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyInt());
        verifyNoInteractions(audit);
    }

    @Test
    void missingAndCrossTemplateHistoryReturns404() throws Exception {
        history();
        assertEquals(404, assertThrows(BusinessException.class, () -> service.downloadVersion(1L, 9)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 9, 1)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.downloadVersion(99L, 1)).getCode());
        verify(versions, never()).findVersion(eq(99L), anyInt());
        verifyNoInteractions(audit);
    }

    @Test
    void historyPaginationUsesBoundedOffsetAndPublicView() throws Exception {
        var historical = history();
        when(versions.countVersions(1L)).thenReturn(25L);
        when(versions.listVersions(1L, 10L, 10L)).thenReturn(List.of(historical));
        var query = new TemplateVersionQueryDTO();
        query.setPage(2);
        query.setSize(10);
        var page = service.listVersions(1L, query);
        assertEquals(25, page.getTotal());
        assertEquals(3, page.getPages());
        assertEquals(1, page.getRecords().getFirst().versionNo());
        String serialized = new tools.jackson.databind.ObjectMapper().writeValueAsString(page);
        assertFalse(serialized.contains("storageKey"));
        assertFalse(serialized.contains(historical.getStorageKey()));
        assertFalse(serialized.contains("variables"));
        query.setPage(Long.MAX_VALUE);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.listVersions(1L, query)).getCode());
        query.setPage(1);
        query.setSize(101);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.listVersions(1L, query)).getCode());
    }

    @Test
    void historyWritesDoNotOccurDuringMetadataOrStatusUpdates() {
        service.update(1L, dto());
        service.updateEnabled(1L, true);
        verifyNoInteractions(versions);
    }

    @Test
    void invalidVersionNumbersNeverReachHistoryLookupOrStorage() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.downloadVersion(1L, 0)).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.restoreVersion(1L, 1, null)).getCode());
        row.get().setVersionNo(Integer.MAX_VALUE);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.replaceContent(1L, upload)).getCode());
        verifyNoInteractions(versions, uploads);
    }

    private DocTemplateVersion history() throws Exception {
        byte[] bytes;
        try (var docx = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var output = new ByteArrayOutputStream()) {
            docx.createParagraph().createRun().setText("历史模板正文");
            docx.write(output);
            bytes = output.toByteArray();
        }
        var version = new DocTemplateVersion();
        version.setTemplateId(1L);
        version.setVersionNo(1);
        version.setStorageKey(storage.store(bytes, "docx"));
        version.setOriginalName("historical.docx");
        version.setExtension("docx");
        version.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        version.setSizeBytes((long) bytes.length);
        version.setChangeType("BASELINE");
        when(versions.findVersion(1L, 1)).thenReturn(version);
        return version;
    }

    @Test
    void cleanupFailureDoesNotReplaceDatabaseError() {
        var injected = new DataAccessResourceFailureException("original error");
        doThrow(injected).when(mapper).insert(any(DocTemplate.class));
        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete(anyString());
        assertSame(injected, assertThrows(DataAccessResourceFailureException.class, () -> service.create(dto(), upload)));
        verify(storage, times(1)).delete(newKey.get());
        assertTrue(storage.exists(oldKey));
    }

    private void assertNewFileCleaned() {
        assertNotNull(newKey.get());
        assertFalse(storage.exists(newKey.get()));
        assertTrue(storage.exists(oldKey));
        verify(storage, times(1)).delete(newKey.get());
        verify(storage, never()).delete(oldKey);
    }

    private static SaveDocTemplateDTO dto() {
        var dto = new SaveDocTemplateDTO();
        dto.setCode("weekly");
        dto.setName(" 周报 ");
        dto.setVariables("[{\"name\":\" title \",\"required\":true}]");
        return dto;
    }

    /** Drives Spring's real commit/rollback callbacks without claiming database integration coverage. */
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<State> current = new ThreadLocal<>();
        int commits;
        int rollbacks;
        boolean failCommit;

        TestTransactions() { setRollbackOnCommitFailure(true); }
        @Override protected Object doGetTransaction() {
            return current.get() == null ? new State() : current.get();
        }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((State) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            State state = (State) transaction;
            state.active = true;
            current.set(state);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("commit failed");
            commits++;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doCleanupAfterCompletion(Object transaction) {
            ((State) transaction).active = false;
            current.remove();
        }
        private static class State { boolean active; }
    }
}
