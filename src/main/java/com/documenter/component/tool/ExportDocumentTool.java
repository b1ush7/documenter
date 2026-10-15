package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import com.documenter.service.DocumentExportService;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.FileDownload;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 导出文档（TODO 4.2「封装受控工具：导出文档」）。
 *
 * <p>只报告「已可导出」与文件名，<b>不把导出的字节内容交给模型</b>。
 * 理由与预览工具相同：二进制大对象对模型无意义，
 * 而真正需要文件的是用户，由前端按返回的路径去下载。
 *
 * <p>这个工具会真实触发转换（DOCX → PDF），因此是有开销的。
 * 它被标记为 {@code mutating()} 是因为在「提交前确认」模式下，
 * 不应在用户还没确认修改时就把资源花在导出上。
 */
@Component
public class ExportDocumentTool extends AbstractAiTool {

    public static final String NAME = "export_document";

    private final DocumentExportService exportService;

    public ExportDocumentTool(DocumentExportService exportService) {
        this.exportService = exportService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把当前版本导出为 PDF，返回可供用户下载的文件名。"
                + "导出会真实执行格式转换，请只在用户明确要求导出时调用。"
                + "本工具不修改文档内容，不产生新版本。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of();
    }

    @Override
    public boolean mutating() {
        // 不产生版本，但有转换开销；在确认模式下不预先执行
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        if (context.fileId() == null) {
            throw new BusinessException(422, "当前对话没有关联文档，无法导出");
        }

        FileDownload download = exportService.exportPdf(
                context.userId(), context.fileId(), context.versionNo());

        // 只回传文件名与大小，不回传内容
        String fileName = download.downloadFileName();
        long size = download.contentLength();
        return ToolResult.withArtifact(
                "已生成 PDF：" + fileName + "（" + formatSize(size) + "），用户可在版本列表下载",
                fileName);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " 字节";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
