package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
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

class XlsxDocumentServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int SOURCE_VERSION = 1;

    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final VersionWriter versionWriter = mock(VersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private final XlsxDocumentServiceImpl service = new XlsxDocumentServiceImpl(
            versionService, fileStorage, versionWriter, properties);

    @Test
    void readsSheetsCellsStylesFormulasMergesAndCharts() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var row = sheet.createRow(0);
            var text = row.createCell(0);
            text.setCellValue("名称");
            var style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setWrapText(true);
            var font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            text.setCellStyle(style);
            row.createCell(1).setCellValue(12.5);
            row.createCell(2).setCellFormula("B1*2");
            sheet.createRow(1).createCell(0).setCellValue(LocalDate.of(2026, 10, 11));
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(2, 2, 0, 1));
            var drawing = sheet.createDrawingPatriarch();
            var chart = drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 4, 1, 10, 15));
            chart.setTitleText("示例图表");
            workbook.createSheet("汇总");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var result = service.readStructure(USER_ID, FILE_ID, SOURCE_VERSION);

        assertEquals(2, result.totalSheets());
        assertFalse(result.truncated());
        assertEquals("数据", result.sheets().getFirst().name());
        assertEquals(List.of("A3:B3"), result.sheets().getFirst().mergedRegions());
        assertEquals("示例图表", result.sheets().getFirst().charts().getFirst().title());
        var first = result.sheets().getFirst().cells().getFirst();
        assertEquals("A1", first.address());
        assertEquals("STRING", first.type());
        assertEquals("名称", first.value());
        assertEquals("CENTER", first.style().horizontalAlignment());
        assertTrue(first.style().wrapText());
        assertTrue(first.style().bold());
        var formula = result.sheets().getFirst().cells().stream()
                .filter(cell -> "C1".equals(cell.address())).findFirst().orElseThrow();
        assertEquals("FORMULA", formula.type());
        assertEquals("B1*2", formula.formula());
        assertEquals("NUMERIC", formula.cachedValueType());
    }

    @Test
    void truncatesCellOutputAtConfiguredLimit() throws Exception {
        properties.getXlsx().setMaxReadCells(1);
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("数据").createRow(0);
            row.createCell(0).setCellValue("一");
            row.createCell(1).setCellValue("二");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var result = service.readStructure(USER_ID, FILE_ID, SOURCE_VERSION);

        assertEquals(1, result.includedCells());
        assertTrue(result.truncated());
    }

    @Test
    void editsTypedValuesFormulaAndClearIntoReopenableVersion() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("数据").createRow(0).createCell(0);
            cell.setCellValue("待清空");
            var style = workbook.createCellStyle();
            style.setWrapText(true);
            cell.setCellStyle(style);
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        EditXlsxCellsDTO request = request(
                clear("A1"),
                value("B1", "STRING", "=SUM(1,2)"),
                value("C1", "NUMBER", "12.5"),
                value("D1", "BOOLEAN", "true"),
                value("E1", "DATE", "2026-10-11"),
                formula("F1", "SUM(C1,2)"));

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var row = workbook.getSheet("数据").getRow(0);
            assertEquals(CellType.BLANK, row.getCell(0).getCellType());
            assertTrue(row.getCell(0).getCellStyle().getWrapText());
            assertEquals(CellType.STRING, row.getCell(1).getCellType());
            assertEquals("=SUM(1,2)", row.getCell(1).getStringCellValue());
            assertEquals(12.5, row.getCell(2).getNumericCellValue());
            assertTrue(row.getCell(3).getBooleanCellValue());
            assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(row.getCell(4)));
            assertEquals("SUM(C1,2)", row.getCell(5).getCellFormula());
            assertTrue(workbook.getForceFormulaRecalculation());
        }
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                eq("更新数据"), eq("编辑 6 个 XLSX 单元格"), eq(SOURCE_VERSION));
    }

    @Test
    void rejectsExternalOrNetworkFormulaWithoutStoringOutput() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        EditXlsxCellsDTO request = request(formula("A1", "WEBSERVICE(\"https://example.com\")"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void rejectsEditingNonAnchorCellInMergedRegion() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION,
                        request(value("B1", "STRING", "不可见"))));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    private void prepareEdit(byte[] source) {
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        when(fileStorage.store(any(byte[].class), eq("xlsx"))).thenReturn("versions/v2.xlsx");
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION))).thenReturn(createdVersion());
    }

    private byte[] storedBytes() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorage).store(captor.capture(), eq("xlsx"));
        assertTrue(captor.getValue().length > 0);
        return captor.getValue();
    }

    private static EditXlsxCellsDTO request(EditXlsxCellsDTO.Operation... operations) {
        EditXlsxCellsDTO request = new EditXlsxCellsDTO();
        request.setInstruction("更新数据");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditXlsxCellsDTO.Operation clear(String cell) {
        EditXlsxCellsDTO.Operation operation = base("CLEAR", cell);
        return operation;
    }

    private static EditXlsxCellsDTO.Operation value(String cell, String type, String value) {
        EditXlsxCellsDTO.Operation operation = base("SET_VALUE", cell);
        operation.setValueType(type);
        operation.setValue(value);
        return operation;
    }

    private static EditXlsxCellsDTO.Operation formula(String cell, String formula) {
        EditXlsxCellsDTO.Operation operation = base("SET_FORMULA", cell);
        operation.setFormula(formula);
        return operation;
    }

    private static EditXlsxCellsDTO.Operation base(String type, String cell) {
        EditXlsxCellsDTO.Operation operation = new EditXlsxCellsDTO.Operation();
        operation.setType(type);
        operation.setSheetName("数据");
        operation.setCell(cell);
        return operation;
    }

    private static FileDownload download(byte[] content) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setDisplayName("source.xlsx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        asset.setExtension("xlsx");
        return new FileDownload(asset, new ByteArrayResource(content), content.length,
                asset.getContentType(), asset.getDisplayName());
    }

    private static DocumentVersion createdVersion() {
        DocumentVersion version = new DocumentVersion();
        version.setId(20L);
        version.setFileId(FILE_ID);
        version.setUserId(USER_ID);
        version.setVersionNo(2);
        version.setParentVersion(SOURCE_VERSION);
        version.setStorageKey("versions/v2.xlsx");
        version.setSizeBytes(1L);
        version.setSha256("hash");
        version.setChangeType(ChangeType.MANUAL_EDIT.name());
        return version;
    }
}
