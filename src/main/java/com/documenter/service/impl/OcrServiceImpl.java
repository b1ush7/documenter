package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.OcrRequestDTO;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OcrEngine;
import com.documenter.service.OcrService;
import com.documenter.util.OcrLanguageUtil;
import com.documenter.vo.FileDownload;
import com.documenter.vo.OcrResultVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PDF/图片 OCR 编排：原生文字优先、扫描页渲染、坐标与置信度统一输出。 */
@Slf4j
@Service
public class OcrServiceImpl implements OcrService {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg");

    private final DocumentVersionService versions;
    private final OcrEngine engine;
    private final OcrProperties properties;
    private final StorageProperties storageProperties;

    public OcrServiceImpl(DocumentVersionService versions, OcrEngine engine,
                          OcrProperties properties, StorageProperties storageProperties) {
        this.versions = versions;
        this.engine = engine;
        this.properties = properties;
        this.storageProperties = storageProperties;
    }

    @Override
    public OcrResultVO recognize(Long userId, Long fileId, Integer versionNo,
                                 OcrRequestDTO request) {
        if (versionNo == null || versionNo < 1) {
            throw new BusinessException(400, "OCR 源版本号必须为正数");
        }
        OcrRequestDTO safeRequest = request == null ? new OcrRequestDTO() : request;
        String language = OcrLanguageUtil.resolve(safeRequest.getLanguage(), properties);
        List<Integer> requestedPages = validateRequestedPages(safeRequest.getPages());

        FileDownload source = versions.downloadVersion(userId, fileId, versionNo);
        String extension = extension(source.asset());
        byte[] content = readAll(source);
        if ("pdf".equals(extension)) {
            return recognizePdf(fileId, versionNo, content, language,
                    requestedPages, safeRequest.getDpi(), safeRequest.getRegion(),
                    safeRequest.isEnhance());
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return recognizeImage(fileId, versionNo, content, language,
                    requestedPages, safeRequest.getRegion(), safeRequest.isEnhance());
        }
        throw new BusinessException(415, "OCR 仅支持 PDF、PNG 和 JPEG");
    }

    private OcrResultVO recognizeImage(Long fileId, Integer versionNo, byte[] content,
                                       String language, List<Integer> pages,
                                       OcrRequestDTO.Region requestedRegion,
                                       boolean enhance) {
        if (!pages.isEmpty() && !pages.equals(List.of(1))) {
            throw new BusinessException(400, "图片只有第 1 页");
        }
        BufferedImage image;
        try (InputStream input = new ByteArrayInputStream(content)) {
            image = ImageIO.read(input);
        } catch (IOException invalid) {
            throw new BusinessException(422, "图片无法解析");
        }
        if (image == null) {
            throw new BusinessException(415, "无法识别该图片格式");
        }
        validatePixels(image);
        CroppedImage cropped = crop(image, requestedRegion);
        byte[] png = encodePng(enhance ? enhance(cropped.image()) : cropped.image());
        requireEngine();
        OcrEngine.Result recognized = engine.recognize(png, language);
        OcrResultVO.Page page = toPage(1, "OCR", image.getWidth(), image.getHeight(),
                recognized, cropped.region(), cropped.offsetX(), cropped.offsetY());
        return new OcrResultVO(fileId, versionNo, engine.name(), language,
                1, 1, recognized.truncated(), List.of(page));
    }

    private OcrResultVO recognizePdf(Long fileId, Integer versionNo, byte[] content,
                                     String language, List<Integer> requestedPages,
                                     Integer requestedDpi,
                                     OcrRequestDTO.Region requestedRegion,
                                     boolean enhance) {
        int dpi = resolveDpi(requestedDpi);
        try (PDDocument document = Loader.loadPDF(content)) {
            int totalPages = document.getNumberOfPages();
            if (totalPages < 1) {
                throw new BusinessException(422, "PDF 没有可识别页面");
            }
            List<Integer> pages = selectedPages(requestedPages, totalPages);
            if (pages.size() > properties.getMaxPages()) {
                throw new BusinessException(413,
                        "单次 OCR 最多处理 " + properties.getMaxPages() + " 页，请分批选择");
            }

            List<OcrResultVO.Page> results = new ArrayList<>();
            int renderedPages = 0;
            int totalChars = 0;
            boolean truncated = false;
            boolean engineChecked = false;
            for (int pageNo : pages) {
                NativeText nativeText = nativeText(document, pageNo);
                if (requestedRegion == null && !nativeText.text().isBlank()) {
                    totalChars = checkedChars(totalChars, nativeText.text());
                    OcrResultVO.Line nativeLine = nativeText.text().contains("\n")
                            ? null : new OcrResultVO.Line(nativeText.text(), 0, 0, 0, 0,
                            100, false);
                    results.add(new OcrResultVO.Page(pageNo, "NATIVE", 0, 0,
                            nativeText.text(), 100d, false, null,
                            List.of(),
                            nativeLine == null ? List.of() : List.of(nativeLine),
                            nativeText.truncated()));
                    truncated |= nativeText.truncated();
                    continue;
                }

                if (!engineChecked) {
                    requireEngine();
                    engineChecked = true;
                }
                PDPage page = document.getPage(pageNo - 1);
                BufferedImage image = new PDFRenderer(document)
                        .renderImageWithDPI(pageNo - 1, dpi, ImageType.RGB);
                validatePixels(image);
                renderedPages++;
                CroppedImage cropped = crop(image, requestedRegion);
                OcrEngine.Result recognized = engine.recognize(
                        encodePng(enhance ? enhance(cropped.image()) : cropped.image()),
                        language);
                String pageText = recognized.lines().stream()
                        .map(OcrEngine.Line::text)
                        .reduce((left, right) -> left + "\n" + right).orElse("");
                if (!pageText.isBlank()) totalChars = checkedChars(totalChars, pageText);
                truncated |= recognized.truncated();
                results.add(toPage(pageNo, recognized.lines().isEmpty() ? "EMPTY" : "OCR",
                        image.getWidth(), image.getHeight(), recognized,
                        cropped.region(), cropped.offsetX(), cropped.offsetY()));
            }
            return new OcrResultVO(fileId, versionNo, engine.name(), language,
                    totalPages, renderedPages, truncated, results);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException invalid) {
            throw new BusinessException(422, "PDF 无法解析或内容已损坏");
        }
    }

