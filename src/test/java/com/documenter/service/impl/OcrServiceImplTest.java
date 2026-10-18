package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.OcrRequestDTO;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OcrEngine;
import com.documenter.vo.FileDownload;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 2;

    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final OcrEngine engine = mock(OcrEngine.class);
    private final OcrProperties properties = properties();
    private final StorageProperties storage = storage();
    private final OcrServiceImpl service = new OcrServiceImpl(
            versions, engine, properties, storage);

    @Test
    void preprocessesRegionAndMapsRotatedLinesAndTablesToOriginalPixels() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(png(), "png", "image/png"));
        when(engine.available()).thenReturn(true);
        when(engine.name()).thenReturn("cloud-vision");
        when(engine.recognize(any(byte[].class), any())).thenAnswer(invocation -> {
            var input = ImageIO.read(new java.io.ByteArrayInputStream(invocation.getArgument(0)));
            assertEquals(12, input.getWidth());
            assertEquals(30, input.getHeight());
            return new OcrEngine.Result(List.of(new OcrEngine.Line("旋转文字", 2, 3, 5, 8, 90, false)),
                    List.of(new OcrEngine.Table(1, 1, 90, false, List.of(
                            new OcrEngine.Cell(0, 0, 1, 1, "单元格", 2, 3, 5, 8, 90, false)))), false);
        });
        var request = new OcrRequestDTO();
        request.setRotationDegrees(90);
        request.setLowLight(true);
        request.setDenoise(true);
        request.setSharpen(true);
        var region = new OcrRequestDTO.Region();
        region.setX(5); region.setY(4); region.setWidth(30); region.setHeight(12);
        request.setRegion(region);
        var page = service.recognize(USER_ID, FILE_ID, VERSION, request).pages().getFirst();
        var line = page.lines().getFirst();
        assertEquals(8, line.left());
        assertEquals(9, line.top());
        assertEquals(8, line.width());
        assertEquals(5, line.height());
        var cell = page.tables().getFirst().cells().getFirst();
        assertEquals(line.left(), cell.left());
        assertEquals(line.top(), cell.top());
        assertEquals(line.width(), cell.width());
        assertEquals(line.height(), cell.height());
        assertEquals(30, page.region().width());
    }

    @Test
    void invalidRotationFailsBeforeReadingOrCallingCloud() {
        var request = new OcrRequestDTO();
        request.setRotationDegrees(-90);
        assertThrows(BusinessException.class, () -> service.recognize(USER_ID, FILE_ID, VERSION, request));
        verify(versions, never()).downloadVersion(any(), any(), any());
        verify(engine, never()).recognize(any(), any());
    }

    @Test
    void recognizesImageAndReturnsCoordinatesAndReviewFlag() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(png(), "png", "image/png"));
        when(engine.available()).thenReturn(true);
        when(engine.name()).thenReturn("cloud-vision");
        when(engine.recognize(any(byte[].class), eq("zh"))).thenReturn(
                new OcrEngine.Result(List.of(
                        new OcrEngine.Line("待复核", 3, 4, 20, 10, 42, true)),
                        List.of(new OcrEngine.Table(1, 1, 42, true,
                                List.of(new OcrEngine.Cell(0, 0, 1, 1,
                                        "待复核", 3, 4, 20, 10, 42, true)))), false));
        var request = new OcrRequestDTO();
        request.setLanguage("zh");
        request.setPages(List.of(1));
        var region = new OcrRequestDTO.Region();
        region.setX(5);
        region.setY(4);
        region.setWidth(30);
        region.setHeight(12);
        request.setRegion(region);

        var result = service.recognize(USER_ID, FILE_ID, VERSION, request);

        assertEquals("cloud-vision", result.engine());
        assertTrue(result.fingerprint().matches("^[a-f0-9]{64}$"));
        assertEquals(1, result.pages().size());
        assertEquals("OCR", result.pages().getFirst().source());
        assertEquals(42, result.pages().getFirst().confidence());
        assertTrue(result.pages().getFirst().needsReview());
        assertEquals(5, result.pages().getFirst().region().x());
        assertEquals(30, result.pages().getFirst().region().width());
        assertEquals(8, result.pages().getFirst().lines().getFirst().left());
        assertEquals(8, result.pages().getFirst().lines().getFirst().top());
        assertEquals(1, result.pages().getFirst().paragraphs().size());
        assertEquals(1, result.pages().getFirst().tables().size());
        assertEquals(8, result.pages().getFirst().tables().getFirst()
                .cells().getFirst().left());
        assertEquals(8, result.pages().getFirst().tables().getFirst()
                .cells().getFirst().top());
    }

    @Test
    void reusesNativePdfTextWithoutCallingCloudEngine() throws Exception {
        byte[] pdf = textPdf("Native text");
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(pdf, "pdf", "application/pdf"));
        when(engine.name()).thenReturn("cloud-vision");

        var result = service.recognize(USER_ID, FILE_ID, VERSION, new OcrRequestDTO());

        assertEquals(1, result.pages().size());
        assertEquals("NATIVE", result.pages().getFirst().source());
        assertTrue(result.pages().getFirst().text().contains("Native text"));
        assertEquals(0, result.renderedPages());
        verify(engine, never()).recognize(any(byte[].class), any());
    }

    @Test
    void regionRequestOverridesNativeTextAndMapsCoordinatesToFullPage() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(textPdf("Native text"), "pdf", "application/pdf"));
        when(engine.available()).thenReturn(true);
        when(engine.name()).thenReturn("cloud-vision");
        when(engine.recognize(any(byte[].class), eq("en"))).thenReturn(
                new OcrEngine.Result(List.of(
                        new OcrEngine.Line("区域文字", 2, 3, 20, 10, 95, false)), false));
        var request = new OcrRequestDTO();
        request.setLanguage("en");
        var region = new OcrRequestDTO.Region();
        region.setX(10);
        region.setY(12);
        region.setWidth(120);
        region.setHeight(80);
        request.setRegion(region);

        var result = service.recognize(USER_ID, FILE_ID, VERSION, request);

        assertEquals("OCR", result.pages().getFirst().source());
        assertEquals(1, result.renderedPages());
        assertEquals(10, result.pages().getFirst().region().x());
        assertEquals(12, result.pages().getFirst().lines().getFirst().left());
        assertEquals(15, result.pages().getFirst().lines().getFirst().top());
    }

    @Test
    void groupsSpatialLinesIntoParagraphsByVerticalGap() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(png(), "png", "image/png"));
        when(engine.available()).thenReturn(true);
        when(engine.name()).thenReturn("cloud-vision");
        when(engine.recognize(any(byte[].class), eq("auto"))).thenReturn(
                new OcrEngine.Result(List.of(
                        new OcrEngine.Line("第一段第一行", 2, 2, 25, 8, 95, false),
                        new OcrEngine.Line("第一段第二行", 2, 12, 25, 8, 94, false),
                        new OcrEngine.Line("第二段", 2, 30, 18, 8, 93, false)), false));

        var result = service.recognize(
                USER_ID, FILE_ID, VERSION, new OcrRequestDTO());

        assertEquals(2, result.pages().getFirst().paragraphs().size());
        assertEquals("第一段第一行\n第一段第二行",
                result.pages().getFirst().paragraphs().get(0).text());
        assertEquals("第二段", result.pages().getFirst().paragraphs().get(1).text());
    }

    @Test
    void contrastEnhancementPreservesDimensionsAndExpandsGrayRange() {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int gray = x < 10 ? 100 : 120;
                image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
            }
        }

        BufferedImage enhanced = OcrServiceImpl.enhance(image);

        assertEquals(20, enhanced.getWidth());
        assertEquals(20, enhanced.getHeight());
        assertTrue((enhanced.getRGB(0, 0) & 0xff)
                < (enhanced.getRGB(19, 0) & 0xff));
        assertTrue((enhanced.getRGB(19, 0) & 0xff) - (enhanced.getRGB(0, 0) & 0xff)
                > 20);
    }

    @Test
    void rejectsScannedPdfWhenCloudModelIsUnavailable() throws Exception {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(imagePdf(), "pdf", "application/pdf"));
        when(engine.available()).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recognize(USER_ID, FILE_ID, VERSION, new OcrRequestDTO()));

        assertEquals(503, error.getCode());
        assertTrue(error.getMessage().contains("云端 OCR"));
    }

    @Test
    void validatesLanguageAndPageBoundsBeforeCallingEngine() throws Exception {
        var invalidLanguage = new OcrRequestDTO();
        invalidLanguage.setLanguage("zh+xx");
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(textPdf("Text"), "pdf", "application/pdf"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.recognize(USER_ID, FILE_ID, VERSION,
                        invalidLanguage)).getCode());

        var badPage = new OcrRequestDTO();
        badPage.setPages(List.of(2));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.recognize(USER_ID, FILE_ID, VERSION,
                        badPage)).getCode());

        var badRegionRequest = new OcrRequestDTO();
        var badRegion = new OcrRequestDTO.Region();
        badRegion.setX(30);
        badRegion.setY(0);
        badRegion.setWidth(20);
        badRegion.setHeight(20);
        badRegionRequest.setRegion(badRegion);
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION))
                .thenReturn(download(png(), "png", "image/png"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.recognize(USER_ID, FILE_ID, VERSION,
                        badRegionRequest)).getCode());
    }

    private static OcrProperties properties() {
        OcrProperties properties = new OcrProperties();
        properties.setDefaultLanguage("auto");
        properties.setDpi(144);
        properties.setMaxDpi(300);
        properties.setMaxPages(20);
        return properties;
    }

    private static StorageProperties storage() {
        StorageProperties properties = new StorageProperties();
        properties.getUpload().setMaxImagePixels(100_000_000L);
        properties.getPdf().setMaxExtractedCharsPerPage(100_000);
        return properties;
    }

    private static FileDownload download(byte[] content, String extension,
                                         String contentType) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setExtension(extension);
        asset.setContentType(contentType);
        return new FileDownload(asset, new ByteArrayResource(content),
                content.length, contentType, "source." + extension);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] imagePdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, 40, 20);
            } finally {
                graphics.dispose();
            }
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image),
                        72, 600, 40, 20);
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
