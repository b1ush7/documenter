package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.PdfDocumentService;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;

/** 基于 PDFBox 的 PDF 原生文本读取器。 */
@Slf4j
@Service
public class PdfDocumentServiceImpl implements PdfDocumentService {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final int MIN_PREVIEW_DPI = 72;

    private final DocumentVersionService versionService;
    private final StorageProperties properties;

    public PdfDocumentServiceImpl(DocumentVersionService versionService, StorageProperties properties) {
        this.versionService = versionService;
        this.properties = properties;
    }

    @Override
    public PdfContentVO readContent(Long userId, Long fileId, Integer versionNo) {
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        if (!isPdf(version)) {
            throw new BusinessException(400, "当前版本不是 PDF 文档");
        }

        try (InputStream input = version.resource().getInputStream();
             PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            if (totalPages > properties.getUpload().getMaxPdfPages()) {
                throw new BusinessException(413, "PDF 页数超过处理限制");
            }
            return extract(fileId, versionNo, document);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("读取 PDF 内容失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(422, "PDF 文档无法解析或内容已损坏");
        }
    }

    @Override
    public PdfPagePreview renderPage(Long userId, Long fileId, Integer versionNo,
                                     Integer pageNo, Integer dpi) {
        if (pageNo == null || pageNo <= 0) {
            throw new BusinessException(400, "PDF 页码必须大于 0");
        }
        int actualDpi = dpi == null ? properties.getPdf().getPreviewDpi() : dpi;
        if (actualDpi < MIN_PREVIEW_DPI || actualDpi > properties.getPdf().getMaxPreviewDpi()) {
            throw new BusinessException(400, "预览 DPI 必须在 " + MIN_PREVIEW_DPI + " 到 "
                    + properties.getPdf().getMaxPreviewDpi() + " 之间");
        }

        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        if (!isPdf(version)) {
            throw new BusinessException(400, "当前版本不是 PDF 文档");
        }

        try (InputStream input = version.resource().getInputStream();
             PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            if (pageNo > document.getNumberOfPages()) {
                throw new BusinessException(404, "PDF 页面不存在");
            }
            PDPage page = document.getPage(pageNo - 1);
            int expectedWidth = (int) Math.ceil(page.getCropBox().getWidth() * actualDpi / 72.0);
            int expectedHeight = (int) Math.ceil(page.getCropBox().getHeight() * actualDpi / 72.0);
            long expectedPixels = (long) expectedWidth * expectedHeight;
            if (expectedPixels > properties.getUpload().getMaxImagePixels()) {
                throw new BusinessException(413, "PDF 页面预览像素超过限制");
            }

            var image = new PDFRenderer(document).renderImageWithDPI(
                    pageNo - 1, actualDpi, ImageType.RGB);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output)) {
                    throw new BusinessException(500, "当前环境不支持 PNG 预览编码");
                }
                String fileName = "file-" + fileId + "-v" + versionNo + "-page-" + pageNo + ".png";
                return new PdfPagePreview(output.toByteArray(), pageNo, actualDpi,
                        image.getWidth(), image.getHeight(), fileName);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("渲染 PDF 页面失败, fileId={}, versionNo={}, pageNo={}",
                    fileId, versionNo, pageNo, e);
            throw new BusinessException(422, "PDF 页面无法渲染或内容已损坏");
        }
    }

    private PdfContentVO extract(Long fileId, Integer versionNo, PDDocument document) throws IOException {
        List<PdfContentVO.Page> pages = new ArrayList<>(document.getNumberOfPages());
        int textPages = 0;
        int scannedPages = 0;
        int mixedPages = 0;
        int emptyPages = 0;
        int extractedChars = 0;
        boolean documentTruncated = false;

        PDFTextStripper stripper = new PDFTextStripper();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            int pageNo = pageIndex + 1;
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            String originalText = stripper.getText(document).strip();
            int textCharCount = originalText.codePointCount(0, originalText.length());
            boolean hasText = originalText.codePoints().anyMatch(value -> !Character.isWhitespace(value));
            int imageCount = countImages(document.getPage(pageIndex));
            String type = pageType(hasText, imageCount);
            switch (type) {
                case "TEXT" -> textPages++;
                case "SCANNED" -> scannedPages++;
                case "MIXED" -> mixedPages++;
                default -> emptyPages++;
            }

            int remaining = Math.max(0,
                    properties.getPdf().getMaxExtractedCharsTotal() - extractedChars);
            int pageLimit = Math.min(properties.getPdf().getMaxExtractedCharsPerPage(), remaining);
            String returnedText = truncateCodePoints(originalText, pageLimit);
            int returnedChars = returnedText.codePointCount(0, returnedText.length());
            boolean pageTruncated = returnedChars < textCharCount;
            extractedChars += returnedChars;
            documentTruncated |= pageTruncated;
            pages.add(new PdfContentVO.Page(pageNo, type, returnedText, textCharCount,
                    imageCount, pageTruncated));
        }

        String documentType = documentType(textPages, scannedPages, mixedPages, emptyPages);
        return new PdfContentVO(fileId, versionNo, documentType, document.getNumberOfPages(),
                textPages, scannedPages, mixedPages, emptyPages, extractedChars,
                documentTruncated, pages);
    }

    private static boolean isPdf(FileDownload version) {
        if (PDF_CONTENT_TYPE.equalsIgnoreCase(version.contentType())) {
            return true;
        }
        String extension = version.asset().getExtension();
        return extension != null && "pdf".equals(extension.toLowerCase(Locale.ROOT));
    }

    private static String pageType(boolean hasText, int imageCount) {
        if (hasText && imageCount > 0) {
            return "MIXED";
        }
        if (hasText) {
            return "TEXT";
        }
        return imageCount > 0 ? "SCANNED" : "EMPTY";
    }

    private static String documentType(int textPages, int scannedPages, int mixedPages, int emptyPages) {
        if (mixedPages > 0 || (textPages > 0 && scannedPages > 0)) {
            return "MIXED";
        }
        if (textPages > 0) {
            return "TEXT";
        }
        if (scannedPages > 0) {
            return "SCANNED";
        }
        return "EMPTY";
    }

    private static int countImages(PDPage page) throws IOException {
        return countImages(page.getResources(), new HashSet<>());
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

    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }
}