    private OcrResultVO.Page toPage(int pageNo, String source, int width, int height,
                                    OcrEngine.Result result, OcrResultVO.Region region,
                                    int offsetX, int offsetY) {
        List<OcrResultVO.Line> lines = result.lines().stream()
                .map(line -> new OcrResultVO.Line(line.text(),
                        line.left() + offsetX, line.top() + offsetY,
                        line.width(), line.height(), line.confidence(), line.needsReview()))
                .toList();
        String text = lines.stream().map(OcrResultVO.Line::text)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        Double confidence = lines.isEmpty() ? null
                : Math.round(lines.stream().mapToDouble(OcrResultVO.Line::confidence)
                .average().orElse(0) * 100d) / 100d;
        boolean needsReview = lines.stream().anyMatch(OcrResultVO.Line::needsReview);
        List<OcrResultVO.Paragraph> paragraphs = paragraphs(lines);
        return new OcrResultVO.Page(pageNo, source, width, height, text,
                confidence, needsReview, region, paragraphs, lines, result.truncated());
    }

    private static List<OcrResultVO.Paragraph> paragraphs(List<OcrResultVO.Line> lines) {
        if (lines.isEmpty()) return List.of();
        List<Integer> heights = lines.stream().map(OcrResultVO.Line::height)
                .sorted().toList();
        int medianHeight = heights.get(heights.size() / 2);
        int gapThreshold = Math.max(8, (int) Math.ceil(Math.max(1, medianHeight) * 0.9));
        List<OcrResultVO.Paragraph> result = new ArrayList<>();
        int start = 0;
        for (int index = 1; index <= lines.size(); index++) {
            boolean boundary = index == lines.size();
            if (!boundary) {
                OcrResultVO.Line previous = lines.get(index - 1);
                OcrResultVO.Line current = lines.get(index);
                int gap = current.top() - (previous.top() + previous.height());
                boundary = gap > gapThreshold;
            }
            if (boundary) {
                result.add(toParagraph(lines.subList(start, index)));
                start = index;
            }
        }
        return List.copyOf(result);
    }

    private static OcrResultVO.Paragraph toParagraph(List<OcrResultVO.Line> lines) {
        int left = lines.stream().mapToInt(OcrResultVO.Line::left).min().orElse(0);
        int top = lines.stream().mapToInt(OcrResultVO.Line::top).min().orElse(0);
        int right = lines.stream().mapToInt(line -> line.left() + line.width())
                .max().orElse(left);
        int bottom = lines.stream().mapToInt(line -> line.top() + line.height())
                .max().orElse(top);
        double confidence = Math.round(lines.stream()
                .mapToDouble(OcrResultVO.Line::confidence).average().orElse(0) * 100d) / 100d;
        return new OcrResultVO.Paragraph(
                lines.stream().map(OcrResultVO.Line::text)
                        .reduce((leftText, rightText) -> leftText + "\n" + rightText).orElse(""),
                left, top, Math.max(0, right - left), Math.max(0, bottom - top),
                confidence,
                lines.stream().anyMatch(OcrResultVO.Line::needsReview),
                lines);
    }

