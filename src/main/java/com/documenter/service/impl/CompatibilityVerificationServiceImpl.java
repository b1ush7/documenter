package com.documenter.service.impl;

import com.documenter.configuration.PreviewProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.CompatibilityVerificationService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.DocxCompatibilityReportVO;
import com.documenter.vo.DocxCompatibilityReportVO.Issue;
import com.documenter.vo.DocxCompatibilityReportVO.Severity;
import com.documenter.vo.DocumentRenderManifest;
import com.documenter.vo.FileDownload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * DOCX 应用级兼容性验收实现（TODO 4.1）。
 *
 * <p>处理链：
 * <pre>
 *   校验版本与归属（复用下载路径，与预览/下载同一套鉴权）
 *     → 把该版本落到临时文件
 *     → 交给 Office 渲染后端真正打开并排版
 *     → 检查页数、缺失字体、页面边缘裁切
 *     → 汇总报告（有问题也不抛异常，而是如实列出）
 * </pre>
 *
 * <p>为什么「有问题不抛异常」：验收的目的是收集证据并给出结论。
 * 一遇到问题就中断，调用方只能看到第一个问题，无法判断整体质量。
 * 只有「根本无法渲染」才是硬失败。
 */
@Slf4j
@Service
public class CompatibilityVerificationServiceImpl implements CompatibilityVerificationService {

    private static final String DOCX_EXTENSION = "docx";

    private final DocumentVersionService versionService;
    private final OfficeRenderService renderService;
    private final PreviewProperties properties;

    public CompatibilityVerificationServiceImpl(DocumentVersionService versionService,
                                                OfficeRenderService renderService,
                                                PreviewProperties properties) {
        this.versionService = versionService;
        this.renderService = renderService;
        this.properties = properties;
    }

    @Override
    public DocxCompatibilityReportVO verify(Long userId, Long fileId, Integer versionNo) {
        // 与预览、下载共用同一条鉴权与版本解析路径
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);

        if (!renderService.available()) {
            // 未接入渲染后端时如实说明，而不是假装通过
            throw new BusinessException(503,
                    "当前部署未接入 Office 渲染后端，无法执行兼容性验收；"
                            + renderService.configurationHint());
        }

        FileAsset asset = version.asset();
        if (!isDocx(asset)) {
            throw new BusinessException(400, "兼容性验收目前仅支持 DOCX，当前版本是 "
                    + describeType(asset));
        }

        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("docx-compat-");
            Path sourceFile = workDirectory.resolve("input.docx");
            try (InputStream input = version.resource().getInputStream()) {
                Files.copy(input, sourceFile);
            }

            // 注意：kit 会自行创建输出目录，传入已存在的目录会报 EEXIST。
            // 因此这里只给出路径，不预先创建。
            Path outputDirectory = workDirectory.resolve("render");

            // 页数上限：兼容性验收不需要把 500 页文档全部渲染，那会把流程拖到不可用
            int maxPages = properties.getCompatibilityMaxPages();
            List<Integer> pages = new ArrayList<>();
            for (int page = 1; page <= maxPages; page++) {
                pages.add(page);
            }

            DocumentRenderManifest manifest = renderService.render(sourceFile, DOCX_EXTENSION,
                    outputDirectory, pages, properties.getCompatibilityDpi());

