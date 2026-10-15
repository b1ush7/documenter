package com.documenter.service.impl;

import com.documenter.configuration.PreviewProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OfficeRenderService;
import com.documenter.service.PagePreviewService;
import com.documenter.vo.DocumentRenderManifest;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PdfPagePreview;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 页面版式预览实现（TODO 3.3）。
 *
 * <p>处理链：
 * <pre>
 *   校验版本与归属（复用下载路径）
 *     → 按内容类型分流
 *         PDF      → 直接渲染
 *         图片     → 单页，直接回传
 *         DOCX/XLSX → 外部转换器转 PDF 再渲染
 *     → 渲染指定页为 PNG
 *     → 落缓存（按内容摘要 + 页号 + DPI 命名）
 * </pre>
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>鉴权与下载同源</b>：调用 {@link DocumentVersionService#downloadVersion}，
 *       因此归属校验、版本存在性、内容丢失检测都只有一套实现，
 *       不会出现「预览接口自己写了一遍鉴权、然后漏了一种情况」。</li>
 *   <li><b>缓存键用内容摘要而不是 fileId+versionNo</b>：版本内容一旦变化，
 *       摘要随之变化，缓存自然失效，不存在「版本号复用导致预览显示旧内容」的风险。</li>
 * </ul>
 */
@Slf4j
@Service
public class PagePreviewServiceImpl implements PagePreviewService {

    private static final String PDF_EXTENSION = "pdf";
    private static final String ENGINE_NATIVE = "native";
    private static final int MIN_DPI = 60;
    private static final int FIRST_PAGE = 1;

    /** 可直接按图片回传的扩展名。 */
    private static final java.util.Set<String> IMAGE_EXTENSIONS =
            java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private final DocumentVersionService versionService;
    private final OfficeRenderService renderService;
    private final PreviewProperties previewProperties;
    private final StorageProperties storageProperties;

    public PagePreviewServiceImpl(DocumentVersionService versionService,
                                  OfficeRenderService renderService,
                                  PreviewProperties previewProperties,
                                  StorageProperties storageProperties) {
        this.versionService = versionService;
        this.renderService = renderService;
        this.previewProperties = previewProperties;
        this.storageProperties = storageProperties;
    }

    // ------------------------------------------------------------ 元信息

    @Override
    public PagePreviewInfoVO describe(Long userId, Long fileId, Integer versionNo) {
        // 归属校验与版本存在性检查都在这里完成，与下载走同一条路径
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        int dpi = resolveDpi(null);
        String extension = normalizeExtension(version.asset());

        if (PDF_EXTENSION.equals(extension)) {
            int pages = countPdfPages(version);
            return new PagePreviewInfoVO(fileId, versionNo, version.contentType(), extension,
                    pages, false, true, ENGINE_NATIVE, dpi, null);
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return new PagePreviewInfoVO(fileId, versionNo, version.contentType(), extension,
                    1, false, true, ENGINE_NATIVE, dpi, null);
        }

        // DOCX / XLSX 需要外部 Office 渲染后端
        if (!renderService.available()) {
            return new PagePreviewInfoVO(fileId, versionNo, version.contentType(), extension,
                    null, true, false, renderService.backendName(), dpi,
                    renderService.configurationHint());
        }
        // 后端可用时给出页数需要真正渲染一次；这里返回空页数 + 提示，
        // 由前端在首次请求页面图片时再触发渲染，避免「只看一眼元信息就把大文档排一遍版」。
        return new PagePreviewInfoVO(fileId, versionNo, version.contentType(), extension,
                null, true, true, renderService.backendName(), dpi,
                "页数将在首次渲染时确定");
    }

    // ------------------------------------------------------------ 渲染

    @Override
    public PdfPagePreview renderPage(Long userId, Long fileId, Integer versionNo,
                                     Integer pageNo, Integer dpi) {
        if (pageNo == null || pageNo < FIRST_PAGE) {
            throw new BusinessException(400, "页码必须大于 0");
        }
        int actualDpi = resolveDpi(dpi);

        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        String extension = normalizeExtension(version.asset());

        // 图片只有一页，直接回传原图，无需栅格化（保留透明通道等原始信息）
        if (IMAGE_EXTENSIONS.contains(extension)) {
            if (pageNo != FIRST_PAGE) {
                throw new BusinessException(400, "图片只有 1 页");
            }
            return renderImageDirectly(version, actualDpi);
        }

        // 缓存命中直接返回，避免重复渲染（Office 渲染是最重的一步）
        String cacheKey = cacheKey(version, pageNo, actualDpi);
        byte[] cached = readCache(cacheKey);
        if (cached != null) {
            return buildPreview(cached, pageNo, actualDpi);
        }

        byte[] png = PDF_EXTENSION.equals(extension)
                ? renderPdfPage(readAll(version), pageNo, actualDpi, version)
                : renderViaOffice(version, extension, pageNo, actualDpi);
        writeCache(cacheKey, png);
        return buildPreview(png, pageNo, actualDpi);
    }

    /**
     * 通过 Office 渲染后端直接渲染指定页。
     *
     * <p>不经过「先转 PDF 再栅格化」两步：kit 本身就能按页输出图片，
     * 少一步转换既更快，也少一次可能引入偏差的中间格式。
     */
    private byte[] renderViaOffice(FileDownload version, String extension,
                                   int pageNo, int dpi) {
        if (!renderService.available()) {
            throw new BusinessException(503,
                    "当前部署未接入 Office 渲染后端，无法生成 " + extension.toUpperCase()
                            + " 的版式预览；" + renderService.configurationHint());
        }

        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("page-preview-");
            Path sourceFile = workDirectory.resolve("input." + extension);
            try (InputStream input = version.resource().getInputStream()) {
                Files.copy(input, sourceFile);
            }

            // 只渲染请求的那一页，避免为看一页而排整本文档
            DocumentRenderManifest manifest = renderService.render(sourceFile, extension,
                    workDirectory.resolve("render"), List.of(pageNo), dpi);

            if (manifest.pages().isEmpty()) {
                // 页码越界时后端不会产出该页，此处按 404 处理与 PDF 路径保持一致
                throw new BusinessException(404,
                        "第 " + pageNo + " 页不存在或无法渲染（该版本共 "
                                + manifest.pageCount() + " 页）");
            }
            return Files.readAllBytes(Path.of(manifest.pages().get(0).path()));
        } catch (IOException e) {
            log.warn("Office 渲染页面失败, fileId={}, versionNo={}, pageNo={}",
                    version.asset().getId(), version.asset().getLatestVersion(), pageNo, e);
            throw new BusinessException(500, "页面预览渲染失败");
        } finally {
            cleanupQuietly(workDirectory);
        }
    }

    /** 用 PDFBox 把指定页渲染为 PNG，并校验像素上限。 */
    private byte[] renderPdfPage(byte[] pdfBytes, int pageNo, int dpi, FileDownload version) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (pageNo > document.getNumberOfPages()) {
                throw new BusinessException(404,
                        "页码超出范围（该版本共 " + document.getNumberOfPages() + " 页）");
            }
            BufferedImage image = new PDFRenderer(document)
                    .renderImageWithDPI(pageNo - FIRST_PAGE, dpi, ImageType.RGB);
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > previewProperties.getMaxPagePixels()) {
                // 超大页面在低内存机器上会直接 OOM，宁可拒绝并提示降低 DPI
                throw new BusinessException(413,
                        "该页在 " + dpi + " DPI 下像素过多，请降低 dpi 参数后重试");
            }
            return encodePng(image);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.warn("页面渲染失败, fileId={}", version.asset().getId(), e);
            throw new BusinessException(500, "页面预览渲染失败，文档可能已损坏");
        }
    }

    /** 图片版本：直接回传原字节，但做像素上限检查。 */
    private PdfPagePreview renderImageDirectly(FileDownload version, int dpi) {
        byte[] content = readAll(version);
        try (InputStream input = new ByteArrayInputStream(content)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new BusinessException(415, "无法解析该图片，无法生成预览");
            }
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > previewProperties.getMaxPagePixels()) {
                throw new BusinessException(413, "图片像素数超过预览上限");
            }
            String contentType = version.contentType();
            String fileName = version.downloadFileName() == null
                    ? "preview" : version.downloadFileName();
            return new PdfPagePreview(content, FIRST_PAGE, dpi,
                    image.getWidth(), image.getHeight(), fileName);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(415, "无法解析该图片，无法生成预览");
        }
    }

    // ------------------------------------------------------------ 辅助

    private PdfPagePreview buildPreview(byte[] png, int pageNo, int dpi) {
        // 读回尺寸用于响应头，前端可据此预留高度避免布局跳动
        int width = 0;
        int height = 0;
        try (InputStream input = new ByteArrayInputStream(png)) {
            BufferedImage image = ImageIO.read(input);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException ignored) {
            // 尺寸仅用于展示，读不到不影响预览本身
        }
        String fileName = "page-" + pageNo + ".png";
        return new PdfPagePreview(png, pageNo, dpi, width, height, fileName);
    }

    private int countPdfPages(FileDownload version) {
        return countPdfPages(readAll(version));
    }

    private int countPdfPages(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new BusinessException(422, "文档不包含可预览页面");
            }
            return pages;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(422, "无法解析该文档，可能已损坏");
        }
    }

    private byte[] readAll(FileDownload version) {
        try (InputStream input = version.resource().getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "读取版本内容失败");
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    private int resolveDpi(Integer requested) {
        int dpi = requested == null ? previewProperties.getDefaultDpi() : requested;
        if (dpi < MIN_DPI || dpi > previewProperties.getMaxDpi()) {
            throw new BusinessException(400,
                    "dpi 必须在 " + MIN_DPI + " 到 " + previewProperties.getMaxDpi() + " 之间");
        }
        return dpi;
    }

    /** 扩展名优先取版本自身记录，缺失时从内容类型推断。 */
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
        if (lower.contains("pdf")) {
            return PDF_EXTENSION;
        }
        if (lower.contains("png")) {
            return "png";
        }
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpg";
        }
        if (lower.contains("wordprocessingml")) {
            return "docx";
        }
        if (lower.contains("spreadsheetml")) {
            return "xlsx";
        }
        return "";
    }

    private static String baseName(FileAsset asset) {
        String name = asset.getDisplayName();
        if (name == null || name.isBlank()) {
            return "document";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "document" : base;
    }

    /** 清理临时目录；失败只记日志，交给过期临时文件清理任务回收。 */
    private static void cleanupQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 由清理任务回收
                }
            });
        } catch (IOException e) {
            log.debug("预览临时目录清理失败: {}", directory, e);
        }
    }

    // ------------------------------------------------------------ 缓存

    /**
     * 缓存键：内容摘要 + 页号 + DPI。
     *
     * <p>刻意不使用 fileId + versionNo：内容一旦变化摘要随之变化，缓存自然失效，
     * 不存在「版本号复用导致预览显示旧内容」的风险。
     */
    private String cacheKey(FileDownload version, int pageNo, int dpi) {
        String digest = version.asset().getSha256();
        if (digest == null || digest.isBlank()) {
            digest = "unknown";
        }
        // 非 PDF 版本会被转换成 PDF，转换结果与源内容一一对应，因此摘要同样有效
        return digest + "-p" + pageNo + "-d" + dpi + ".png";
    }

    private byte[] readCache(String key) {
        if (!previewProperties.isCacheEnabled()) {
            return null;
        }
        Path file = cacheDirectory().resolve(key);
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            log.debug("预览缓存读取失败: {}", file, e);
            return null;
        }
    }

    private void writeCache(String key, byte[] content) {
        if (!previewProperties.isCacheEnabled()) {
            return;
        }
        try {
            Path directory = Files.createDirectories(cacheDirectory());
            Files.write(directory.resolve(key), content);
        } catch (IOException e) {
            // 缓存写失败只影响性能，不能影响预览本身
            log.debug("预览缓存写入失败, key={}", key, e);
        }
    }

    /**
     * 缓存目录放在存储的<b>临时目录</b>下，而不是正式目录。
     *
     * <p>好处：既有的 {@code TemporaryFileCleaner} 会按保留策略自动回收，
     * 不需要为预览缓存再写一套清理逻辑。
     */
    private Path cacheDirectory() {
        return Path.of(storageProperties.getLocal().getTemp())
                .toAbsolutePath()
                .normalize()
                .resolve(previewProperties.getCacheDirectory());
    }

    /** 供诊断与测试使用：当前生效的渲染后端名。 */
    public String converterEngine() {
        return renderService.backendName();
    }
}
