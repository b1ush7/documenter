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
