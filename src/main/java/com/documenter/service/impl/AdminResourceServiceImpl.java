package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.service.AdminResourceService;
import com.documenter.service.AuditService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.TaskService;
import com.documenter.vo.AdminFileVO;
import com.documenter.vo.AdminTaskDetailVO;
import com.documenter.vo.AdminTaskVO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.VersionVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 管理员跨用户资源查询实现。 */
@Service
public class AdminResourceServiceImpl implements AdminResourceService {

    private static final String STATUS_READY = "READY";

    private final FileAssetMapper fileAssetMapper;
    private final ProcessingTaskMapper taskMapper;
    private final FileService fileService;
    private final DocumentVersionService versionService;
    private final DocxDocumentService docxDocumentService;
    private final TaskService taskService;
    private final AuditService auditService;

    public AdminResourceServiceImpl(FileAssetMapper fileAssetMapper,
                                    ProcessingTaskMapper taskMapper,
                                    FileService fileService,
                                    DocumentVersionService versionService,
                                    DocxDocumentService docxDocumentService,
                                    TaskService taskService,
                                    AuditService auditService) {
        this.fileAssetMapper = fileAssetMapper;
        this.taskMapper = taskMapper;
        this.fileService = fileService;
        this.versionService = versionService;
        this.docxDocumentService = docxDocumentService;
        this.taskService = taskService;
        this.auditService = auditService;
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
