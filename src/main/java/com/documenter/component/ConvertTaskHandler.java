package com.documenter.component;

import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.PdfConversionMode;
import com.documenter.enums.TaskStepStatus;
import com.documenter.enums.TaskType;
import com.documenter.exception.BusinessException;
import com.documenter.service.PdfToDocxService;
import com.documenter.service.TaskExecutionContext;
import com.documenter.service.TaskHandler;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 执行 PDF 转可编辑 DOCX 的真实异步任务。 */
@Component
public class ConvertTaskHandler implements TaskHandler {

    private final PdfToDocxService converter;

    public ConvertTaskHandler(PdfToDocxService converter) {
        this.converter = converter;
    }

    @Override
    public void handle(ProcessingTask task, TaskExecutionContext context) {
        requireBoundVersion(task);
        context.startStep("读取 PDF", "源版本 v" + task.getInputVersion());
        context.reportProgress(10);
        context.assertNotCancelled();
        context.finishStep(TaskStepStatus.SUCCEEDED, "输入版本已确认", null);

        context.startStep("重建可编辑 DOCX", "提取正文、字体和基础位置");
        PdfConversionMode mode = conversionMode(task.getInstruction());
        ConvertPdfToDocxDTO request = new ConvertPdfToDocxDTO();
        request.setMode(mode);
        request.setInstruction(task.getInstruction());
        context.reportProgress(35);
        var version = converter.convert(task.getUserId(), task.getFileId(), task.getInputVersion(),
                request, task.getId(), context.attemptNo());
        context.assertNotCancelled();
        context.finishStep(TaskStepStatus.SUCCEEDED,
                "已生成 DOCX 版本 v" + version.getVersionNo(), null);
        context.completeWithVersion(version.getVersionNo());
        context.reportProgress(100);
    }

    @Override
    public String name() {
        return "ConvertTaskHandler";
    }

    @Override
    public boolean supports(TaskType type) {
        return type == TaskType.CONVERT;
    }

    static PdfConversionMode conversionMode(String instruction) {
        String normalized = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        return normalized.contains("版式") || normalized.contains("layout")
                ? PdfConversionMode.LAYOUT : PdfConversionMode.EDITABLE;
    }

    private static void requireBoundVersion(ProcessingTask task) {
        if (task.getFileId() == null || task.getInputVersion() == null
                || task.getInputVersion() < 1) {
            throw new BusinessException(400, "转换任务必须指定文件和明确输入版本");
        }
    }
}
