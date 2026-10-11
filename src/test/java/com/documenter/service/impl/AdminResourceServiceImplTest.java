package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.TaskService;
import com.documenter.vo.FileDownload;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminResourceServiceImplTest {

    private final FileAssetMapper fileAssetMapper = mock(FileAssetMapper.class);
    private final ProcessingTaskMapper taskMapper = mock(ProcessingTaskMapper.class);
    private final FileService fileService = mock(FileService.class);
    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final DocxDocumentService docxDocumentService = mock(DocxDocumentService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminResourceServiceImpl service = new AdminResourceServiceImpl(
            fileAssetMapper, taskMapper, fileService, versionService,
            docxDocumentService, taskService, auditService);

    @Test
    void fileDetailIncludesOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);

        var result = service.fileDetail(10L);

        assertEquals(10L, result.getId());
        assertEquals(7L, result.getUserId());
        verify(auditService).success(AuditAction.ADMIN_FILE_VIEW, "FILE", 10L, "查看文件元数据");
    }

    @Test
    void downloadDelegatesWithActualOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        FileDownload expected = new FileDownload(asset, null, 0, "application/pdf", "sample.pdf");
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);
        when(fileService.download(7L, 10L)).thenReturn(expected);

        FileDownload result = service.downloadFile(10L);

        assertSame(expected, result);
        verify(fileService).download(7L, 10L);
        verify(auditService).success(AuditAction.ADMIN_FILE_DOWNLOAD, "FILE", 10L,
                "下载用户文件当前版本");
    }

    @Test
    void taskDetailDelegatesWithActualOwnerAndWritesAudit() {
        ProcessingTask task = task(20L, 8L);
        TaskDetailVO detail = TaskDetailVO.of(TaskVO.from(task), List.of());
        when(taskMapper.selectById(20L)).thenReturn(task);
        when(taskService.detail(8L, 20L)).thenReturn(detail);

        var result = service.taskDetail(20L);

        assertEquals(8L, result.getTask().getUserId());
        assertEquals(20L, result.getTask().getId());
        assertEquals(List.of(), result.getSteps());
        verify(taskService).detail(8L, 20L);
        verify(auditService).success(AuditAction.ADMIN_TASK_VIEW, "TASK", 20L,
                "查看任务与执行步骤");
    }

    @Test
    void missingFileDoesNotInvokeOwnedDownload() {
        when(fileAssetMapper.selectById(99L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.downloadFile(99L));

        assertEquals(404, error.getCode());
        verify(fileService, never()).download(7L, 99L);
    }

    private static FileAsset file(Long id, Long userId) {
        FileAsset asset = new FileAsset();
        asset.setId(id);
        asset.setUserId(userId);
        asset.setDisplayName("sample.pdf");
        asset.setContentType("application/pdf");
        asset.setStatus("READY");
        asset.setLatestVersion(1);
        return asset;
    }

    private static ProcessingTask task(Long id, Long userId) {
        ProcessingTask task = new ProcessingTask();
        task.setId(id);
        task.setUserId(userId);
        task.setStatus("FAILED");
        task.setProgress(50);
        task.setRetryCount(0);
        task.setMaxRetry(2);
        return task;
    }
}
