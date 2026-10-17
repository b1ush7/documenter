package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.InsertXlsxImagesDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XlsxImageInsertionTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private final XlsxDocumentServiceImpl service = new XlsxDocumentServiceImpl(versions, storage, writer, properties);

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg"})
    void embedsPinnedImageWithExactSizeAndSourceWithoutChangingCellsStylesOrCharts(String format) throws Exception {
        byte[] original = source(false);
        byte[] image = image(format, 0xffff0000);
        prepare(original);
        prepareImage(30L, image, format);

        var result = service.insertImages(7L, 10L, 1, request(operation("数据", "B3", 30L)));

        assertEquals(2, result.getVersionNo());
        byte[] saved = stored();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(saved))) {
            var sheet = workbook.getSheet("数据");
            assertEquals(11, sheet.getRow(0).getCell(0).getNumericCellValue());
            assertTrue(workbook.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold());
            assertEquals("A1*2", sheet.getRow(0).getCell(1).getCellFormula());
            assertEquals(22, sheet.getRow(0).getCell(1).getNumericCellValue());
            assertEquals("D3:E4", sheet.getMergedRegion(0).formatAsString());
            assertEquals(20 * 256, sheet.getColumnWidth(1));
            assertEquals(30, sheet.getRow(2).getHeightInPoints());
            var drawing = sheet.getDrawingPatriarch();
            assertEquals(1, drawing.getCharts().size());
            var pictures = drawing.getShapes().stream().filter(XSSFPicture.class::isInstance)
                    .map(XSSFPicture.class::cast).toList();
            assertEquals(2, pictures.size());
            assertArrayEquals(image("png", 0xff0000ff), pictures.getFirst().getPictureData().getData());
            var inserted = pictures.get(1);
            assertArrayEquals(image, inserted.getPictureData().getData());
            assertEquals("image/" + ("png".equals(format) ? "png" : "jpeg"), inserted.getPictureData().getMimeType());
            assertEquals(1, inserted.getClientAnchor().getCol1());
            assertEquals(2, inserted.getClientAnchor().getRow1());
            assertEquals(Units.pixelToEMU(80), inserted.getCTPicture().getSpPr().getXfrm().getExt().getCx());
            assertEquals(Units.pixelToEMU(40), inserted.getCTPicture().getSpPr().getXfrm().getExt().getCy());
            assertEquals("Documenter imageFileId=30; imageVersionNo=2", inserted.getCTPicture().getNvPicPr().getCNvPr().getDescr());
            assertFalse(inserted.getCTPicture().getBlipFill().getBlip().getEmbed().isEmpty());
            assertFalse(inserted.getCTPicture().getBlipFill().getBlip().isSetLink());
            assertEquals("oneCell", drawing.getCTDrawing().getTwoCellAnchorArray(2).getEditAs().toString());
        }
        var content = ArgumentCaptor.forClass(VersionWriter.VersionContent.class);
        verify(writer).appendVersion(eq(10L), eq(7L), content.capture(), eq(ChangeType.MANUAL_EDIT),
                eq("插入图片"), any(), eq(1));
        assertEquals(saved.length, content.getValue().sizeBytes());
        assertEquals(Digests.sha256Hex(saved), content.getValue().sha256());
        verify(versions).downloadVersion(7L, 30L, 2);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(original))) {
            assertEquals(1, workbook.getAllPictures().size());
        }
    }

    @Test
    void batchAcrossSheetsSavesOnceAndDoesNotCreateAnchorCells() throws Exception {
        prepare(source(false));
        prepareImage(30L, image("png", 0xffff0000), "png");
        prepareImage(31L, image("jpg", 0xff00ff00), "jpg");

        service.insertImages(7L, 10L, 1, request(operation("数据", "F20", 30L), operation("说明", "C5", 31L)));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(stored()))) {
            assertNull(workbook.getSheet("数据").getRow(19));
            assertNull(workbook.getSheet("说明").getRow(4));
            assertEquals(3, workbook.getAllPictures().size());
            var picture = (XSSFPicture) workbook.getSheet("说明").getDrawingPatriarch().getShapes().getFirst();
            assertEquals(2, picture.getClientAnchor().getCol1());
            assertEquals(4, picture.getClientAnchor().getRow1());
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1));
    }

    @Test
    void hiddenColumnsAndRowsDoNotConsumeVisibleImageDimensions() throws Exception {
        byte[] source;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(source(false)));
             var output = new ByteArrayOutputStream()) {
            workbook.getSheet("数据").setColumnHidden(1, true);
            workbook.getSheet("数据").getRow(2).setZeroHeight(true);
            workbook.write(output);
            source = output.toByteArray();
        }
        prepare(source);
        prepareImage(30L, image("png", 0xffff0000), "png");

        service.insertImages(7L, 10L, 1, request(operation("数据", "B3", 30L)));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(stored()))) {
            var drawing = workbook.getSheet("数据").getDrawingPatriarch();
            var picture = (XSSFPicture) drawing.getShapes().getLast();
            assertTrue(picture.getClientAnchor().getCol2() > 1);
            assertTrue(picture.getClientAnchor().getRow2() > 2);
            assertEquals(Units.pixelToEMU(80), picture.getCTPicture().getSpPr().getXfrm().getExt().getCx());
            assertEquals(Units.pixelToEMU(40), picture.getCTPicture().getSpPr().getXfrm().getExt().getCy());
        }
    }

    @Test
    void secondImageOwnershipFailureDoesNotSaveFirstInsertion() throws Exception {
        prepare(source(false));
        prepareImage(30L, image("png", 0xffff0000), "png");
        when(versions.downloadVersion(7L, 31L, 2)).thenThrow(new BusinessException(404, "图片不存在"));

        assertCode(404, () -> service.insertImages(7L, 10L, 1,
                request(operation("数据", "B3", 30L), operation("数据", "D8", 31L))));

        verify(versions).downloadVersion(7L, 30L, 2);
        verifyNoInteractions(storage, writer);
    }

    @Test
    void sourceOwnershipFailureDoesNotReadAnyImage() {
        when(versions.downloadVersion(7L, 10L, 1)).thenThrow(new BusinessException(404, "文件不存在"));
        assertCode(404, () -> service.insertImages(7L, 10L, 1, request(operation("数据", "B3", 30L))));
        verify(versions).downloadVersion(7L, 10L, 1);
        verifyNoMoreInteractions(versions);
        verifyNoInteractions(storage, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING_SHEET", "PROTECTED", "X_BOUNDARY", "Y_BOUNDARY", "MIME", "MISMATCH", "CORRUPT", "PIXELS", "IMAGE_BYTES", "TOTAL_BYTES", "OUTPUT_BYTES"})
    void rejectsInvalidTargetsAndImagesWithoutSaving(String reason) throws Exception {
        byte[] source = source("PROTECTED".equals(reason));
        prepare(source);
        byte[] png = image("png", 0xffff0000);
        prepareImage(30L, png, "png");
        var operation = operation("数据", "B3", 30L);
        var request = request(operation);
        int expected = 400;
        switch (reason) {
            case "MISSING_SHEET" -> { operation.setSheetName("不存在"); expected = 404; }
            case "PROTECTED" -> expected = 422;
            case "X_BOUNDARY" -> { operation.setCell("XFD1"); operation.setWidthPixels(10000); }
            case "Y_BOUNDARY" -> { operation.setCell("A1048576"); operation.setHeightPixels(10000); }
            case "MIME" -> when(versions.downloadVersion(7L, 30L, 2)).thenReturn(download(30L, png, "image/gif"));
            case "MISMATCH" -> { prepareImage(30L, image("jpg", 0xffff0000), "png"); expected = 422; }
            case "CORRUPT" -> { prepareImage(30L, new byte[]{1, 2}, "png"); expected = 422; }
            case "PIXELS" -> { properties.getUpload().setMaxImagePixels(3); expected = 413; }
            case "IMAGE_BYTES" -> { properties.getUpload().setMaxSizeBytes(1); expected = 413; }
            case "TOTAL_BYTES" -> {
                properties.getUpload().setMaxSizeBytes(png.length * 2L - 1);
                request.setOperations(List.of(operation, operation("数据", "D8", 30L)));
                expected = 413;
            }
            case "OUTPUT_BYTES" -> { properties.getUpload().setMaxSizeBytes(source.length); expected = 413; }
            default -> throw new IllegalArgumentException(reason);
        }

        assertCode(expected, () -> service.insertImages(7L, 10L, 1, request));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void verifiesActualReadSizeWhenResourceLengthUnderstatesBytes() throws Exception {
        prepare(source(false));
        byte[] png = image("png", 0xffff0000);
        properties.getUpload().setMaxSizeBytes(png.length - 1);
        var asset = new FileAsset();
        when(versions.downloadVersion(7L, 30L, 2)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(png), 1, "image/png", "image.png"));

        assertCode(413, () -> service.insertImages(7L, 10L, 1, request(operation("数据", "B3", 30L))));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void invalidRequestsFailBeforeAccessingSourceOrImages() {
        assertCode(400, () -> service.insertImages(7L, 10L, 1, null));
        assertCode(400, () -> service.insertImages(7L, 10L, 1, request()));
        assertCode(400, () -> service.insertImages(7L, 10L, 1, request((InsertXlsxImagesDTO.Operation) null)));
        for (String cell : List.of("XFE1", "A1048577", "A0", "Sheet1!A1", "A1:B2")) {
            assertCode(400, () -> service.insertImages(7L, 10L, 1, request(operation("数据", cell, 30L))));
        }
        var operation = operation("数据", "B3", 30L);
        operation.setWidthPixels(0);
        assertCode(400, () -> service.insertImages(7L, 10L, 1, request(operation)));
        operation.setWidthPixels(80);
        operation.setImageVersionNo(0);
        assertCode(400, () -> service.insertImages(7L, 10L, 1, request(operation)));
        var request = request();
        request.setOperations(java.util.Collections.nCopies(21, operation));
        assertCode(400, () -> service.insertImages(7L, 10L, 1, request));
        verifyNoInteractions(versions, storage, writer);
    }

    @Test
    void versionConflictCleansUpEmbeddedOutput() throws Exception {
        prepare(source(false));
        prepareImage(30L, image("png", 0xffff0000), "png");
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        when(storage.delete("new.xlsx")).thenReturn(true);

        assertCode(409, () -> service.insertImages(7L, 10L, 1, request(operation("数据", "B3", 30L))));
        verify(storage).delete("new.xlsx");
    }

    private void prepare(byte[] source) {
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(download(10L, source,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        when(storage.store(any(byte[].class), eq("xlsx"))).thenReturn("new.xlsx");
        var version = new DocumentVersion();
        version.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1)))
                .thenReturn(version);
    }

    private void prepareImage(Long id, byte[] image, String format) {
        when(versions.downloadVersion(7L, id, 2)).thenReturn(download(id, image,
                "image/" + ("jpg".equals(format) ? "jpeg" : format)));
    }

    private byte[] stored() {
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("xlsx"));
        return bytes.getValue();
    }

    private static InsertXlsxImagesDTO request(InsertXlsxImagesDTO.Operation... operations) {
        var request = new InsertXlsxImagesDTO();
        request.setInstruction("插入图片");
        request.setOperations(Arrays.asList(operations));
        return request;
    }

    private static InsertXlsxImagesDTO.Operation operation(String sheet, String cell, Long imageId) {
        var operation = new InsertXlsxImagesDTO.Operation();
        operation.setSheetName(sheet);
        operation.setCell(cell);
        operation.setImageFileId(imageId);
        operation.setImageVersionNo(2);
        operation.setWidthPixels(80);
        operation.setHeightPixels(40);
        return operation;
    }

    private static FileDownload download(Long id, byte[] bytes, String mime) {
        var asset = new FileAsset();
        asset.setId(id);
        asset.setUserId(7L);
        asset.setLatestVersion(9);
        return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length, mime, "source");
    }

    private static byte[] source(boolean protectedSheet) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            workbook.createSheet("说明");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(11);
            var style = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            row.getCell(0).setCellStyle(style);
            row.createCell(1).setCellFormula("A1*2");
            row.getCell(1).setCellValue(22);
            sheet.addMergedRegion(CellRangeAddress.valueOf("D3:E4"));
            sheet.setColumnWidth(1, 20 * 256);
            sheet.createRow(2).setHeightInPoints(30);
            var drawing = sheet.createDrawingPatriarch();
            var oldAnchor = drawing.createAnchor(0, 0, 0, 0, 8, 0, 9, 3);
            drawing.createPicture(oldAnchor, workbook.addPicture(image("png", 0xff0000ff), Workbook.PICTURE_TYPE_PNG));
            drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 11, 0, 14, 8));
            if (protectedSheet) sheet.protectSheet("secret");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] image(String format, int color) throws Exception {
        var image = new BufferedImage(2, 2, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }

    private static void assertCode(int code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getCode());
    }
}