    private static CroppedImage crop(BufferedImage image,
                                     OcrRequestDTO.Region requested) {
        if (requested == null) {
            return new CroppedImage(image, null, 0, 0);
        }
        long right = (long) requested.getX() + requested.getWidth();
        long bottom = (long) requested.getY() + requested.getHeight();
        if (requested.getWidth() < 1 || requested.getHeight() < 1
                || requested.getX() < 0 || requested.getY() < 0
                || right > image.getWidth() || bottom > image.getHeight()) {
            throw new BusinessException(400, "OCR 区域超出页面或图片范围");
        }
        BufferedImage cropped = new BufferedImage(requested.getWidth(),
                requested.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = cropped.createGraphics();
        try {
            graphics.drawImage(image.getSubimage(requested.getX(), requested.getY(),
                    requested.getWidth(), requested.getHeight()), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return new CroppedImage(cropped,
                new OcrResultVO.Region(requested.getX(), requested.getY(),
                        requested.getWidth(), requested.getHeight()),
                requested.getX(), requested.getY());
    }

    /**
     * 转灰度并按 1%～99% 分位拉伸对比度。透明像素按白底处理，尺寸和坐标不变。
     */
    static BufferedImage enhance(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage flattened = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = flattened.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        int[] pixels = flattened.getRGB(0, 0, width, height, null, 0, width);
        int[] histogram = new int[256];
        for (int pixel : pixels) {
            int gray = (((pixel >> 16) & 0xff) * 77
                    + ((pixel >> 8) & 0xff) * 150 + (pixel & 0xff) * 29) >> 8;
            histogram[gray]++;
        }
        int lowTarget = Math.max(0, pixels.length / 100);
        int highTarget = Math.max(lowTarget, pixels.length - pixels.length / 100);
        int low = 0;
        int accumulated = 0;
        while (low < 255 && (accumulated += histogram[low]) <= lowTarget) low++;
        int high = 255;
        accumulated = 0;
        while (high > 0 && (accumulated += histogram[high]) <= pixels.length - highTarget) high--;
        if (high <= low) {
            low = 0;
            high = 255;
        }
        double scale = 255d / Math.max(1, high - low);
        int[] enhanced = new int[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int gray = (((pixel >> 16) & 0xff) * 77
                    + ((pixel >> 8) & 0xff) * 150 + (pixel & 0xff) * 29) >> 8;
            int value = Math.max(0, Math.min(255,
                    (int) Math.round((gray - low) * scale)));
            enhanced[index] = (value << 16) | (value << 8) | value;
        }
        flattened.setRGB(0, 0, width, height, enhanced, 0, width);
        return flattened;
    }

    private NativeText nativeText(PDDocument document, int pageNo) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        String text = stripper.getText(document).replace("\r\n", "\n")
                .replace('\r', '\n').trim();
        int limit = storageProperties.getPdf().getMaxExtractedCharsPerPage();
        if (text.codePointCount(0, text.length()) <= limit) return new NativeText(text, false);
        StringBuilder truncated = new StringBuilder(limit);
        text.codePoints().limit(limit).forEach(truncated::appendCodePoint);
        return new NativeText(truncated.toString(), true);
    }

    private int checkedChars(int current, String text) {
        int count = current + text.codePointCount(0, text.length());
        if (count > properties.getMaxOutputChars()) {
            throw new BusinessException(413, "OCR 结果文字总量超过限制，请分批选择页面");
        }
        return count;
    }

    private List<Integer> selectedPages(List<Integer> requested, int totalPages) {
        List<Integer> result = requested.isEmpty()
                ? java.util.stream.IntStream.rangeClosed(1, totalPages).boxed().toList()
                : requested;
        for (int pageNo : result) {
            if (pageNo > totalPages) {
                throw new BusinessException(404,
                        "页码超出范围（该版本共 " + totalPages + " 页）");
            }
        }
        return result;
    }

    private List<Integer> validateRequestedPages(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) return List.of();
        Set<Integer> unique = new HashSet<>();
        for (Integer page : pages) {
            if (page == null || page < 1) {
                throw new BusinessException(400, "OCR 页码必须为正数");
            }
            if (!unique.add(page)) {
                throw new BusinessException(400, "OCR 页码不能重复");
            }
        }
        return List.copyOf(pages);
    }

    private int resolveDpi(Integer requested) {
        int dpi = requested == null ? properties.getDpi() : requested;
        if (dpi < 100 || dpi > properties.getMaxDpi()) {
            throw new BusinessException(400,
                    "OCR DPI 必须在 100 到 " + properties.getMaxDpi() + " 之间");
        }
        return dpi;
    }

    private void requireEngine() {
        if (!engine.available()) {
            throw new BusinessException(503,
                    "云端 OCR 未启用或不可用，请配置默认 OCR 模型并检查密钥");
        }
    }

    private void validatePixels(BufferedImage image) {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > storageProperties.getUpload().getMaxImagePixels()) {
            throw new BusinessException(413, "OCR 页面像素数超过限制，请降低 DPI");
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new BusinessException(422, "OCR 页面无法编码为 PNG");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(422, "OCR 页面编码失败");
        }
    }

    private static byte[] readAll(FileDownload version) {
        try (InputStream input = version.resource().getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "读取 OCR 源文件失败");
        }
    }

    private static String extension(FileAsset asset) {
        String extension = asset.getExtension();
        if (extension != null && !extension.isBlank()) {
            return extension.trim().toLowerCase(Locale.ROOT);
        }
        String contentType = asset.getContentType();
        if (contentType == null) return "";
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("pdf")) return "pdf";
        if (lower.contains("png")) return "png";
        if (lower.contains("jpeg") || lower.contains("jpg")) return "jpg";
        return "";
    }

    private record NativeText(String text, boolean truncated) { }
    private record CroppedImage(BufferedImage image, OcrResultVO.Region region,
                                int offsetX, int offsetY) { }
}
