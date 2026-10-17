package com.documenter.service.impl;

import com.documenter.component.StorageWriter;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocTemplateService;
import com.documenter.service.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real storage and Spring transaction advice; Mapper substitutes do not verify MariaDB SQL. */
class DocTemplateServiceImplTest {
    @TempDir Path directory;
    private final DocTemplateMapper mapper = mock(DocTemplateMapper.class);
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
        when(mapper.replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    row.get().setStorageKey(invocation.getArgument(1));
                    row.get().setOriginalName(invocation.getArgument(2));
                    row.get().setContentType(invocation.getArgument(3));
                    row.get().setExtension(invocation.getArgument(4));
                    row.get().setSizeBytes(invocation.getArgument(5));
                    row.get().setVersionNo(row.get().getVersionNo() + 1);
                    return 1;
                });
        var target = new DocTemplateServiceImpl(mapper, uploads, storage, audit);
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE", "NO_ROW", "AUDIT", "RELOAD"})
    void everyReplacementFailureDeletesOnlyNewFile(String failure) {
        RuntimeException injected = new DataAccessResourceFailureException("write failure");
        switch (failure) {
            case "DATABASE" -> doThrow(injected).when(mapper)
                    .replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong());
            case "NO_ROW" -> doReturn(0).when(mapper)
                    .replaceContent(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong());
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
        verify(mapper, never()).updateById(any(DocTemplate.class));
        verifyNoInteractions(uploads, audit);
        verify(storage, never()).delete(anyString());
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
