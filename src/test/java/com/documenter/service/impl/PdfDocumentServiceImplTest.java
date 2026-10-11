package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.vo.FileDownload;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfDocumentServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION_NO = 1;

    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final StorageProperties properties = properties();
    private final PdfDocumentServiceImpl service = new PdfDocumentServiceImpl(versionService, properties);

    @Test
    void extractsNativeTextAndClassifiesTextPage() throws Exception {
        byte[] pdf = createPdf(document -> addTextPage(document, "Hello PDF"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        var result = service.readContent(USER_ID, FILE_ID, VERSION_NO);

        assertEquals("TEXT", result.documentType());
        assertEquals(1, result.totalPages());
        assertEquals(1, result.textPages());
        assertEquals("TEXT", result.pages().getFirst().type());
        assertTrue(result.pages().getFirst().text().contains("Hello PDF"));
        assertFalse(result.textTruncated());
    }

    @Test
    void detectsImageOnlyPageAsScanned() throws Exception {
        byte[] pdf = createPdf(PdfDocumentServiceImplTest::addImagePage);
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        var result = service.readContent(USER_ID, FILE_ID, VERSION_NO);

        assertEquals("SCANNED", result.documentType());
        assertEquals(1, result.scannedPages());
        assertEquals("SCANNED", result.pages().getFirst().type());
        assertEquals(1, result.pages().getFirst().imageCount());
        assertEquals("", result.pages().getFirst().text());
    }

    @Test
    void detectsDocumentWithTextAndScannedPagesAsMixed() throws Exception {
        byte[] pdf = createPdf(document -> {
            addTextPage(document, "Native text");
            addImagePage(document);
        });
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        var result = service.readContent(USER_ID, FILE_ID, VERSION_NO);

        assertEquals("MIXED", result.documentType());
        assertEquals(1, result.textPages());
        assertEquals(1, result.scannedPages());
    }

    @Test
    void truncatesReturnedTextAndKeepsOriginalCharacterCount() throws Exception {
        properties.getPdf().setMaxExtractedCharsPerPage(5);
        properties.getPdf().setMaxExtractedCharsTotal(5);
        byte[] pdf = createPdf(document -> addTextPage(document, "abcdefghij"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        var result = service.readContent(USER_ID, FILE_ID, VERSION_NO);

        assertEquals("abcde", result.pages().getFirst().text());
        assertEquals(10, result.pages().getFirst().textCharCount());
        assertEquals(5, result.extractedTextChars());
        assertTrue(result.pages().getFirst().textTruncated());
        assertTrue(result.textTruncated());
    }

    @Test
    void rejectsNonPdfVersionBeforeParsing() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(new byte[]{1, 2, 3}, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.readContent(USER_ID, FILE_ID, VERSION_NO));

        assertEquals(400, error.getCode());
    }

    @Test
    void rendersRequestedPageAsBoundedPngPreview() throws Exception {
        byte[] pdf = createPdf(document -> addTextPage(document, "Preview"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        var result = service.renderPage(USER_ID, FILE_ID, VERSION_NO, 1, 72);

        assertEquals(1, result.pageNo());
        assertEquals(72, result.dpi());
        assertTrue(result.content().length > 0);
        var image = ImageIO.read(new java.io.ByteArrayInputStream(result.content()));
        assertEquals(result.widthPixels(), image.getWidth());
        assertEquals(result.heightPixels(), image.getHeight());
        assertTrue(result.fileName().endsWith(".png"));
    }

    @Test
    void rejectsPreviewThatWouldExceedPixelLimit() throws Exception {
        properties.getUpload().setMaxImagePixels(100);
        byte[] pdf = createPdf(document -> addTextPage(document, "Large page"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.renderPage(USER_ID, FILE_ID, VERSION_NO, 1, 72));

        assertEquals(413, error.getCode());
    }

    @Test
    void rejectsPreviewPageOutsideDocument() throws Exception {
        byte[] pdf = createPdf(document -> addTextPage(document, "One page"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO)).thenReturn(download(pdf, true));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.renderPage(USER_ID, FILE_ID, VERSION_NO, 2, null));

        assertEquals(404, error.getCode());
    }

    private static StorageProperties properties() {
        StorageProperties properties = new StorageProperties();
        properties.getUpload().setMaxPdfPages(10);
        properties.getPdf().setMaxExtractedCharsPerPage(100_000);
        properties.getPdf().setMaxExtractedCharsTotal(2_000_000);
        properties.getPdf().setPreviewDpi(144);
        properties.getPdf().setMaxPreviewDpi(200);
        return properties;
    }

    private static byte[] createPdf(PdfWriter writer) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.write(document);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addTextPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }

    private static void addImagePage(PDDocument document) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 20, 20);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(5, 5, 10, 10);
        } finally {
            graphics.dispose();
        }
        var pdfImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(pdfImage, 72, 650, 100, 100);
        }
    }

    private static FileDownload download(byte[] content, boolean pdf) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setDisplayName(pdf ? "sample.pdf" : "sample.docx");
        asset.setContentType(pdf ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension(pdf ? "pdf" : "docx");
        return new FileDownload(asset, new ByteArrayResource(content), content.length,
                asset.getContentType(), asset.getDisplayName());
    }

    @FunctionalInterface
    private interface PdfWriter {
        void write(PDDocument document) throws Exception;
    }
}
