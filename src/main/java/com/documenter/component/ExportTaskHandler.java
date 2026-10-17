package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.ChangeType;
import com.documenter.enums.TaskStepStatus;
import com.documenter.enums.TaskType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentExportService;
import com.documenter.service.FileStorage;
import com.documenter.service.TaskExecutionContext;
import com.documenter.service.TaskHandler;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 将明确文档版本导出为 PDF 新版本。 */
@Component
public class ExportTaskHandler implements TaskHandler {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final DocumentExportService exportService;
    private final FileStorage storage;
    private final VersionWriter versionWriter;
    private final StorageProperties properties;

    public ExportTaskHandler(DocumentExportService exportService, FileStorage storage,
                             VersionWriter versionWriter, StorageProperties properties) {
        this.exportService = exportService;
        this.storage = storage;
        this.versionWriter = versionWriter;
        this.properties = properties;
    }

    @Override
    public void handle(ProcessingTask task, TaskExecutionContext context) {
        requireBoundVersion(task);
        context.startStep("导出版本", "源版本 v" + task.getInputVersion());
        context.reportProgress(10);
        context.assertNotCancelled();
        var pdf = exportService.exportPdf(task.getUserId(), task.getFileId(), task.getInputVersion());
        byte[] bytes;
        try (var input = pdf.resource().getInputStream()) {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "读取导出的 PDF 失败，请稍后重试");
        }
        if (bytes.length == 0) {
            throw new BusinessException(422, "导出服务返回了空 PDF");
        }
        if (bytes.length > properties.getUpload().getMaxSizeBytes()) {
            throw new BusinessException(413, "导出的 PDF 超过文件大小限制");
        }
        context.assertNotCancelled();
        context.finishStep(TaskStepStatus.SUCCEEDED, "PDF 已生成", null);

        context.startStep("保存 PDF 版本", "写入版本历史");
        context.reportProgress(70);
        String storageKey = storage.store(bytes, "pdf");
        DocumentVersion created = null;
        try {
            created = versionWriter.appendVersion(task.getFileId(), task.getUserId(),
                    new VersionWriter.VersionContent(storageKey, bytes.length,
                            Digests.sha256Hex(bytes), PDF_CONTENT_TYPE, "pdf"),
                    ChangeType.CONVERT, "异步导出 PDF",
                    "由版本 " + task.getInputVersion() + " 导出 PDF",
                    task.getInputVersion(), task.getId(), context.attemptNo());
            if (!storageKey.equals(created.getStorageKey())) {
                storage.delete(storageKey);
            }
            context.completeWithVersion(created.getVersionNo());
            context.finishStep(TaskStepStatus.SUCCEEDED,
                    "已生成 PDF 版本 v" + created.getVersionNo(), null);
            context.reportProgress(100);
        } catch (RuntimeException e) {
            if (created == null || !storageKey.equals(created.getStorageKey())) {
                storage.delete(storageKey);
            }
            throw e;
        }
    }

    @Override
    public String name() {
        return "ExportTaskHandler";
    }

    @Override
    public boolean supports(TaskType type) {
        return type == TaskType.EXPORT;
    }

    private static void requireBoundVersion(ProcessingTask task) {
        if (task.getFileId() == null || task.getInputVersion() == null
                || task.getInputVersion() < 1) {
            throw new BusinessException(400, "导出任务必须指定文件和明确输入版本");
        }
    }
}
