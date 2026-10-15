package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AdminStatisticsMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.service.AdminResourceService;
import com.documenter.service.AuditService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.PagePreviewService;
import com.documenter.service.PdfDocumentService;
import com.documenter.service.TaskService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.AdminFileVO;
import com.documenter.vo.AdminOverviewVO;
import com.documenter.vo.AdminTaskDetailVO;
import com.documenter.vo.AdminTaskVO;
import com.documenter.vo.AdminUserUsageVO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PageResult;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxStructureVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxFormulaResultsVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 管理员跨用户资源查询实现。 */
@Service
public class AdminResourceServiceImpl implements AdminResourceService {

    private static final String STATUS_READY = "READY";

    private final FileAssetMapper fileAssetMapper;
    private final ProcessingTaskMapper taskMapper;
    private final AdminStatisticsMapper statisticsMapper;
    private final FileService fileService;
    private final DocumentVersionService versionService;
    private final DocxDocumentService docxDocumentService;
    private final PdfDocumentService pdfDocumentService;
    private final XlsxDocumentService xlsxDocumentService;
    private final PagePreviewService pagePreviewService;
    private final TaskService taskService;
    private final AuditService auditService;

    public AdminResourceServiceImpl(FileAssetMapper fileAssetMapper,
                                    ProcessingTaskMapper taskMapper,
                                    AdminStatisticsMapper statisticsMapper,
                                    FileService fileService,
                                    DocumentVersionService versionService,
                                    DocxDocumentService docxDocumentService,
                                    PdfDocumentService pdfDocumentService,
                                    XlsxDocumentService xlsxDocumentService,
                                    PagePreviewService pagePreviewService,
                                    TaskService taskService,
                                    AuditService auditService) {
        this.fileAssetMapper = fileAssetMapper;
        this.taskMapper = taskMapper;
        this.statisticsMapper = statisticsMapper;
        this.fileService = fileService;
        this.versionService = versionService;
        this.docxDocumentService = docxDocumentService;
        this.pdfDocumentService = pdfDocumentService;
        this.xlsxDocumentService = xlsxDocumentService;
        this.pagePreviewService = pagePreviewService;
        this.taskService = taskService;
        this.auditService = auditService;
    }

    @Override
    public AdminOverviewVO overview() {
        AdminOverviewVO overview = statisticsMapper.selectOverview();
        if (overview == null) {
            throw new BusinessException(500, "读取系统统计数据失败");
        }
        auditService.success(AuditAction.ADMIN_STATISTICS_VIEW, "SYSTEM", null, "查看系统使用量概览");
        return overview;
    }

