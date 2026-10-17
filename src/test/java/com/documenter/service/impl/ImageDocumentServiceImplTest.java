package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditImageDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImageDocumentServiceImplTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private final ImageDocumentServiceImpl service = new ImageDocumentServiceImpl(
            versions, storage, writer, properties);

    @ParameterizedTest
    @CsvSource({"0,80000000", "0.5,800a4b6e", "1,801496dc", "2,8028ffff", "4,8050ffff"})
    void brightnessScalesRgbClampsChannelsAndKeepsAlpha(double factor, String expected) throws Exception {
        var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x801496dc);
        image.setRGB(1, 0, 0x001496dc);
        prepare(image, "png");

        service.edit(7L, 10L, 1, request(brightness(factor)));

        var output = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals((int) Long.parseLong(expected, 16), output.getRGB(0, 0));
        assertEquals(0, output.getRGB(1, 0) >>> 24);
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1));
    }

    @Test
    void textUsesRequestedColorAndInkPositionWhilePreservingOtherPixels() throws Exception {
        var image = new BufferedImage(128, 80, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(120, 70, 0x8044aa66);
        prepare(image, "png");
        var text = text("A", 10, 12);
        text.setFontSize(24);
        text.setColor("#ff0000");

        service.edit(7L, 10L, 1, request(text));

        var output = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals(0x8044aa66, output.getRGB(120, 70));
        assertEquals(0, output.getRGB(0, 0));
        int pixels = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 100; x++) {
                int pixel = output.getRGB(x, y);
                if ((pixel >>> 24) == 0) continue;
                assertEquals(0x00ff0000, pixel & 0x00ffffff);
                assertTrue(x >= 10 && y >= 12);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                pixels++;
            }
        }
        assertTrue(pixels > 0);
        assertEquals(10, minX);
        assertEquals(12, minY);
    }

    @Test
    void textDefaultsToBlackAndFitsAtImageOriginWithoutBaselineClipping() throws Exception {
        prepare(new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB), "png");
        service.edit(7L, 10L, 1, request(text("Ag", 0, 0)));
        var output = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals(100, output.getWidth());
        assertEquals(60, output.getHeight());
        assertTrue(java.util.Arrays.stream(output.getRGB(0, 0, 100, 60, null, 0, 100))
                .anyMatch(pixel -> (pixel >>> 24) > 0));
        assertTrue(java.util.Arrays.stream(output.getRGB(0, 0, 100, 60, null, 0, 100))
                .allMatch(pixel -> (pixel & 0x00ffffff) == 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void brightnessAndTextAreAppliedInRequestedOrder(boolean brightnessFirst) throws Exception {
        prepare(new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB), "png");
        var text = text("A", 5, 5);
        text.setColor("#ff0000");
        var request = brightnessFirst ? request(brightness(0.0), text) : request(text, brightness(0.0));

        service.edit(7L, 10L, 1, request);

        var output = ImageIO.read(new ByteArrayInputStream(stored("png")));
        int[] pixels = output.getRGB(0, 0, 100, 60, null, 0, 100);
        assertTrue(java.util.Arrays.stream(pixels).anyMatch(pixel -> (pixel >>> 24) > 0));
        assertEquals(brightnessFirst, java.util.Arrays.stream(pixels)
                .anyMatch(pixel -> (pixel & 0x00ffffff) != 0));
        verify(storage, times(1)).store(any(byte[].class), eq("png"));
    }

    @Test
    void newOperationsCanBeComposedWithResizeAndJpegConversion() throws Exception {
        prepare(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png");
        var text = text("A", 4, 4);
        text.setColor("#ff0000");
        var request = request(operation("RESIZE", 100, 60, null), brightness(2.0), text);
        request.setOutputFormat("JPEG");

        service.edit(7L, 10L, 1, request);

        var output = ImageIO.read(new ByteArrayInputStream(stored("jpg")));
        assertEquals(100, output.getWidth());
        assertFalse(output.getColorModel().hasAlpha());
        assertEquals(Color.WHITE.getRGB(), output.getRGB(99, 59));
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.CONVERT), any(), any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEYOND_X", "BEYOND_Y", "AFTER_RESIZE", "MISSING_GLYPH"})
    void textRenderingFailureNeverStoresEarlierOperations(String scenario) throws Exception {
        prepare(new BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB), "png");
        var text = text("Too wide", 0, 0);
        var request = request(brightness(2.0), text);
        int expected = 400;
        switch (scenario) {
            case "BEYOND_X" -> text.setX(Integer.MAX_VALUE);
            case "BEYOND_Y" -> text.setY(Integer.MAX_VALUE);
            case "AFTER_RESIZE" -> request.setOperations(List.of(operation("RESIZE", 10, 10, null), text));
            case "MISSING_GLYPH" -> { text.setText("\u0378"); expected = 422; }
            default -> throw new IllegalArgumentException(scenario);
        }

        assertEquals(expected, assertThrows(BusinessException.class,
                () -> service.edit(7L, 10L, 1, request)).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void invalidNewFieldsAreRejectedBeforeReadingSource() {
        for (Double factor : new Double[]{null, -0.1, 4.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertBadRequest(brightness(factor));
        }
        for (String content : new String[]{null, " ", "a\nb", "a\rb", "a\tb", "a\u2028b", "a".repeat(501)}) {
            assertBadRequest(text(content, 0, 0));
        }
        for (Integer fontSize : new Integer[]{0, 513}) {
            var text = text("A", 0, 0);
            text.setFontSize(fontSize);
            assertBadRequest(text);
        }
        for (String color : List.of("red", "#abcd", "#ff000000", "#gg0000")) {
            var text = text("A", 0, 0);
            text.setColor(color);
            assertBadRequest(text);
        }
        assertBadRequest(text("A", -1, 0));
        assertBadRequest(text("A", 0, -1));
        assertBadRequest(text("A", null, 0));
        verifyNoInteractions(versions, storage, writer);
    }

    @Test
    void operationSpecificFieldsCannotBeSilentlyIgnored() {
        var crop = operation("CROP", 1, 1, null);
        crop.setX(0);
        crop.setY(0);
        crop.setFactor(1.0);
        assertBadRequest(crop);
        var resize = operation("RESIZE", 10, 10, null);
        resize.setColor("#ff0000");
        assertBadRequest(resize);
        var rotate = operation("ROTATE", null, null, 90);
        rotate.setText("A");
        assertBadRequest(rotate);
        var brightness = brightness(2.0);
        brightness.setX(0);
        assertBadRequest(brightness);
        var text = text("A", 0, 0);
        text.setFactor(1.0);
        assertBadRequest(text);
        verifyNoInteractions(versions, storage, writer);
    }

    private void assertBadRequest(EditImageDTO.Operation operation) {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.edit(7L, 10L, 1, request(operation))).getCode());
    }

    private static EditImageDTO.Operation brightness(Double factor) {
        var operation = operation("BRIGHTNESS", null, null, null);
        operation.setFactor(factor);
        return operation;
    }

    private static EditImageDTO.Operation text(String content, Integer x, Integer y) {
        var operation = operation("ADD_TEXT", null, null, null);
        operation.setText(content);
        operation.setX(x);
        operation.setY(y);
        return operation;
    }

    @Test
    void cropsAndRotatesInOrderPreservingPngTransparencyAndVersionDigest() throws Exception {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 0, Color.RED.getRGB());
        image.setRGB(2, 0, Color.GREEN.getRGB());
        image.setRGB(1, 1, Color.BLUE.getRGB());
        image.setRGB(2, 1, 0x800000FF);
        prepare(image, "png");
        var crop = operation("CROP", 2, 2, null);
        crop.setX(1);
        crop.setY(0);

        service.edit(7L, 10L, 1, request(crop, operation("ROTATE", null, null, 90)));

        byte[] bytes = stored("png");
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(bytes));
        assertEquals(2, output.getWidth());
        assertEquals(2, output.getHeight());
        assertEquals(Color.BLUE.getRGB(), output.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), output.getRGB(1, 0));
        assertEquals(128, output.getRGB(0, 1) >>> 24);
        var content = ArgumentCaptor.forClass(VersionWriter.VersionContent.class);
        verify(writer).appendVersion(eq(10L), eq(7L), content.capture(), eq(ChangeType.MANUAL_EDIT),
                eq("编辑图片"), any(), eq(1));
        assertEquals("image/png", content.getValue().contentType());
        assertEquals("png", content.getValue().extension());
        assertEquals(bytes.length, content.getValue().sizeBytes());
        assertEquals(Digests.sha256Hex(bytes), content.getValue().sha256());
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 180, 270})
    void rotatesPixelPositionsClockwise(int degrees) throws Exception {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        prepare(image, "png");

        service.edit(7L, 10L, 1, request(operation("ROTATE", null, null, degrees)));

        var result = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals(degrees == 180 ? 3 : 2, result.getWidth());
        assertEquals(degrees == 180 ? 2 : 3, result.getHeight());
        int x = degrees == 90 ? 1 : degrees == 180 ? 2 : 0;
        int y = degrees == 90 ? 0 : degrees == 180 ? 1 : 2;
        assertEquals(Color.RED.getRGB(), result.getRGB(x, y));
    }

    @Test
    void resizesToRequestedDimensions() throws Exception {
        prepare(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png");
        service.edit(7L, 10L, 1, request(operation("RESIZE", 6, 4, null)));
        var result = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals(6, result.getWidth());
        assertEquals(4, result.getHeight());
        assertEquals(0, result.getRGB(0, 0) >>> 24);
    }

    @Test
    void convertsTransparentPngToJpegWithWhiteBackgroundAndCorrectFormat() throws Exception {
        prepare(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png");
        var request = request();
        request.setOutputFormat("JPEG");
        request.setJpegQuality(0.8);

        var version = service.edit(7L, 10L, 1, request);

        var result = ImageIO.read(new ByteArrayInputStream(stored("jpg")));
        assertFalse(result.getColorModel().hasAlpha());
        assertEquals(Color.WHITE.getRGB(), result.getRGB(0, 0));
        assertEquals("image/jpeg", version.getContentType());
        assertEquals("jpg", version.getExtension());
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.CONVERT), any(), any(), eq(1));
    }

    @Test
    void convertsJpegBackToPng() throws Exception {
        prepare(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "jpg");
        var request = request();
        request.setOutputFormat("PNG");
        var version = service.edit(7L, 10L, 1, request);
        var result = ImageIO.read(new ByteArrayInputStream(stored("png")));
        assertEquals(4, result.getWidth());
        assertEquals("image/png", version.getContentType());
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.CONVERT), any(), any(), eq(1));
    }

    @Test
    void jpegQualityAloneReencodesSameFormat() throws Exception {
        prepare(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg");
        var request = request();
        request.setJpegQuality(0.3);
        service.edit(7L, 10L, 1, request);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(stored("jpg"))));
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CROP", "SOURCE_PIXELS", "OUTPUT_PIXELS", "SOURCE_BYTES", "PNG_QUALITY"})
    void rejectsInvalidOrOversizedOutputWithoutSaving(String limitation) throws Exception {
        prepare(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png");
        var request = request(operation("RESIZE", 6, 4, null));
        int expected = 413;
        switch (limitation) {
            case "CROP" -> {
                var crop = operation("CROP", 3, 2, null);
                crop.setX(1);
                crop.setY(0);
                request = request(crop);
                expected = 400;
            }
            case "SOURCE_PIXELS" -> properties.getUpload().setMaxImagePixels(5);
            case "OUTPUT_PIXELS" -> properties.getUpload().setMaxImagePixels(10);
            case "SOURCE_BYTES" -> properties.getUpload().setMaxSizeBytes(1);
            case "PNG_QUALITY" -> {
                request.setJpegQuality(0.5);
                expected = 400;
            }
            default -> throw new IllegalArgumentException(limitation);
        }
        var finalRequest = request;
        var error = assertThrows(BusinessException.class,
                () -> service.edit(7L, 10L, 1, finalRequest));
        assertEquals(expected, error.getCode());
        verify(storage, never()).store(any(byte[].class), any());
    }

    @Test
    void rejectsEncodedOutputLargerThanLimitWithoutSaving() throws Exception {
        prepare(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png");
        properties.getUpload().setMaxSizeBytes(100);
        var request = request(operation("RESIZE", 100, 100, null));
        request.setOutputFormat("JPEG");

        var error = assertThrows(BusinessException.class,
                () -> service.edit(7L, 10L, 1, request));

        assertEquals(413, error.getCode());
        assertEquals("编辑后的图片超过文件大小限制", error.getMessage());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void rejectsCorruptSourceAndInvalidFields() {
        FileAsset asset = new FileAsset();
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(new byte[]{1, 2}), 2, "image/png", "bad.png"));
        assertEquals(422, assertThrows(BusinessException.class, () -> service.edit(7L, 10L, 1,
                request(operation("ROTATE", null, null, 90)))).getCode());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.edit(7L, 10L, 1,
                request(operation("ROTATE", null, null, 45)))).getCode());
        verify(storage, never()).store(any(byte[].class), any());
    }

    @Test
    void ownershipFailureStopsEditingBeforeStorage() {
        when(versions.downloadVersion(7L, 10L, 1)).thenThrow(new BusinessException(404, "文件不存在"));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.edit(7L, 10L, 1,
                request(operation("ROTATE", null, null, 90)))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void cleansUpConvertedOutputOnVersionConflict() throws Exception {
        prepare(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png");
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        when(storage.delete("new.jpg")).thenReturn(true);
        var request = request();
        request.setOutputFormat("JPEG");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.edit(7L, 10L, 1, request)).getCode());
        verify(storage).delete("new.jpg");
    }

    private void prepare(BufferedImage image, String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        byte[] bytes = output.toByteArray();
        FileAsset asset = new FileAsset();
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(bytes), bytes.length,
                "png".equals(format) ? "image/png" : "image/jpeg", "original." + format));
        when(storage.store(any(byte[].class), any())).thenAnswer(call -> "new." + call.getArgument(1));
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1)))
                .thenAnswer(call -> {
                    VersionWriter.VersionContent content = call.getArgument(2);
                    DocumentVersion version = new DocumentVersion();
                    version.setVersionNo(2);
                    version.setContentType(content.contentType());
                    version.setExtension(content.extension());
                    return version;
                });
    }

    private byte[] stored(String extension) {
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq(extension));
        return bytes.getValue();
    }

    private static EditImageDTO request(EditImageDTO.Operation... operations) {
        var request = new EditImageDTO();
        request.setInstruction("编辑图片");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditImageDTO.Operation operation(String type, Integer width,
                                                    Integer height, Integer degrees) {
        var operation = new EditImageDTO.Operation();
        operation.setType(type);
        operation.setWidth(width);
        operation.setHeight(height);
        operation.setDegrees(degrees);
        return operation;
    }
}
