package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentExportService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.FileDownload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 导出实现（TODO 4.3）。
 *
 * <p>导出与预览共用同一个渲染后端，因此不会出现「预览正常但导出报不支持」
 * 这种让用户困惑的组合。
 *
 * <p>一个容易忽略但很关键的点：转换后的下载响应必须把
 * {@code contentType} 与文件名后缀一并改成新的格式。只改文件名不改 MIME，
 * 浏览器会按原类型处理，表现为「下载下来的文件打不开」或直接被当成
 * 未知类型 —— 而服务端看起来一切正常。
 */
@Slf4j
@Service
public class DocumentExportServiceImpl implements DocumentExportService {

    private static final String PDF_EXTENSION = "pdf";
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    /**
     * 可转换为 PDF 的扩展名。
     *
     * <p>白名单而不是黑名单：新格式在验证过之前不应自动获得导出能力，
     * 否则会把「渲染后端不支持」暴露成服务端错误。
     */
    private static final java.util.Set<String> CONVERTIBLE_EXTENSIONS =
            java.util.Set.of("docx", "xlsx", "pptx", "odt", "ods", "odp", "doc", "xls", "ppt");

    private final DocumentVersionService versionService;
    private final OfficeRenderService renderService;

    public DocumentExportServiceImpl(DocumentVersionService versionService,
                                     OfficeRenderService renderService) {
        this.versionService = versionService;
        this.renderService = renderService;
    }

    @Override
    public FileDownload exportPdf(Long userId, Long fileId, Integer versionNo) {
        // 归属校验与版本解析走下载路径，与预览、比较保持一致
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        FileAsset asset = version.asset();

        if (isPdf(asset)) {
            // 已经是 PDF：直接返回原内容。
            // 再转换一次不仅浪费，还可能因二次渲染产生与用户所见不一致的结果
            log.info("导出版本本身即 PDF，直接返回, fileId={}, versionNo={}", fileId, versionNo);
            return version;
        }

        if (!renderService.available()) {
            throw new BusinessException(503,
                    "当前部署未接入 Office 渲染后端，无法导出 PDF；"
                            + renderService.configurationHint());
        }

        Path workDirectory = null;
        try {
            String extension = normalizeExtension(asset);
            if (!CONVERTIBLE_EXTENSIONS.contains(extension)) {
                // 只放行已知可转换的格式。若不在这里拒绝，未知格式会被送进渲染后端，
                // 从中途失败并可能表现为 500 —— 那是服务端的错，而实际上
                // 是「这种格式不支持导出」，属于调用方可以自行修正的问题
                throw new BusinessException(400,
                        "该版本的格式（" + (extension.isEmpty() ? "未知" : extension)
                                + "）不支持导出 PDF；目前支持 "
                                + String.join("、", CONVERTIBLE_EXTENSIONS));
            }

            workDirectory = Files.createTempDirectory("export-pdf-");
            Path sourceFile = workDirectory.resolve("input." + extension);
            try (InputStream input = version.resource().getInputStream()) {
                Files.copy(input, sourceFile);
            }

            // convert 会自行创建输出目录（与 render 相反），见 OfficeRenderService 说明
            Path pdf = renderService.toPdf(sourceFile, extension, workDirectory.resolve("out"));
            byte[] content = Files.readAllBytes(pdf);

            log.info("导出版本为 PDF, fileId={}, versionNo={}, bytes={}",
                    fileId, versionNo, content.length);
            return pdfDownload(asset, content);
        } catch (IOException e) {
            log.warn("导出 PDF 失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(500, "导出 PDF 失败，请稍后重试");
        } finally {
            cleanupQuietly(workDirectory);
        }
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 组装 PDF 下载载荷。
     *
     * <p>元数据快照必须替换掉原有的 content type 与扩展名：
     * 下载响应头由它生成，若仍带着 docx 的类型，浏览器会按 Word 文档处理
     * 一个实际是 PDF 的字节流。
     */
    private static FileDownload pdfDownload(FileAsset source, byte[] content) {
        FileAsset snapshot = new FileAsset();
        snapshot.setId(source.getId());
        snapshot.setUserId(source.getUserId());
        snapshot.setDisplayName(source.getDisplayName());
        snapshot.setOriginalName(source.getOriginalName());
        snapshot.setContentType(PDF_CONTENT_TYPE);
        snapshot.setExtension(PDF_EXTENSION);
        snapshot.setSizeBytes((long) content.length);
        snapshot.setSha256(source.getSha256());
        snapshot.setStatus(source.getStatus());
        snapshot.setSource(source.getSource());
        snapshot.setLatestVersion(source.getLatestVersion());

        String fileName = pdfFileName(source);
        return new FileDownload(snapshot, new ByteArrayResource(content), content.length,
                PDF_CONTENT_TYPE, fileName);
    }

    private static boolean isPdf(FileAsset asset) {
        String contentType = asset.getContentType();
        if (contentType != null && PDF_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            return true;
        }
        return PDF_EXTENSION.equalsIgnoreCase(asset.getExtension());
    }

    private static String normalizeExtension(FileAsset asset) {
        String extension = asset.getExtension();
        if (extension != null && !extension.isBlank()) {
            return extension.trim().toLowerCase(Locale.ROOT);
        }
        String contentType = asset.getContentType();
        if (contentType == null) {
            return "";
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("wordprocessingml")) {
            return "docx";
        }
        if (lower.contains("spreadsheetml")) {
            return "xlsx";
        }
        if (lower.contains("presentationml")) {
            return "pptx";
        }
        if (lower.contains("pdf")) {
            return PDF_EXTENSION;
        }
        return "";
    }

    /**
     * 导出文件名：把原扩展名替换为 pdf。
     *
     * <p>保留原基础名是有意的 —— 用户要对照原文件与导出件，
     * 文件名相近才容易配对；版本号由调用方在页面上下文中体现。
     */
    private static String pdfFileName(FileAsset asset) {
        String name = asset.getDisplayName();
        if (name == null || name.isBlank()) {
            return "export.pdf";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return (base.isBlank() ? "export" : base) + ".pdf";
    }

    private static void cleanupQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 交给过期临时文件清理任务回收
                }
            });
        } catch (IOException e) {
            log.debug("导出临时目录清理失败: {}", directory, e);
        }
    }
}
