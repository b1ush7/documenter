package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.EditXlsxDataDTO;
import com.documenter.dto.EditXlsxSheetsDTO;
import com.documenter.dto.EditXlsxStructureDTO;
import com.documenter.dto.FormatXlsxCellsDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void readsRectangularRangeIncludingMissingBlankCells() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("左上");
            sheet.createRow(1).createCell(2).setCellValue("右下");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var result = service.readRange(USER_ID, FILE_ID, SOURCE_VERSION, "数据", "A1:C2");

        assertEquals(2, result.rowCount());
        assertEquals(3, result.columnCount());
        assertEquals("左上", result.rows().get(0).get(0).value());
        assertEquals("BLANK", result.rows().get(0).get(1).type());
        assertEquals("B1", result.rows().get(0).get(1).address());
        assertEquals("右下", result.rows().get(1).get(2).value());
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
    void writesTypedRectangularRangeIntoReopenableVersion() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(1).createCell(1).setCellValue("待清空");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        EditXlsxCellsDTO.Operation operation = new EditXlsxCellsDTO.Operation();
        operation.setType("SET_RANGE");
        operation.setSheetName("数据");
        operation.setRange("A1:B2");
        operation.setValues(List.of(
                List.of(rangeValue("STRING", "=普通文本"),
                        rangeValue("FORMULA", "SUM(1,2)")),
                List.of(rangeValue("DATE", "2026-10-11"),
                        rangeValue("BLANK", null))));
        EditXlsxCellsDTO request = request(operation);

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals(CellType.STRING, sheet.getRow(0).getCell(0).getCellType());
            assertEquals("=普通文本", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("SUM(1,2)", sheet.getRow(0).getCell(1).getCellFormula());
            assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(
                    sheet.getRow(1).getCell(0)));
            assertEquals(CellType.BLANK, sheet.getRow(1).getCell(1).getCellType());
        }
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                eq("更新数据"), eq("编辑 4 个 XLSX 单元格"), eq(SOURCE_VERSION));
    }

    @Test
    void insertsRowsAndColumnsAndKeepsMovedFormulaAndStyles() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var first = sheet.createRow(0);
            first.createCell(0).setCellValue("左");
            first.createCell(1).setCellValue("右");
            first.createCell(2).setCellFormula("A1&B1");
            var style = workbook.createCellStyle();
            style.setWrapText(true);
            first.getCell(0).setCellStyle(style);
            sheet.createRow(1).createCell(0).setCellValue("数据行");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        EditXlsxStructureDTO request = structureRequest(
                structureOperation("INSERT_ROWS", 2, 1),
                structureOperation("INSERT_COLUMNS", 2, 1));

        service.editStructure(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals("左", sheet.getRow(0).getCell(0).getStringCellValue());
            assertNull(sheet.getRow(0).getCell(1));
            assertEquals("右", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("A1&C1", sheet.getRow(0).getCell(3).getCellFormula());
            assertEquals("数据行", sheet.getRow(2).getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(1).getCell(0).getCellStyle().getWrapText());
        }
    }

    @Test
    void deletesRowsAndColumnsAndMovesRemainingCells() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                row.createCell(0).setCellValue("A" + (rowIndex + 1));
                row.createCell(1).setCellValue("B" + (rowIndex + 1));
                row.createCell(2).setCellValue("C" + (rowIndex + 1));
            }
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        EditXlsxStructureDTO request = structureRequest(
                structureOperation("DELETE_ROWS", 2, 1),
                structureOperation("DELETE_COLUMNS", 2, 1));

        service.editStructure(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals("A3", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("C3", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(2, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void rejectsStructuralEditThatSplitsMergedRegion() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("合并");
            sheet.createRow(1);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 1, 0, 0));
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editStructure(USER_ID, FILE_ID, SOURCE_VERSION,
                        structureRequest(structureOperation("INSERT_ROWS", 2, 1))));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void shiftsWholeMergedRegionWhenRowsAreInsertedAtItsBoundary() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("表头");
            sheet.createRow(1).createCell(0).setCellValue("合并内容");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, 1));
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);

        service.editStructure(USER_ID, FILE_ID, SOURCE_VERSION,
                structureRequest(structureOperation("INSERT_ROWS", 2, 1)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            assertEquals("A3:B3", workbook.getSheet("数据").getMergedRegion(0).formatAsString());
            assertEquals("合并内容", workbook.getSheet("数据").getRow(2)
                    .getCell(0).getStringCellValue());
        }
    }

    @Test
    void rejectsStructuralRequestOverConfiguredTotal() {
        properties.getXlsx().setMaxStructuralUnits(1);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editStructure(USER_ID, FILE_ID, SOURCE_VERSION,
                        structureRequest(structureOperation("INSERT_ROWS", 1, 2))));

        assertEquals(413, error.getCode());
        verify(versionService, never()).downloadVersion(any(), any(), any());
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

    @Test
    void createsRenamesAndDeletesSheetsInRequestOrder() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据");
            workbook.createSheet("待删除");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        EditXlsxSheetsDTO request = sheetRequest(
                sheetOperation("CREATE", "中间", null, 2),
                sheetOperation("RENAME", "数据", "原始数据", null),
                sheetOperation("DELETE", "待删除", null, null));

        service.editSheets(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            assertEquals(2, workbook.getNumberOfSheets());
            assertEquals("原始数据", workbook.getSheetName(0));
            assertEquals("中间", workbook.getSheetName(1));
            assertNull(workbook.getSheet("待删除"));
        }
    }

    @Test
    void rejectsDeletingLastWorksheet() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editSheets(USER_ID, FILE_ID, SOURCE_VERSION,
                        sheetRequest(sheetOperation("DELETE", "数据", null, null))));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void sortsValueRangeStablyAndMovesStylesWithRows() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("分数");
            var row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("高");
            row2.createCell(1).setCellValue(30);
            var row3 = sheet.createRow(2);
            row3.createCell(0).setCellValue("低");
            row3.createCell(1).setCellValue(10);
            var marked = workbook.createCellStyle();
            marked.setWrapText(true);
            row3.getCell(0).setCellStyle(marked);
            sheet.createRow(3).createCell(0).setCellValue("空值");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);

        service.editData(USER_ID, FILE_ID, SOURCE_VERSION,
                dataRequest(sortOperation("A1:B4", "B", "ASC", true)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals("名称", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("低", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(10, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertTrue(sheet.getRow(1).getCell(0).getCellStyle().getWrapText());
            assertEquals("高", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("空值", sheet.getRow(3).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rejectsSortingRangeContainingFormula() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("值");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editData(USER_ID, FILE_ID, SOURCE_VERSION,
                        dataRequest(sortOperation("A1:A2", "A", "ASC", true))));

        assertEquals(422, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void setsAndClearsAutoFilter() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("名称");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        service.editData(USER_ID, FILE_ID, SOURCE_VERSION,
                dataRequest(filterOperation("SET_AUTO_FILTER", "A1:B5")));
        byte[] filtered = storedBytes();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(filtered))) {
            assertTrue(workbook.getSheet("数据").getCTWorksheet().isSetAutoFilter());
            assertEquals("A1:B5", workbook.getSheet("数据").getCTWorksheet()
                    .getAutoFilter().getRef());
        }

        org.mockito.Mockito.reset(fileStorage, versionWriter, versionService);
        prepareEdit(filtered);
        service.editData(USER_ID, FILE_ID, SOURCE_VERSION,
                dataRequest(filterOperation("CLEAR_AUTO_FILTER", null)));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            assertFalse(workbook.getSheet("数据").getCTWorksheet().isSetAutoFilter());
        }
    }

    @Test
    void formatsRangeWithoutChangingValues() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("数据").createRow(0);
            row.createCell(0).setCellValue(12.5);
            row.createCell(1).setCellValue("文本");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        FormatXlsxCellsDTO.Operation operation = new FormatXlsxCellsDTO.Operation();
        operation.setSheetName("数据");
        operation.setRange("A1:B1");
        operation.setFontName("Arial");
        operation.setFontSize(14.0);
        operation.setBold(true);
        operation.setItalic(true);
        operation.setFontColor("#112233");
        operation.setHorizontalAlignment("CENTER");
        operation.setVerticalAlignment("TOP");
        operation.setWrapText(true);
        operation.setFillColor("FFEEDD");
        operation.setNumberFormat("0.00");
        operation.setBorderStyle("THIN");
        FormatXlsxCellsDTO request = new FormatXlsxCellsDTO();
        request.setInstruction("设置格式");
        request.setOperations(List.of(operation));

        service.formatCells(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var row = workbook.getSheet("数据").getRow(0);
            assertEquals(12.5, row.getCell(0).getNumericCellValue());
            assertEquals("文本", row.getCell(1).getStringCellValue());
            var style = row.getCell(0).getCellStyle();
            var font = workbook.getFontAt(style.getFontIndex());
            assertEquals("Arial", font.getFontName());
            assertEquals(14.0, font.getFontHeightInPoints());
            assertTrue(font.getBold());
            assertTrue(font.getItalic());
            assertEquals(HorizontalAlignment.CENTER, style.getAlignment());
            assertEquals(VerticalAlignment.TOP, style.getVerticalAlignment());
            assertTrue(style.getWrapText());
            assertEquals(FillPatternType.SOLID_FOREGROUND, style.getFillPattern());
            assertEquals("0.00", style.getDataFormatString());
            assertEquals(BorderStyle.THIN, style.getBorderTop());
            assertEquals(style.getIndex(), row.getCell(1).getCellStyle().getIndex());
        }
    }

    @Test
    void fillsFormulasWithRelativeMixedAbsoluteAndQuotedSheetReferences() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue(2);
            sheet.getRow(0).createCell(1).setCellValue(3);
            sheet.createRow(1).createCell(0).setCellValue(5);
            sheet.getRow(1).createCell(1).setCellValue(7);
            var style = workbook.createCellStyle();
            style.setWrapText(true);
            sheet.getRow(1).createCell(2).setCellStyle(style);
            var other = workbook.createSheet("年度 数据");
            other.createRow(1).createCell(1).setCellValue(11);
            other.getRow(1).createCell(2).setCellValue(13);
            other.createRow(2).createCell(1).setCellValue(17);
            other.getRow(2).createCell(2).setCellValue(19);
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        var fill = new EditXlsxCellsDTO.Operation();
        fill.setType("FILL_FORMULA");
        fill.setSheetName("数据");
        fill.setRange("C2:D3");
        fill.setFormula("=SUM(A1,$A1,A$1,$A$1,'年度 数据'!B2)");

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request(fill));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals("SUM(A1,$A1,A$1,$A$1,'年度 数据'!B2)",
                    sheet.getRow(1).getCell(2).getCellFormula());
            assertEquals("SUM(B1,$A1,B$1,$A$1,'年度 数据'!C2)",
                    sheet.getRow(1).getCell(3).getCellFormula());
            assertEquals("SUM(A2,$A2,A$1,$A$1,'年度 数据'!B3)",
                    sheet.getRow(2).getCell(2).getCellFormula());
            assertEquals("SUM(B2,$A2,B$1,$A$1,'年度 数据'!C3)",
                    sheet.getRow(2).getCell(3).getCellFormula());
            assertTrue(sheet.getRow(1).getCell(2).getCellStyle().getWrapText());
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            assertEquals(19, evaluator.evaluate(sheet.getRow(1).getCell(2)).getNumberValue());
            assertEquals(36, evaluator.evaluate(sheet.getRow(2).getCell(3)).getNumberValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"不存在!A1", "XFE1", "A1048577", "SUM(", "#REF!"})
    void rejectsInvalidFormulaSyntaxAndReferences(String formula) throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION,
                        request(formula("A1", formula))));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void rejectsFillThatMovesReferenceOutsideWorksheet() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        var fill = new EditXlsxCellsDTO.Operation();
        fill.setType("FILL_FORMULA");
        fill.setSheetName("数据");
        fill.setRange("A1:A2");
        fill.setFormula("XFD1048576");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request(fill)));

        assertEquals(400, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
    }

    @Test
    void calculatesSummaryConditionLookupCrossSheetDateBlankAndErrorsWithoutSaving() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("办公");
            sheet.getRow(0).createCell(1).setCellValue(12.5);
            sheet.createRow(1).createCell(0).setCellValue("差旅");
            sheet.getRow(1).createCell(1).setCellValue(20);
            sheet.createRow(2).createCell(0).setCellValue("办公");
            sheet.getRow(2).createCell(1).setCellValue(7.5);
            var other = workbook.createSheet("年度 数据");
            other.createRow(0).createCell(0).setCellValue(100);
            String[] formulas = {"SUM(B1:B3)", "SUMIF(A1:A3,\"办公\",B1:B3)",
                    "IF(B2>10,\"达标\",\"不足\")", "VLOOKUP(\"差旅\",A1:B3,2,FALSE)",
                    "'年度 数据'!A1+SUM(B1:B3)", "DATE(2026,10,11)+1", "Z1+1", "1/0",
                    "CUBEVALUE(\"connection\",\"member\")", "B1>10", "1+\"abc\"",
                    "VLOOKUP(\"不存在\",A1:B3,2,FALSE)", "#REF!"};
            for (int index = 0; index < formulas.length; index++) {
                var row = sheet.getRow(index);
                if (row == null) {
                    row = sheet.createRow(index);
                }
                row.createCell(3).setCellFormula(formulas[index]);
            }
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            sheet.getRow(5).getCell(3).setCellStyle(dateStyle);
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var result = service.calculateFormulas(USER_ID, FILE_ID, SOURCE_VERSION, "数据", "D1:D13");

        assertEquals(13, result.results().size());
        assertEquals("40", result.results().get(0).value());
        assertEquals("20", result.results().get(1).value());
        assertEquals("达标", result.results().get(2).value());
        assertEquals("STRING", result.results().get(2).valueType());
        assertEquals("20", result.results().get(3).value());
        assertEquals("140", result.results().get(4).value());
        assertEquals("2026-10-12T00:00:00", result.results().get(5).value());
        assertEquals("1", result.results().get(6).value());
        assertEquals("ERROR", result.results().get(7).status());
        assertEquals("#DIV/0!", result.results().get(7).value());
        assertEquals("UNSUPPORTED", result.results().get(8).status());
        assertNull(result.results().get(8).value());
        assertEquals("true", result.results().get(9).value());
        assertEquals("BOOLEAN", result.results().get(9).valueType());
        assertEquals("ERROR", result.results().get(10).status());
        assertEquals("#VALUE!", result.results().get(10).value());
        assertEquals("#N/A", result.results().get(11).value());
        assertEquals("#REF!", result.results().get(12).value());
        verify(fileStorage, never()).store(any(byte[].class), any());
        verify(versionWriter, never()).appendVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCalculatingUnsafeFormulaInDependencySheet() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellFormula("依赖!A1");
            workbook.createSheet("依赖").createRow(0).createCell(0)
                    .setCellFormula("HYPERLINK(\"https://example.com\")");
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.calculateFormulas(USER_ID, FILE_ID, SOURCE_VERSION, "数据", "A1"));

        assertEquals(400, error.getCode());
    }

    @Test
    void limitsFormulaFillAndCalculationBeforeReadingWorkbook() {
        properties.getXlsx().setMaxEditOperations(1);
        var fill = new EditXlsxCellsDTO.Operation();
        fill.setType("FILL_FORMULA");
        fill.setSheetName("数据");
        fill.setRange("A1:A2");
        fill.setFormula("1+1");

        assertEquals(413, assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request(fill))).getCode());
        assertEquals(413, assertThrows(BusinessException.class,
                () -> service.calculateFormulas(USER_ID, FILE_ID, SOURCE_VERSION,
                        "数据", "A1:A2")).getCode());
        verify(versionService, never()).downloadVersion(any(), any(), any());
    }

    @Test
    void replacesExistingFormulaWithTypedValueRatherThanOnlyChangingItsCache() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellFormula("1+1");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION,
                request(value("A1", "STRING", "=普通文本")));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var cell = workbook.getSheet("数据").getRow(0).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("=普通文本", cell.getStringCellValue());
        }
    }

    @Test
    void rejects1904DateSystemInsteadOfReturningIncorrectDate() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
            var cell = workbook.createSheet("数据").createRow(0).createCell(0);
            cell.setCellFormula("DATE(2026,10,11)");
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            cell.setCellStyle(style);
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var error = assertThrows(BusinessException.class,
                () -> service.calculateFormulas(USER_ID, FILE_ID, SOURCE_VERSION, "数据", "A1"));
        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains("1904"));
    }

    @Test
    void savesRecalculatedCachesForExistingDependenciesAndNewFilledFormulas() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(3);
            row.createCell(1).setCellFormula("A1*2");
            row.createCell(2).setCellFormula("B1+1");
            row.createCell(3).setCellFormula("IF(A1>5,\"达标\",\"不足\")");
            row.createCell(4).setCellFormula("A1>5");
            row.createCell(5).setCellFormula("1/0");
            row.createCell(6).setCellFormula("DATE(2026,10,12)");
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            row.getCell(6).setCellStyle(dateStyle);
            workbook.createSheet("汇总").createRow(0).createCell(0).setCellFormula("数据!C1+1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        var fill = new EditXlsxCellsDTO.Operation();
        fill.setType("FILL_FORMULA");
        fill.setSheetName("数据");
        fill.setRange("B2:B3");
        fill.setFormula("$A$1*ROW()");
        var request = request(value("A1", "NUMBER", "10"), fill);
        request.setRecalculateFormulas(true);

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request);

        byte[] edited = storedBytes();
        // 不创建计算器，直接读取导出文件的缓存，证明结果确实已落盘。
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(edited))) {
            var sheet = workbook.getSheet("数据");
            var row = sheet.getRow(0);
            assertEquals(CellType.FORMULA, row.getCell(1).getCellType());
            assertEquals("A1*2", row.getCell(1).getCellFormula());
            assertEquals(CellType.NUMERIC, row.getCell(1).getCachedFormulaResultType());
            assertEquals(20, row.getCell(1).getNumericCellValue());
            assertEquals(21, row.getCell(2).getNumericCellValue());
            assertEquals(CellType.STRING, row.getCell(3).getCachedFormulaResultType());
            assertEquals("达标", row.getCell(3).getStringCellValue());
            assertEquals(CellType.BOOLEAN, row.getCell(4).getCachedFormulaResultType());
            assertTrue(row.getCell(4).getBooleanCellValue());
            assertEquals(CellType.ERROR, row.getCell(5).getCachedFormulaResultType());
            assertEquals(org.apache.poi.ss.usermodel.FormulaError.DIV0.getCode(),
                    row.getCell(5).getErrorCellValue());
            assertEquals(LocalDate.of(2026, 10, 12),
                    row.getCell(6).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("yyyy-mm-dd", row.getCell(6).getCellStyle().getDataFormatString());
            assertEquals(22, workbook.getSheet("汇总").getRow(0).getCell(0).getNumericCellValue());
            assertEquals(20, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(30, sheet.getRow(2).getCell(1).getNumericCellValue());
            assertTrue(workbook.getForceFormulaRecalculation());
        }
        try (XSSFWorkbook original = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            assertEquals(6, original.getSheet("数据").getRow(0).getCell(1).getNumericCellValue());
        }
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                eq("更新数据"), eq("编辑 3 个 XLSX 单元格，已重算公式缓存"), eq(SOURCE_VERSION));
    }

    @Test
    void unsupportedCacheCalculationDoesNotSavePartialEdits() throws Exception {
        byte[] source = unsupportedFormulaWorkbook();
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        var request = request(value("B1", "NUMBER", "10"));
        request.setRecalculateFormulas(true);

        var error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request));

        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains("数据!A1"));
        assertTrue(error.getMessage().contains("未保存"));
        verify(fileStorage, never()).store(any(byte[].class), any());
        verify(versionWriter, never()).appendVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void defaultEditingStillAllowsUnsupportedFormulasWithoutRecalculating() throws Exception {
        prepareEdit(unsupportedFormulaWorkbook());

        service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request(value("B1", "NUMBER", "10")));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(storedBytes()))) {
            var row = workbook.getSheet("数据").getRow(0);
            assertEquals("CUBEVALUE(\"connection\",\"member\")", row.getCell(0).getCellFormula());
            assertEquals(999, row.getCell(0).getNumericCellValue());
            assertEquals(10, row.getCell(1).getNumericCellValue());
            assertTrue(workbook.getForceFormulaRecalculation());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1904", "FORMULAS", "CELLS", "UNSAFE"})
    void rejectsUncalculableWorkbookBeforeSavingCaches(String limitation) throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellFormula("1+1");
            switch (limitation) {
                case "1904" -> workbook.getCTWorkbook().getWorkbookPr().setDate1904(true);
                case "FORMULAS" -> {
                    sheet.getRow(0).createCell(2).setCellFormula("A1+1");
                    properties.getXlsx().setMaxEditOperations(1);
                }
                case "CELLS" -> properties.getXlsx().setMaxReadCells(1);
                case "UNSAFE" -> sheet.getRow(0).getCell(0)
                        .setCellFormula("HYPERLINK(\"https://example.com\")");
                default -> throw new IllegalArgumentException(limitation);
            }
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        var request = request(value("B1", "NUMBER", "10"));
        request.setRecalculateFormulas(true);

        var error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request));

        int expectedCode = switch (limitation) {
            case "1904" -> 422;
            case "UNSAFE" -> 400;
            default -> 413;
        };
        assertEquals(expectedCode, error.getCode());
        verify(fileStorage, never()).store(any(byte[].class), any());
        verify(versionWriter, never()).appendVersion(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cleansUpRecalculatedOutputWhenVersionConflicts() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellFormula("1+1");
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        when(fileStorage.delete("versions/v2.xlsx")).thenReturn(true);
        var request = request(value("B1", "NUMBER", "10"));
        request.setRecalculateFormulas(true);

        var error = assertThrows(BusinessException.class,
                () -> service.editCells(USER_ID, FILE_ID, SOURCE_VERSION, request));

        assertEquals(409, error.getCode());
        verify(fileStorage).delete("versions/v2.xlsx");
    }

    private static byte[] unsupportedFormulaWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("数据").createRow(0).createCell(0);
            cell.setCellFormula("CUBEVALUE(\"connection\",\"member\")");
            cell.setCellValue(999);
            workbook.write(output);
            return output.toByteArray();
        }
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

    private static EditXlsxCellsDTO.RangeValue rangeValue(String type, String value) {
        EditXlsxCellsDTO.RangeValue rangeValue = new EditXlsxCellsDTO.RangeValue();
        rangeValue.setValueType(type);
        rangeValue.setValue(value);
        return rangeValue;
    }

    private static EditXlsxStructureDTO structureRequest(
            EditXlsxStructureDTO.Operation... operations) {
        EditXlsxStructureDTO request = new EditXlsxStructureDTO();
        request.setInstruction("调整行列");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditXlsxStructureDTO.Operation structureOperation(String type, int start, int count) {
        EditXlsxStructureDTO.Operation operation = new EditXlsxStructureDTO.Operation();
        operation.setType(type);
        operation.setSheetName("数据");
        operation.setStart(start);
        operation.setCount(count);
        return operation;
    }

    private static EditXlsxSheetsDTO sheetRequest(EditXlsxSheetsDTO.Operation... operations) {
        EditXlsxSheetsDTO request = new EditXlsxSheetsDTO();
        request.setInstruction("调整工作表");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditXlsxSheetsDTO.Operation sheetOperation(String type, String sheetName,
                                                               String newName, Integer position) {
        EditXlsxSheetsDTO.Operation operation = new EditXlsxSheetsDTO.Operation();
        operation.setType(type);
        operation.setSheetName(sheetName);
        operation.setNewName(newName);
        operation.setPosition(position);
        return operation;
    }

    private static EditXlsxDataDTO dataRequest(EditXlsxDataDTO.Operation... operations) {
        EditXlsxDataDTO request = new EditXlsxDataDTO();
        request.setInstruction("调整数据");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditXlsxDataDTO.Operation sortOperation(String range, String keyColumn,
                                                           String direction, boolean hasHeader) {
        EditXlsxDataDTO.Operation operation = new EditXlsxDataDTO.Operation();
        operation.setType("SORT_RANGE");
        operation.setSheetName("数据");
        operation.setRange(range);
        operation.setKeyColumn(keyColumn);
        operation.setDirection(direction);
        operation.setHasHeader(hasHeader);
        return operation;
    }

    private static EditXlsxDataDTO.Operation filterOperation(String type, String range) {
        EditXlsxDataDTO.Operation operation = new EditXlsxDataDTO.Operation();
        operation.setType(type);
        operation.setSheetName("数据");
        operation.setRange(range);
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
