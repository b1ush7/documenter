package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.enums.PdfConversionMode;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.PdfToDocxService;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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

    public PdfToDocxServiceImpl(DocumentVersionService versions, FileStorage storage,
                                VersionWriter versionWriter, StorageProperties properties) {
        this.versions = versions;
        this.storage = storage;
        this.versionWriter = versionWriter;
        this.properties = properties;
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

        byte[] docx = rebuild(pdfBytes, request.getMode());
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (docx.length > maxSize) {
            throw new BusinessException(413, "转换后的 DOCX 超过文件大小限制");
        }

        String storageKey = storage.store(docx, "docx");
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
                            + "；复杂表格、域、脚注和精确分页需在办公软件中复核",
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
            Set<COSBase> visitedImages = new HashSet<>();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = pdf.getPage(pageIndex);
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
                    if (countImages(page.getResources(), new HashSet<>()) > 0) {
                        scannedPages.add(pageIndex + 1);
                    }
                    continue;
                }

                if (pageIndex > 0) {
                    docx.createParagraph().setPageBreak(true);
                }
                totalLines += appendTextPage(docx, text.lines, page, mode);
                PageImages images = extractImages(page.getResources(), visitedImages);
                totalImages += appendImages(docx, images, page, imageBudget);
            }

            if (!scannedPages.isEmpty()) {
                throw new BusinessException(422, "PDF 第 " + joinedPages(scannedPages)
                        + " 页没有原生文字，当前没有可复用的 OCR 结果，不能生成可编辑 DOCX");
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
        for (PDImageXObject image : images.images) {
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
                    float width = Math.max(1f, Math.min(availableWidth, image.getWidth()));
                    float height = width * image.getHeight() / Math.max(1, image.getWidth());
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

    private static PageImages extractImages(PDResources resources, Set<COSBase> visited) throws IOException {
        List<PDImageXObject> images = new ArrayList<>();
        collectImages(resources, visited, images);
        return new PageImages(images);
    }

    private static void collectImages(PDResources resources, Set<COSBase> visited,
                                      List<PDImageXObject> images) throws IOException {
        if (resources == null) {
            return;
        }
        for (var name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object == null || !visited.add(object.getCOSObject())) {
                continue;
            }
            if (object instanceof PDImageXObject image) {
                images.add(image);
            } else if (object instanceof PDFormXObject form) {
                collectImages(form.getResources(), visited, images);
            }
        }
    }

    private static int countImages(PDResources resources, Set<COSBase> visited) throws IOException {
        if (resources == null) {
            return 0;
        }
        int count = 0;
        for (var name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object == null || !visited.add(object.getCOSObject())) {
                continue;
            }
            if (object instanceof PDImageXObject) {
                count++;
            } else if (object instanceof PDFormXObject form) {
                count += countImages(form.getResources(), visited);
            }
        }
        return count;
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
    private record PageImages(List<PDImageXObject> images) { }
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
