package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.configuration.OcrProperties;
import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.enums.PdfConversionMode;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.OcrEngine;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfToDocxServiceImplTest {

    @Test
    void sharedResourcesDoNotMoveAnImageOntoAnEarlierOrBlankPage() throws Exception {
        byte[] pdf = createPdf(document -> {
            var resources = new org.apache.pdfbox.pdmodel.PDResources();
            var image = LosslessFactory.createFromImage(document, sampleImage()); resources.add(image);
            for (int number = 1; number <= 3; number++) {
                var page = new PDPage(); page.setResources(resources); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    if (number != 2) {
                        stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                        stream.newLineAtOffset(72,720); stream.showText("Page " + number); stream.endText();
                    }
                    if (number == 3) stream.drawImage(image,72,600,40,40);
                }
            }
        });
        try(var rebuilt = new XWPFDocument(new ByteArrayInputStream(service.rebuild(pdf,PdfConversionMode.EDITABLE)))) {
            int third=-1, picture=-1;
            for(int i=0;i<rebuilt.getParagraphs().size();i++) {
                var paragraph=rebuilt.getParagraphs().get(i);
                if(paragraph.getText().contains("Page 3")) third=i;
                for(var run:paragraph.getRuns()) if(!run.getEmbeddedPictures().isEmpty()) picture=i;
            }
            assertEquals(1,rebuilt.getAllPictures().size()); assertTrue(third>=0 && picture>third);
        }
    }

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int SOURCE_VERSION = 3;

    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter versionWriter = mock(VersionWriter.class);
    private final OcrEngine ocrEngine = mock(OcrEngine.class);
    private final StorageProperties properties = properties();
    private final OcrProperties ocrProperties = new OcrProperties();
    private final PdfToDocxServiceImpl service =
            new PdfToDocxServiceImpl(versions, storage, versionWriter, properties,
                    ocrEngine, ocrProperties);

    @Test
    void rebuildsNativeTextAsEditableDocxWithPageBreaks() throws Exception {
        byte[] pdf = createPdf(document -> {
            addTextPage(document, "First page", 72);
            addTextPage(document, "Second page", 72);
        });

        byte[] docx = service.rebuild(pdf, PdfConversionMode.EDITABLE);

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = reopened.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(text.contains("First page"));
            assertTrue(text.contains("Second page"));
            assertTrue(reopened.getParagraphs().stream().anyMatch(paragraph ->
                    paragraph.isPageBreak()));
            assertTrue(reopened.getAllPictures().isEmpty());
        }
    }

    @Test
    void layoutModePreservesHorizontalOffsetFontSizeAndImage() throws Exception {
        byte[] pdf = createPdf(document -> {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD), 16);
                content.newLineAtOffset(180, 700);
                content.showText("Positioned line");
                content.endText();
                var image = LosslessFactory.createFromImage(document, sampleImage());
                content.drawImage(image, 72, 600, 40, 40);
            }
        });

        byte[] docx = service.rebuild(pdf, PdfConversionMode.LAYOUT);

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(docx))) {
            var paragraph = reopened.getParagraphs().stream()
                    .filter(item -> "Positioned line".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertTrue(paragraph.getIndentationLeft() > 0);
            assertTrue(paragraph.getRuns().getFirst().getFontSize() >= 16);
            assertTrue(paragraph.getRuns().getFirst().isBold());
            assertEquals(1, reopened.getAllPictures().size());
        }
    }

    @Test
    void rejectsScannedPageInsteadOfEmbeddingWholePageImage() throws Exception {
        byte[] pdf = createPdf(document -> addImagePage(document, false));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.rebuild(pdf, PdfConversionMode.EDITABLE));

        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains("OCR"));
    }

    @Test
    void rejectsMixedDocumentWhenAnyPageRequiresOcr() throws Exception {
        byte[] pdf = createPdf(document -> {
            addTextPage(document, "Native page", 72);
            addImagePage(document, false);
        });

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.rebuild(pdf, PdfConversionMode.EDITABLE));

        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains("第 2 页"));
    }

    @Test
    void rebuildsScannedPageFromConfiguredCloudOcr() throws Exception {
        byte[] pdf = createPdf(document -> addImagePage(document, false));
        when(ocrEngine.available()).thenReturn(true);
        when(ocrEngine.recognize(any(byte[].class), eq("zh+en"))).thenReturn(
                new OcrEngine.Result(List.of(
                        new OcrEngine.Line("云端识别标题", 20, 30, 180, 28, 96.5, false),
                        new OcrEngine.Line("Invoice 42", 20, 70, 130, 24, 91, false)), false));

        byte[] docx = service.rebuild(pdf, PdfConversionMode.EDITABLE, "zh+en");

        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = reopened.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText()).toList().toString();
            assertTrue(text.contains("云端识别标题"));
            assertTrue(text.contains("Invoice 42"));
            assertTrue(reopened.getAllPictures().isEmpty());
        }
        verify(ocrEngine).recognize(any(byte[].class), eq("zh+en"));
    }

    @Test
    void failsClearlyWhenScannedOcrModelIsUnavailable() throws Exception {
        byte[] pdf = createPdf(document -> addImagePage(document, false));
        when(ocrEngine.available()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.rebuild(pdf, PdfConversionMode.EDITABLE, "auto"));

        assertEquals(503, error.getCode());
        assertTrue(error.getMessage().contains("云端 OCR"));
        verify(ocrEngine, never()).recognize(any(byte[].class), anyString());
    }

    @Test
    void rejectsEmptyTextAndImageBudgetOverflow() throws Exception {
        byte[] empty = createPdf(document -> document.addPage(new PDPage()));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.rebuild(empty, PdfConversionMode.EDITABLE)).getCode());

        properties.getPdf().setMaxExtractedCharsPerPage(3);
        byte[] tooLong = createPdf(document -> addTextPage(document, "abcdef", 72));
        assertEquals(413, assertThrows(BusinessException.class,
                () -> service.rebuild(tooLong, PdfConversionMode.EDITABLE)).getCode());
    }

    @Test
    void conversionStoresConvertVersionAndForwardsTaskIdempotency() throws Exception {
        byte[] pdf = createPdf(document -> addTextPage(document, "Convert me", 72));
        when(versions.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION)).thenReturn(download(pdf));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("versions/v4.docx");
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.CONVERT), anyString(), anyString(), eq(SOURCE_VERSION),
                eq(88L), eq(2))).thenReturn(createdVersion("versions/v4.docx"));
        ConvertPdfToDocxDTO request = request(PdfConversionMode.LAYOUT);

        var result = service.convert(USER_ID, FILE_ID, SOURCE_VERSION, request, 88L, 2);

        assertEquals(4, result.getVersionNo());
        assertEquals("docx", result.getExtension());
        assertEquals(ChangeType.CONVERT.name(), result.getChangeType());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("docx"));
        assertTrue(bytes.getValue().length > 0);
        verify(storage, never()).delete("versions/v4.docx");
    }

    @Test
    void rejectsNonPdfAndCleansOutputOnVersionConflict() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(new byte[]{1, 2, 3}, false));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.convert(USER_ID, FILE_ID, SOURCE_VERSION,
                        request(PdfConversionMode.EDITABLE), null, null)).getCode());
        verify(storage, never()).store(any(byte[].class), anyString());

        byte[] pdf = createPdf(document -> addTextPage(document, "Conflict", 72));
        when(versions.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION)).thenReturn(download(pdf));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("versions/v4.docx");
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.CONVERT), anyString(), anyString(), eq(SOURCE_VERSION),
                any(), any())).thenThrow(new BusinessException(409, "版本冲突"));
        when(storage.delete("versions/v4.docx")).thenReturn(true);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.convert(USER_ID, FILE_ID, SOURCE_VERSION,
                        request(PdfConversionMode.EDITABLE), null, null)).getCode());
        verify(storage).delete("versions/v4.docx");
    }

    private static StorageProperties properties() {
        StorageProperties properties = new StorageProperties();
        properties.getUpload().setMaxPdfPages(10);
        properties.getUpload().setMaxImagePixels(10_000_000L);
        properties.getPdf().setMaxExtractedCharsPerPage(100_000);
        properties.getPdf().setMaxExtractedCharsTotal(2_000_000);
        return properties;
    }

    private static ConvertPdfToDocxDTO request(PdfConversionMode mode) {
        ConvertPdfToDocxDTO request = new ConvertPdfToDocxDTO();
        request.setMode(mode);
        request.setInstruction("重建为可编辑文档");
        return request;
    }

    private static DocumentVersion createdVersion(String storageKey) {
        DocumentVersion version = new DocumentVersion();
        version.setId(20L);
        version.setFileId(FILE_ID);
        version.setUserId(USER_ID);
        version.setVersionNo(4);
        version.setParentVersion(SOURCE_VERSION);
        version.setStorageKey(storageKey);
        version.setContentType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        version.setExtension("docx");
        version.setSizeBytes(100L);
        version.setSha256("hash");
        version.setChangeType(ChangeType.CONVERT.name());
        version.setTaskId(88L);
        version.setAttemptNo(2);
        return version;
    }

    private static byte[] createPdf(PdfWriter writer) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.write(document);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void addTextPage(PDDocument document, String text, float x) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(x, 720);
            content.showText(text);
            content.endText();
        }
    }

    private static void addImagePage(PDDocument document, boolean addText) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            if (addText) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Mixed page");
                content.endText();
            }
            var image = LosslessFactory.createFromImage(document, sampleImage());
            content.drawImage(image, 72, 600, 40, 40);
        }
    }

    private static BufferedImage sampleImage() {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 20, 20);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(4, 4, 12, 12);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static FileDownload download(byte[] content) {
        return download(content, true);
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
