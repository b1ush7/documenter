package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.configuration.OcrProperties;
import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.enums.PdfConversionMode;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.OcrEngine;
import com.documenter.service.PdfToDocxService;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.util.OcrLanguageUtil;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 基于 PDFBox 坐标和 POI 的基础可编辑重建器。 */
@Slf4j
@Service
public class PdfToDocxServiceImpl implements PdfToDocxService {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final float POINTS_PER_INCH = 72f;
    private static final int DEFAULT_MARGIN_TWIPS = 1440;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final DocumentVersionService versions;
    private final FileStorage storage;
    private final VersionWriter versionWriter;
    private final StorageProperties properties;
    private final OcrEngine ocrEngine;
    private final OcrProperties ocrProperties;

    public PdfToDocxServiceImpl(DocumentVersionService versions, FileStorage storage,
                                VersionWriter versionWriter, StorageProperties properties,
                                OcrEngine ocrEngine, OcrProperties ocrProperties) {
        this.versions = versions;
        this.storage = storage;
        this.versionWriter = versionWriter;
        this.properties = properties;
        this.ocrEngine = ocrEngine;
        this.ocrProperties = ocrProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VersionVO convert(Long userId, Long fileId, Integer sourceVersion,
                             ConvertPdfToDocxDTO request, Long taskId, Integer attemptNo) {
        if (request == null || request.getMode() == null) {
            throw new BusinessException(400, "PDF 转换取向不能为空");
        }
        if (sourceVersion == null || sourceVersion < 1) {
            throw new BusinessException(400, "PDF 源版本号必须为正数");
        }

        FileDownload source = versions.downloadVersion(userId, fileId, sourceVersion);
        if (!isPdf(source)) {
            throw new BusinessException(400, "当前版本不是 PDF 文档");
        }

        byte[] pdfBytes;
        try (InputStream input = source.resource().getInputStream()) {
            pdfBytes = input.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "PDF 文件读取失败，请稍后重试");
        }

        String ocrLanguage = request.getOcrLanguage() == null
                || request.getOcrLanguage().isBlank()
                ? null : OcrLanguageUtil.resolve(request.getOcrLanguage(), ocrProperties);
        byte[] docx = rebuild(pdfBytes, request.getMode(), ocrLanguage);
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (docx.length > maxSize) {
            throw new BusinessException(413, "转换后的 DOCX 超过文件大小限制");
        }

        String storageKey = storage.store(docx, "docx");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) storage.delete(storageKey);
                        }
                    });
        }
        DocumentVersion created = null;
        try {
            String modeLabel = request.getMode() == PdfConversionMode.LAYOUT ? "版式优先" : "可编辑优先";
            String instruction = request.getInstruction() == null || request.getInstruction().isBlank()
                    ? "PDF 转 DOCX（" + modeLabel + "）"
                    : request.getInstruction().trim();
            created = versionWriter.appendVersion(fileId, userId,
                    new VersionWriter.VersionContent(storageKey, docx.length, Digests.sha256Hex(docx),
                            DOCX_CONTENT_TYPE, "docx"),
                    ChangeType.CONVERT, instruction,
                    "由 PDF 版本 " + sourceVersion + " 重建可编辑 DOCX；" + modeLabel
                            + "；复杂表格、域、脚注和精确分页需在办公软件中复核"
                            + (ocrLanguage == null ? "" : "；扫描页 OCR 结果需人工复核"),
                    sourceVersion, taskId, attemptNo);
            if (!storageKey.equals(created.getStorageKey())) {
                storage.delete(storageKey);
            }
            return VersionVO.from(created);
        } catch (BusinessException e) {
            cleanup(storageKey, created);
            throw e;
        } catch (RuntimeException e) {
            cleanup(storageKey, created);
            throw e;
        }
    }

    byte[] rebuild(byte[] pdfBytes, PdfConversionMode mode) {
        return rebuild(pdfBytes, mode, null);
    }

    byte[] rebuild(byte[] pdfBytes, PdfConversionMode mode, String ocrLanguage) {
        try (PDDocument pdf = Loader.loadPDF(pdfBytes);
             XWPFDocument docx = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int pageCount = pdf.getNumberOfPages();
            if (pageCount < 1) {
                throw new BusinessException(422, "PDF 没有可转换页面");
            }
            if (pageCount > properties.getUpload().getMaxPdfPages()) {
                throw new BusinessException(413, "PDF 页数超过处理限制");
            }

            int totalChars = 0;
            int totalLines = 0;
            int totalImages = 0;
            ImageBudget imageBudget = new ImageBudget(
                    properties.getPdf().getMaxConversionImages(),
                    Math.min(properties.getPdf().getMaxConversionImagePixelsTotal(),
                            Math.max(0L, properties.getUpload().getMaxImagePixels())));
            List<Integer> scannedPages = new ArrayList<>();
            boolean wrotePage = false;
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = pdf.getPage(pageIndex);
                PageImages images = extractImages(page);
                PageText text = extractPage(pdf, pageIndex + 1);
                int pageChars = text.text.codePointCount(0, text.text.length());
                if (pageChars > properties.getPdf().getMaxExtractedCharsPerPage()) {
                    throw new BusinessException(413, "PDF 第 " + (pageIndex + 1)
                            + " 页文字数量超过转换限制");
                }
                totalChars += pageChars;
                if (totalChars > properties.getPdf().getMaxExtractedCharsTotal()) {
                    throw new BusinessException(413, "PDF 提取文字总量超过转换限制");
                }
                if (text.lines.isEmpty()) {
                    if (!images.images.isEmpty()) {
                        if (ocrLanguage == null) {
                            scannedPages.add(pageIndex + 1);
                        } else {
                            PageText recognized = recognizeScannedPage(pdf, pageIndex, ocrLanguage);
                            int recognizedChars = recognized.text.codePointCount(
                                    0, recognized.text.length());
                            totalChars += recognizedChars;
                            if (totalChars > properties.getPdf().getMaxExtractedCharsTotal()
                                    || recognizedChars
                                    > properties.getPdf().getMaxExtractedCharsPerPage()) {
                                throw new BusinessException(413,
                                        "PDF 第 " + (pageIndex + 1) + " 页 OCR 文字超过转换限制");
                            }
                            if (recognized.lines.isEmpty()) {
                                scannedPages.add(pageIndex + 1);
                            } else {
                                if (wrotePage) {
                                    docx.createParagraph().setPageBreak(true);
                                }
                                totalLines += appendTextPage(docx, recognized.lines, page, mode);
                                wrotePage = true;
                            }
                        }
                    }
                    continue;
                }

                if (wrotePage) {
                    docx.createParagraph().setPageBreak(true);
                }
                totalLines += appendTextPage(docx, text.lines, page, mode);
                totalImages += appendImages(docx, images, page, imageBudget);
                wrotePage = true;
            }

            if (!scannedPages.isEmpty()) {
                throw new BusinessException(422, "PDF 第 " + joinedPages(scannedPages)
                        + " 页没有原生文字；请提供 ocrLanguage 使用已配置的云端 OCR");
            }
            if (totalLines == 0 && totalImages == 0) {
                throw new BusinessException(422, "PDF 没有可重建的文字或图片");
            }

            docx.write(output);
            log.info("PDF 转 DOCX 完成, pages={}, lines={}, images={}, mode={}",
                    pageCount, totalLines, totalImages, mode);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("PDF 转 DOCX 失败", e);
            throw new BusinessException(422, "PDF 无法转换为可编辑 DOCX 或内容已损坏");
        }
    }

    private static PageText extractPage(PDDocument document, int pageNo) throws IOException {
        PositionStripper stripper = new PositionStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageNo);
        stripper.setEndPage(pageNo);
        stripper.getText(document);
        return new PageText(stripper.text.toString(), List.copyOf(stripper.lines));
    }

    private PageText recognizeScannedPage(PDDocument document, int pageIndex,
                                          String language) throws IOException {
        if (!ocrEngine.available()) {
            throw new BusinessException(503,
                    "扫描 PDF 重建需要云端 OCR；请先配置 OCR 供应商凭据并启用 OCR");
        }
        BufferedImage image = new PDFRenderer(document)
                .renderImageWithDPI(pageIndex, ocrProperties.getDpi(), ImageType.RGB);
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > properties.getUpload().getMaxImagePixels()) {
            throw new BusinessException(413,
                    "PDF 第 " + (pageIndex + 1) + " 页 OCR 像素数超过限制");
        }
        byte[] png;
        try (ByteArrayOutputStream encoded = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", encoded)) {
                throw new BusinessException(422,
                        "PDF 第 " + (pageIndex + 1) + " 页无法编码为 OCR 图片");
            }
            png = encoded.toByteArray();
        }
        OcrEngine.Result recognized = ocrEngine.recognize(png, language);
        if (recognized.truncated()) {
            throw new BusinessException(413,
                    "PDF 第 " + (pageIndex + 1) + " 页 OCR 结果超过转换限制");
        }
        PDPage page = document.getPage(pageIndex);
        float scale = POINTS_PER_INCH / Math.max(1f, ocrProperties.getDpi());
        float cropHeight = (float) page.getCropBox().getHeight();
        List<PdfLine> lines = recognized.lines().stream()
                .map(line -> new PdfLine(
                        line.text(),
                        roundPoints(line.left() * scale),
                        roundPoints(Math.max(0,
                                cropHeight - line.top() * scale - line.height() * scale)),
                        Math.max(1f, roundPoints(line.height() * scale)),
                        false, false, "Arial"))
                .toList();
        String text = lines.stream().map(PdfLine::text)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new PageText(text, lines);
    }

    private static int appendTextPage(XWPFDocument docx, List<PdfLine> lines,
                                      PDPage page, PdfConversionMode mode) {
        float previousY = Float.NaN;
        for (PdfLine line : lines) {
            XWPFParagraph paragraph = docx.createParagraph();
            paragraph.setSpacingAfter(0);
            paragraph.setSpacingBefore(0);
            if (mode == PdfConversionMode.LAYOUT) {
                float relativeX = Math.max(0,
                        line.x - (float) page.getCropBox().getLowerLeftX() - POINTS_PER_INCH);
                paragraph.setIndentationLeft(Math.round(relativeX * 20));
                if (!Float.isNaN(previousY)) {
                    float gap = Math.max(0f, previousY - line.y - line.fontSize);
                    paragraph.setSpacingBefore(Math.min(1440,
                            Math.round(gap * 20)));
                }
            }
            XWPFRun run = paragraph.createRun();
            String font = normalizeFont(line.fontName);
            run.setFontFamily(font);
            run.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
            run.setFontSize(Math.max(6, Math.min(72, Math.round(line.fontSize))));
            run.setBold(line.bold);
            run.setItalic(line.italic);
            run.setText(line.text);
            previousY = line.y;
        }
        return lines.size();
    }

    private int appendImages(XWPFDocument docx, PageImages images, PDPage page,
                             ImageBudget budget) {
        int inserted = 0;
        for (PaintedImage painted : images.images) {
            PDImage image = painted.image;
            if (budget.remainingImages <= 0) {
                break;
            }
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels <= 0 || pixels > budget.remainingPixels
                    || pixels > properties.getUpload().getMaxImagePixels()) {
                continue;
            }
            try {
                BufferedImage buffered = image.getImage();
                try (ByteArrayOutputStream encoded = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(buffered, "png", encoded)) {
                        continue;
                    }
                    byte[] bytes = encoded.toByteArray();
                    if (bytes.length > MAX_IMAGE_BYTES) {
                        continue;
                    }
                    float availableWidth = (float) page.getCropBox().getWidth() - 2 * POINTS_PER_INCH;
                    float width = Math.max(1f, Math.min(availableWidth, painted.width));
                    float height = width * painted.height / Math.max(1, painted.width);
                    XWPFParagraph paragraph = docx.createParagraph();
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    XWPFRun run = paragraph.createRun();
                    try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                        run.addPicture(input, Document.PICTURE_TYPE_PNG,
                                "pdf-image-" + inserted + ".png",
                                Units.toEMU(width), Units.toEMU(height));
                    }
                    inserted++;
                    budget.remainingImages--;
                    budget.remainingPixels -= pixels;
                }
            } catch (Exception e) {
                log.debug("跳过无法重建的 PDF 内嵌图片", e);
            }
        }
        return inserted;
    }

    private PageImages extractImages(PDPage page) throws IOException {
        // 资源字典可能由多页共享，只有内容流实际绘制的图片才属于当前页面。
        var collector = new PaintedImageCollector(page, Math.max(1, Math.min(1000, properties.getPdf().getMaxConversionImages())));
        collector.processPage(page);
        return new PageImages(List.copyOf(collector.images));
    }

    private static final class PaintedImageCollector extends PDFGraphicsStreamEngine {
        private final List<PaintedImage> images = new ArrayList<>();
        private final Set<COSBase> activeForms = new HashSet<>();
        private final int limit;
        private java.awt.geom.Point2D current = new java.awt.geom.Point2D.Float();
        PaintedImageCollector(PDPage page, int limit) { super(page); this.limit = limit; }
        @Override public void drawImage(PDImage image) {
            var transform = getGraphicsState().getCurrentTransformationMatrix();
            float width = (float) Math.hypot(transform.getScaleX(), transform.getShearY());
            float height = (float) Math.hypot(transform.getShearX(), transform.getScaleY());
            if (images.size() < limit && Float.isFinite(width) && Float.isFinite(height) && width > 0 && height > 0) {
                images.add(new PaintedImage(image, width, height));
            }
        }
        @Override public void showForm(PDFormXObject form) throws IOException {
            if (activeForms.size() >= 32 || !activeForms.add(form.getCOSObject())) return;
            try { super.showForm(form); } finally { activeForms.remove(form.getCOSObject()); }
        }
        @Override public void appendRectangle(java.awt.geom.Point2D a, java.awt.geom.Point2D b, java.awt.geom.Point2D c, java.awt.geom.Point2D d) { current = a; }
        @Override public void moveTo(float x, float y) { current = new java.awt.geom.Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) { moveTo(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { moveTo(x3, y3); }
        @Override public java.awt.geom.Point2D getCurrentPoint() { return current; }
        @Override public void clip(int rule) {}
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int rule) {}
        @Override public void fillAndStrokePath(int rule) {}
        @Override public void shadingFill(COSName name) {}
    }

    private static String normalizeFont(String name) {
        if (name == null || name.isBlank()) {
            return "SimSun";
        }
        String normalized = name.trim();
        if (normalized.length() > 80 || normalized.chars().anyMatch(Character::isISOControl)) {
            return "SimSun";
        }
        return normalized;
    }

    private static boolean isPdf(FileDownload version) {
        return PDF_CONTENT_TYPE.equalsIgnoreCase(version.contentType())
                || "pdf".equalsIgnoreCase(version.asset().getExtension());
    }

    private static String joinedPages(List<Integer> pages) {
        return pages.stream().map(String::valueOf).reduce((left, right) -> left + "、" + right).orElse("");
    }

    private static float roundPoints(float value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    private void cleanup(String storageKey, DocumentVersion created) {
        if (created != null && storageKey.equals(created.getStorageKey())) {
            return;
        }
        if (!storage.delete(storageKey)) {
            log.warn("PDF 转 DOCX 失败后无法清理未入库产物, storageKey={}", storageKey);
        }
    }

    private static final class PositionStripper extends PDFTextStripper {
        private final StringBuilder text = new StringBuilder();
        private final List<PdfLine> lines = new ArrayList<>();

        @Override
        protected void writeString(String value, List<TextPosition> positions) {
            text.append(value).append('\n');
            String normalized = value == null ? "" : value.replace('\u00a0', ' ').stripTrailing();
            if (normalized.isBlank()) {
                return;
            }
            TextPosition first = positions.stream().filter(position -> !position.getUnicode().isBlank())
                    .findFirst().orElse(positions.isEmpty() ? null : positions.getFirst());
            float x = first == null ? 0 : first.getXDirAdj();
            float y = first == null ? 0 : first.getYDirAdj();
            float fontSize = first == null ? 12 : first.getFontSizeInPt();
            String fontName = first == null || first.getFont() == null
                    ? "" : first.getFont().getName();
            String lower = fontName.toLowerCase(Locale.ROOT);
            lines.add(new PdfLine(normalized, roundPoints(x), roundPoints(y),
                    Math.max(1f, fontSize), lower.contains("bold"), lower.contains("italic")
                    || lower.contains("oblique"), fontName));
        }
    }

    private record PageText(String text, List<PdfLine> lines) { }
    private record PaintedImage(PDImage image, float width, float height) { }
    private record PageImages(List<PaintedImage> images) { }
    private record PdfLine(String text, float x, float y, float fontSize,
                           boolean bold, boolean italic, String fontName) { }
    private static final class ImageBudget {
        private int remainingImages;
        private long remainingPixels;

        private ImageBudget(int remainingImages, long remainingPixels) {
            this.remainingImages = remainingImages;
            this.remainingPixels = remainingPixels;
        }
    }
}
