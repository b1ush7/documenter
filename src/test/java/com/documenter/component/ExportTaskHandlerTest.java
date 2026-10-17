package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentExportService;
import com.documenter.service.FileStorage;
import com.documenter.service.TaskExecutionContext;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExportTaskHandlerTest {

    private final DocumentExportService exportService = mock(DocumentExportService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter versionWriter = mock(VersionWriter.class);
    private final ExportTaskHandler handler = new ExportTaskHandler(
            exportService, storage, versionWriter, new StorageProperties());

    @Test
    void exportsPdfAsTaskVersionAndRecordsOutput() {
        ProcessingTask task = task();
        when(context.attemptNo()).thenReturn(1);
        when(exportService.exportPdf(7L, 10L, 3)).thenReturn(download("%PDF-test".getBytes()));
        when(storage.store(any(byte[].class), eq("pdf"))).thenReturn("versions/v5.pdf");
        when(versionWriter.appendVersion(eq(10L), eq(7L), any(),
                eq(ChangeType.CONVERT), anyString(), anyString(), eq(3), eq(20L), eq(1)))
                .thenReturn(created("versions/v5.pdf"));

        handler.handle(task, context);

        verify(context).completeWithVersion(5);
        verify(storage, never()).delete("versions/v5.pdf");
        verify(context).reportProgress(100);
    }

    @Test
    void conflictingVersionCleansUnreferencedPdf() {
        ProcessingTask task = task();
        when(context.attemptNo()).thenReturn(1);
        when(exportService.exportPdf(7L, 10L, 3)).thenReturn(download("%PDF-test".getBytes()));
        when(storage.store(any(byte[].class), eq("pdf"))).thenReturn("versions/v5.pdf");
        when(versionWriter.appendVersion(eq(10L), eq(7L), any(),
                eq(ChangeType.CONVERT), anyString(), anyString(), eq(3), eq(20L), eq(1)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        when(storage.delete("versions/v5.pdf")).thenReturn(true);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> handler.handle(task, context)).getCode());
        verify(storage).delete("versions/v5.pdf");
        verify(context, never()).completeWithVersion(any());
    }

    @Test
    void missingBoundVersionFailsBeforeExport() {
        ProcessingTask invalid = task();
        invalid.setFileId(null);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> handler.handle(invalid, context)).getCode());
        verifyNoInteractions(exportService, storage, versionWriter);
    }

    private final TaskExecutionContext context = mock(TaskExecutionContext.class);

    private static ProcessingTask task() {
        ProcessingTask task = new ProcessingTask();
        task.setId(20L);
        task.setUserId(7L);
        task.setFileId(10L);
        task.setInputVersion(3);
        return task;
    }

    private static FileDownload download(byte[] bytes) {
        FileAsset asset = new FileAsset();
        asset.setId(10L);
        asset.setUserId(7L);
        asset.setDisplayName("sample.pdf");
        asset.setContentType("application/pdf");
        asset.setExtension("pdf");
        return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length,
                "application/pdf", "sample.pdf");
    }

    private static DocumentVersion created(String storageKey) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(5);
        version.setStorageKey(storageKey);
        return version;
    }
}