    @Override
    public AdminUserUsageVO userUsage(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(400, "用户 ID 必须大于 0");
        }
        AdminUserUsageVO usage = statisticsMapper.selectUserUsage(userId);
        if (usage == null) {
            throw new BusinessException(404, "用户不存在");
        }
        auditService.success(AuditAction.ADMIN_STATISTICS_VIEW, "USER", userId, "查看用户资源用量");
        return usage;
    }

    @Override
    public PageResult<AdminFileVO> listFiles(Long userId, FileQueryDTO query) {
        validateOptionalUserId(userId);
        String status = trimToNull(query.getStatus());
        if (status == null) {
            status = STATUS_READY;
        }
        String keyword = trimToNull(query.getKeyword());
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = fileAssetMapper.selectCount(Wrappers.<FileAsset>lambdaQuery()
                .eq(userId != null, FileAsset::getUserId, userId)
                .eq(FileAsset::getStatus, status)
                .like(keyword != null, FileAsset::getDisplayName, keyword));
        List<AdminFileVO> records = fileAssetMapper.selectList(Wrappers.<FileAsset>lambdaQuery()
                        .eq(userId != null, FileAsset::getUserId, userId)
                        .eq(FileAsset::getStatus, status)
                        .like(keyword != null, FileAsset::getDisplayName, keyword)
                        .orderByDesc(FileAsset::getCreateTime)
                        .last("LIMIT " + query.getSize() + " OFFSET " + offset))
                .stream().map(AdminFileVO::from).toList();

        PageResult<AdminFileVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total == null ? 0 : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public AdminFileVO fileDetail(Long fileId) {
        FileAsset asset = requireFile(fileId);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId, "查看文件元数据");
        return AdminFileVO.from(asset);
    }

    @Override
    public AdminFileVO renameFile(Long fileId, String displayName) {
        FileAsset asset = requireFile(fileId);
        fileService.rename(asset.getUserId(), fileId, displayName);
        auditService.success(AuditAction.ADMIN_FILE_RENAME, "FILE", fileId, "管理员重命名用户文件");
        return AdminFileVO.from(requireFile(fileId));
    }

    @Override
    public void deleteFile(Long fileId) {
        FileAsset asset = requireFile(fileId);
        fileService.delete(asset.getUserId(), fileId);
        auditService.success(AuditAction.ADMIN_FILE_DELETE, "FILE", fileId, "管理员软删除用户文件");
    }

    @Override
    public FileDownload downloadFile(Long fileId) {
        FileAsset asset = requireFile(fileId);
        FileDownload download = fileService.download(asset.getUserId(), fileId);
        auditService.success(AuditAction.ADMIN_FILE_DOWNLOAD, "FILE", fileId, "下载用户文件当前版本");
        return download;
    }

    @Override
    public List<VersionVO> listVersions(Long fileId) {
        FileAsset asset = requireFile(fileId);
        List<VersionVO> versions = versionService.listVersions(asset.getUserId(), fileId);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId, "查看文件版本列表");
        return versions;
    }

    @Override
    public VersionVO versionDetail(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        VersionVO version = versionService.getVersion(asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE_VERSION", version.getId(),
                "查看文件版本详情");
        return version;
    }

    @Override
    public FileDownload downloadVersion(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        FileDownload download = versionService.downloadVersion(asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_DOWNLOAD, "FILE", fileId,
                "下载用户文件版本 " + versionNo);
        return download;
    }

    @Override
    public DocxStructureVO readDocxStructure(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        DocxStructureVO structure = docxDocumentService.readStructure(asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "读取 DOCX 版本 " + versionNo + " 的结构");
        return structure;
    }

    @Override
    public PdfContentVO readPdfContent(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        PdfContentVO content = pdfDocumentService.readContent(asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "读取 PDF 版本 " + versionNo + " 的原生文本");
        return content;
    }

    @Override
    public PdfPagePreview previewPdfPage(Long fileId, Integer versionNo,
                                         Integer pageNo, Integer dpi) {
        FileAsset asset = requireFile(fileId);
        PdfPagePreview preview = pdfDocumentService.renderPage(
                asset.getUserId(), fileId, versionNo, pageNo, dpi);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "预览 PDF 版本 " + versionNo + " 第 " + pageNo + " 页");
        return preview;
    }

    @Override
    public PagePreviewInfoVO adminPreviewInfo(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        // 以真实所有者身份调用：管理员不需要绕过归属校验，
        // 而是复用同一套「绑定明确版本 + 归属校验」的实现
        PagePreviewInfoVO info = pagePreviewService.describe(asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "查看版本 " + versionNo + " 的页面预览元信息");
        return info;
    }

    @Override
    public PdfPagePreview adminPreviewPage(Long fileId, Integer versionNo, Integer pageNo, Integer dpi) {
        FileAsset asset = requireFile(fileId);
        PdfPagePreview preview = pagePreviewService.renderPage(
                asset.getUserId(), fileId, versionNo, pageNo, dpi);
        // 管理员查看用户文档内容属于敏感操作，必须留痕
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "预览版本 " + versionNo + " 第 " + pageNo + " 页");
        return preview;
    }

    @Override
    public XlsxStructureVO readXlsxStructure(Long fileId, Integer versionNo) {
        FileAsset asset = requireFile(fileId);
        XlsxStructureVO structure = xlsxDocumentService.readStructure(
                asset.getUserId(), fileId, versionNo);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "读取 XLSX 版本 " + versionNo + " 的结构");
        return structure;
    }

    @Override
    public XlsxRangeVO readXlsxRange(Long fileId, Integer versionNo,
                                     String sheetName, String range) {
        FileAsset asset = requireFile(fileId);
        XlsxRangeVO result = xlsxDocumentService.readRange(
                asset.getUserId(), fileId, versionNo, sheetName, range);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "读取 XLSX 版本 " + versionNo + " 的区域 " + range);
        return result;
    }

    @Override
    public XlsxFormulaResultsVO calculateXlsxFormulas(Long fileId, Integer versionNo,
                                                      String sheetName, String range) {
        FileAsset asset = requireFile(fileId);
        XlsxFormulaResultsVO result = xlsxDocumentService.calculateFormulas(
                asset.getUserId(), fileId, versionNo, sheetName, range);
        auditService.success(AuditAction.ADMIN_FILE_VIEW, "FILE", fileId,
                "计算 XLSX 版本 " + versionNo + " 的区域公式 " + range);
        return result;
    }

    @Override
    public PageResult<AdminTaskVO> listTasks(Long userId, TaskQueryDTO query) {
        validateOptionalUserId(userId);
        String status = trimToNull(query.getStatus());
        String taskType = trimToNull(query.getTaskType());
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = taskMapper.selectCount(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(userId != null, ProcessingTask::getUserId, userId)
                .eq(status != null, ProcessingTask::getStatus, status)
                .eq(taskType != null, ProcessingTask::getTaskType, taskType)
                .eq(query.getFileId() != null, ProcessingTask::getFileId, query.getFileId()));
        List<AdminTaskVO> records = taskMapper.selectList(Wrappers.<ProcessingTask>lambdaQuery()
                        .eq(userId != null, ProcessingTask::getUserId, userId)
                        .eq(status != null, ProcessingTask::getStatus, status)
                        .eq(taskType != null, ProcessingTask::getTaskType, taskType)
                        .eq(query.getFileId() != null, ProcessingTask::getFileId, query.getFileId())
                        .orderByDesc(ProcessingTask::getCreateTime)
                        .last("LIMIT " + query.getSize() + " OFFSET " + offset))
                .stream().map(AdminTaskVO::from).toList();

        PageResult<AdminTaskVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total == null ? 0 : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public AdminTaskDetailVO taskDetail(Long taskId) {
        ProcessingTask task = requireTask(taskId);
        TaskDetailVO detail = taskService.detail(task.getUserId(), taskId);
        auditService.success(AuditAction.ADMIN_TASK_VIEW, "TASK", taskId, "查看任务与执行步骤");
        return AdminTaskDetailVO.of(AdminTaskVO.from(task), detail.getSteps());
    }

    @Override
    public AdminTaskVO cancelTask(Long taskId) {
        ProcessingTask task = requireTask(taskId);
        taskService.cancel(task.getUserId(), taskId);
        auditService.success(AuditAction.ADMIN_TASK_CANCEL, "TASK", taskId, "管理员取消用户任务");
        return AdminTaskVO.from(requireTask(taskId));
    }

    @Override
    public AdminTaskVO retryTask(Long taskId) {
        ProcessingTask task = requireTask(taskId);
        TaskVO retried = taskService.retry(task.getUserId(), taskId);
        ProcessingTask created = taskService.findById(retried.getId());
        if (created == null) {
            throw new BusinessException(500, "重试任务已创建但无法读取");
        }
        auditService.success(AuditAction.ADMIN_TASK_RETRY, "TASK", taskId,
                "管理员重试用户任务，新任务 ID=" + created.getId());
        return AdminTaskVO.from(created);
    }

    private FileAsset requireFile(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(400, "文件 ID 不能为空");
        }
        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(404, "文件不存在");
        }
        return asset;
    }

    private ProcessingTask requireTask(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "任务 ID 不能为空");
        }
        ProcessingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    private static void validateOptionalUserId(Long userId) {
        if (userId != null && userId <= 0) {
            throw new BusinessException(400, "用户 ID 必须大于 0");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