            return buildReport(fileId, versionNo, manifest, maxPages);
        } catch (IOException e) {
            log.warn("兼容性验收读取版本内容失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(500, "读取版本内容失败");
        } finally {
            cleanupQuietly(workDirectory);
        }
    }

    // ---------------------------------------------------------------- 报告

    private DocxCompatibilityReportVO buildReport(Long fileId, Integer versionNo,
                                                  DocumentRenderManifest manifest, int maxPages) {
        List<Issue> issues = new ArrayList<>();

        // 1) 能否排出内容
        if (manifest.pageCount() <= 0) {
            issues.add(new Issue(Severity.ERROR, "NO_PAGES",
                    "渲染后端未排出任何页面，文档可以解析但无法正常显示内容"));
        }

        if (manifest.pages().isEmpty() && manifest.pageCount() > 0) {
            issues.add(new Issue(Severity.ERROR, "NO_RENDERED_PAGE",
                    "未产生任何页面图片，渲染流程异常"));
        }

        // 2) 页数超出检查上限时如实告知，避免让调用方误以为整篇都验收过了
        if (manifest.pageCount() > maxPages) {
            issues.add(new Issue(Severity.WARNING, "PAGES_TRUNCATED",
                    "文档共 " + manifest.pageCount() + " 页，本次仅检查前 " + maxPages
                            + " 页（上限由 res.preview.compatibility-max-pages 控制）"));
        }

        // 3) 缺失字体：版式偏差的主要来源
        if (!manifest.missingFonts().isEmpty()) {
            issues.add(new Issue(Severity.WARNING, "MISSING_FONTS",
                    "以下字体缺失并被替换，版式可能与预期不同：" + String.join("、", manifest.missingFonts())
                            + "。建议在文档中使用通用字体，或在渲染环境安装对应字体"));
        }

        // 4) 页面边缘裁切检测
        int marginPixels = properties.getClippingMarginPixels();
        if (marginPixels > 0) {
            for (DocumentRenderManifest.Page page : manifest.pages()) {
                if (touchesPageBorder(page.path(), marginPixels)) {
                    issues.add(new Issue(Severity.WARNING, "CONTENT_NEAR_BORDER",
                            "第 " + page.page() + " 页在页面边缘 " + marginPixels
                                    + " 像素内存在内容，可能被裁切或超出可打印区域"));
                }
            }
        }

        boolean compatible = issues.stream().noneMatch(issue -> Severity.ERROR == issue.severity());

        log.info("DOCX 兼容性验收完成, fileId={}, versionNo={}, compatible={}, pages={}, "
                        + "rendered={}, missingFonts={}, issues={}",
                fileId, versionNo, compatible, manifest.pageCount(), manifest.pages().size(),
                manifest.missingFonts().size(), issues.size());

        return new DocxCompatibilityReportVO(fileId, versionNo, compatible,
                renderService.backendName(), manifest.pageCount(), manifest.pages().size(),
                manifest.missingFonts(), issues);
    }

    /**
     * 判断页面图像是否在边缘附近存在非白像素。
     *
     * <p>只检查最外圈若干行/列：正文通常不会贴到纸张边缘（页边距会挡住），
     * 因此边缘出现墨迹大概率意味着内容溢出了可打印区域。
     *
     * <p>这是启发式判断，所以只报 WARNING 而不作为验收失败条件 ——
     * 有些文档确实会故意做满版设计。
     */
    private boolean touchesPageBorder(String imagePath, int marginPixels) {
        try {
            BufferedImage image = ImageIO.read(Path.of(imagePath).toFile());
            if (image == null) {
                return false;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            // 边距不能超过图像尺寸的一半，否则整页都在「边缘」范围内，判断失去意义
            int margin = Math.min(marginPixels, Math.min(width, height) / 2);

            for (int x = 0; x < width; x++) {
                for (int offset = 0; offset < margin; offset++) {
                    if (isInk(image, x, offset) || isInk(image, x, height - 1 - offset)) {
                        return true;
                    }
                }
            }
            for (int y = 0; y < height; y++) {
                for (int offset = 0; offset < margin; offset++) {
                    if (isInk(image, offset, y) || isInk(image, width - 1 - offset, y)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            // 边缘检测是附加检查，读图失败不应影响验收结论
            log.debug("页面边缘检测跳过: {}", imagePath, e);
            return false;
        }
    }

    /** 判定某像素是否为「墨迹」：亮度明显低于白底。 */
    private static boolean isInk(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        // 阈值取 245：抗锯齿产生的极浅灰仍算白底，避免整页误报
        return r < 245 || g < 245 || b < 245;
    }

    // ---------------------------------------------------------------- 辅助

    private static boolean isDocx(FileAsset asset) {
        if (DOCX_EXTENSION.equalsIgnoreCase(asset.getExtension())) {
            return true;
        }
        String contentType = asset.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("wordprocessingml");
    }

    private static String describeType(FileAsset asset) {
        String extension = asset.getExtension();
        return extension == null || extension.isBlank() ? "未知类型" : extension.toUpperCase(Locale.ROOT);
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
            log.debug("兼容性验收临时目录清理失败: {}", directory, e);
        }
    }
}
