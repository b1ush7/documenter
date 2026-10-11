package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AdminStatisticsMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.PdfDocumentService;
import com.documenter.service.TaskService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.AdminOverviewVO;
import com.documenter.vo.AdminUserUsageVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;
import com.documenter.vo.XlsxStructureVO;
import com.documenter.vo.XlsxRangeVO;
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
    private final AdminStatisticsMapper statisticsMapper = mock(AdminStatisticsMapper.class);
    private final FileService fileService = mock(FileService.class);
    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final DocxDocumentService docxDocumentService = mock(DocxDocumentService.class);
    private final PdfDocumentService pdfDocumentService = mock(PdfDocumentService.class);
    private final XlsxDocumentService xlsxDocumentService = mock(XlsxDocumentService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminResourceServiceImpl service = new AdminResourceServiceImpl(
            fileAssetMapper, taskMapper, statisticsMapper, fileService, versionService,
            docxDocumentService, pdfDocumentService, xlsxDocumentService, taskService, auditService);

    @Test
    void readsXlsxWithActualOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        XlsxStructureVO expected = new XlsxStructureVO(10L, 1, 0, 0, false, List.of());
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);
        when(xlsxDocumentService.readStructure(7L, 10L, 1)).thenReturn(expected);

        var result = service.readXlsxStructure(10L, 1);

        assertSame(expected, result);
        verify(xlsxDocumentService).readStructure(7L, 10L, 1);
        verify(auditService).success(AuditAction.ADMIN_FILE_VIEW, "FILE", 10L,
                "读取 XLSX 版本 1 的结构");
    }

    @Test
    void readsXlsxRangeWithActualOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        XlsxRangeVO expected = new XlsxRangeVO(
                10L, 1, "数据", "A1:B2", 0, 0, 2, 2, List.of());
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);
        when(xlsxDocumentService.readRange(7L, 10L, 1, "数据", "A1:B2"))
                .thenReturn(expected);

        var result = service.readXlsxRange(10L, 1, "数据", "A1:B2");

        assertSame(expected, result);
        verify(xlsxDocumentService).readRange(7L, 10L, 1, "数据", "A1:B2");
        verify(auditService).success(AuditAction.ADMIN_FILE_VIEW, "FILE", 10L,
                "读取 XLSX 版本 1 的区域 A1:B2");
    }

    @Test
    void overviewReturnsAggregatesAndWritesAudit() {
        AdminOverviewVO expected = new AdminOverviewVO();
        expected.setTotalUsers(12L);
        expected.setFailedTasks(3L);
        when(statisticsMapper.selectOverview()).thenReturn(expected);

        var result = service.overview();

        assertSame(expected, result);
        verify(auditService).success(AuditAction.ADMIN_STATISTICS_VIEW, "SYSTEM", null,
                "查看系统使用量概览");
    }

    @Test
    void userUsageReturns404WhenUserDoesNotExist() {
        when(statisticsMapper.selectUserUsage(99L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.userUsage(99L));

        assertEquals(404, error.getCode());
    }

    @Test
    void userUsageReturnsAggregatesAndWritesAudit() {
        AdminUserUsageVO expected = new AdminUserUsageVO();
        expected.setUserId(7L);
        expected.setStorageBytes(1024L);
        when(statisticsMapper.selectUserUsage(7L)).thenReturn(expected);

        var result = service.userUsage(7L);

        assertSame(expected, result);
        verify(auditService).success(AuditAction.ADMIN_STATISTICS_VIEW, "USER", 7L,
                "查看用户资源用量");
    }

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
    void renameDelegatesWithActualOwnerAndWritesAudit() {
        FileAsset original = file(10L, 7L);
        FileAsset renamed = file(10L, 7L);
        renamed.setDisplayName("renamed.pdf");
        when(fileAssetMapper.selectById(10L)).thenReturn(original, renamed);

        var result = service.renameFile(10L, "renamed.pdf");

        assertEquals("renamed.pdf", result.getDisplayName());
        verify(fileService).rename(7L, 10L, "renamed.pdf");
        verify(auditService).success(AuditAction.ADMIN_FILE_RENAME, "FILE", 10L,
                "管理员重命名用户文件");
    }

    @Test
    void deleteDelegatesWithActualOwnerAndWritesAudit() {
        when(fileAssetMapper.selectById(10L)).thenReturn(file(10L, 7L));

        service.deleteFile(10L);

        verify(fileService).delete(7L, 10L);
        verify(auditService).success(AuditAction.ADMIN_FILE_DELETE, "FILE", 10L,
                "管理员软删除用户文件");
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
    void pdfContentDelegatesWithActualOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        PdfContentVO expected = new PdfContentVO(10L, 1, "TEXT", 1,
                1, 0, 0, 0, 4, false, List.of());
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);
        when(pdfDocumentService.readContent(7L, 10L, 1)).thenReturn(expected);

        var result = service.readPdfContent(10L, 1);

        assertSame(expected, result);
        verify(auditService).success(AuditAction.ADMIN_FILE_VIEW, "FILE", 10L,
                "读取 PDF 版本 1 的原生文本");
    }

    @Test
    void pdfPreviewDelegatesWithActualOwnerAndWritesAudit() {
        FileAsset asset = file(10L, 7L);
        PdfPagePreview expected = new PdfPagePreview(new byte[]{1, 2}, 1,
                144, 100, 200, "preview.png");
        when(fileAssetMapper.selectById(10L)).thenReturn(asset);
        when(pdfDocumentService.renderPage(7L, 10L, 1, 1, 144)).thenReturn(expected);

        var result = service.previewPdfPage(10L, 1, 1, 144);

        assertSame(expected, result);
        verify(auditService).success(AuditAction.ADMIN_FILE_VIEW, "FILE", 10L,
                "预览 PDF 版本 1 第 1 页");
    }

    @Test
    void cancelTaskDelegatesWithActualOwnerAndReturnsUpdatedState() {
        ProcessingTask running = task(20L, 8L);
        running.setStatus("RUNNING");
        ProcessingTask cancelled = task(20L, 8L);
        cancelled.setStatus("CANCELLED");
        when(taskMapper.selectById(20L)).thenReturn(running, cancelled);

        var result = service.cancelTask(20L);

        assertEquals("CANCELLED", result.getStatus());
        verify(taskService).cancel(8L, 20L);
        verify(auditService).success(AuditAction.ADMIN_TASK_CANCEL, "TASK", 20L,
                "管理员取消用户任务");
    }

    @Test
    void retryTaskReturnsNewTaskAndWritesRelationToAudit() {
        ProcessingTask failed = task(20L, 8L);
        ProcessingTask created = task(21L, 8L);
        created.setStatus("PENDING");
        TaskVO retried = TaskVO.from(created);
        when(taskMapper.selectById(20L)).thenReturn(failed);
        when(taskService.retry(8L, 20L)).thenReturn(retried);
        when(taskService.findById(21L)).thenReturn(created);

        var result = service.retryTask(20L);

        assertEquals(21L, result.getId());
        assertEquals(8L, result.getUserId());
        verify(auditService).success(AuditAction.ADMIN_TASK_RETRY, "TASK", 20L,
                "管理员重试用户任务，新任务 ID=21");
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
