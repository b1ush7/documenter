package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileDownload;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class XlsxTableChartPlanTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 1;

    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private final XlsxDocumentServiceImpl service =
            new XlsxDocumentServiceImpl(versions, storage, writer, properties);

    @Test
    void createsTableAndBarChartInOneAtomicVersion() throws Exception {
        prepareEdit(sourceWorkbook());
        DocumentEditPlan plan = new DocumentEditPlan("生成汇总表和图表", List.of(
                new DocumentEditPlan.Operation("CREATE_TABLE", "汇总!A1",
                        """
                        {
                          "headers": ["项目", "金额"],
                          "rows": [["产品 A", 120], ["产品 B", 80]],
                          "chart": {
                            "type": "BAR",
                            "categoryColumn": "项目",
                            "valueColumn": "金额",
                            "title": "金额对比"
                          }
                        }
                        """, "按用户要求生成汇总表")), null);

        service.editAiPlan(USER_ID, FILE_ID, VERSION, plan, "生成汇总表和图表");

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("汇总");
            assertNotNull(sheet);
            assertEquals("项目", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(120, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(1, sheet.getDrawingPatriarch().getCharts().size());
            assertEquals("金额对比", sheet.getDrawingPatriarch().getCharts().getFirst()
                    .getTitleText().getString());
        }
        verify(writer).appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.AI_EDIT), eq("生成汇总表和图表"),
                eq("AI 执行 1 个 XLSX 操作"), eq(VERSION));
    }

    @Test
    void createsLineChartFromExistingRanges() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("月份");
            header.createCell(1).setCellValue("收入");
            for (int row = 1; row <= 3; row++) {
                var current = sheet.createRow(row);
                current.createCell(0).setCellValue("M" + row);
                current.createCell(1).setCellValue(row * 10);
            }
            workbook.write(output);
            source = output.toByteArray();
        }
        prepareEdit(source);
        DocumentEditPlan plan = new DocumentEditPlan("生成折线图", List.of(
                new DocumentEditPlan.Operation("CREATE_CHART", "数据!D2",
                        """
                        {"type":"LINE","categoriesRange":"A2:A4",
                         "valuesRange":"B2:B4","title":"收入趋势","seriesName":"收入"}
                        """, "展示三个月收入趋势")), null);

        service.editAiPlan(USER_ID, FILE_ID, VERSION, plan, "生成折线图");

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(storedBytes()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals(1, sheet.getDrawingPatriarch().getCharts().size());
            assertEquals("收入趋势", sheet.getDrawingPatriarch().getCharts().getFirst()
                    .getTitleText().getString());
        }
    }

    @Test
    void validatesFormulaAndTableMixWithoutSaving() throws Exception {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("数据").createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(2);
            workbook.write(output);
            source = output.toByteArray();
        }
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        DocumentEditPlan plan = new DocumentEditPlan("公式和表格", List.of(
                new DocumentEditPlan.Operation("SET_FORMULA", "数据!B1", "A1*2", "计算"),
                new DocumentEditPlan.Operation("CREATE_TABLE", "汇总!A1",
                        """
                        {"headers":["名称","数量"],"rows":[["A",1],["B",2]]}
                        """, "汇总")), null);

        var before = service.validateAiPlan(USER_ID, FILE_ID, VERSION, plan);

        assertEquals("2", before.get("数据!B1").value());
        verify(storage, never()).store(any(byte[].class), anyString());
        verifyNoInteractions(writer);
    }

    @Test
    void rejectsDuplicateSheetMismatchedRowsAndNonNumericChartData() throws Exception {
        prepareEdit(sourceWorkbook());
        for (DocumentEditPlan plan : List.of(
                plan("CREATE_TABLE", "数据!A1",
                        "{\"headers\":[\"A\"],\"rows\":[[1]]}", "重复工作表"),
                plan("CREATE_TABLE", "汇总!A1",
                        "{\"headers\":[\"A\",\"B\"],\"rows\":[[1]]}", "列数不一致"),
                plan("CREATE_CHART", "数据!E2",
                        """
                        {"type":"PIE","categoriesRange":"A2:A2",
                         "valuesRange":"B1:B1","title":"错误数据"}
                        """, "文本不能绘制"))) {
            assertThrows(BusinessException.class,
                    () -> service.validateAiPlan(USER_ID, FILE_ID, VERSION, plan));
        }
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    private static DocumentEditPlan plan(String type, String target, String content,
                                         String reason) {
        return new DocumentEditPlan(reason, List.of(
                new DocumentEditPlan.Operation(type, target, content, reason)), null);
    }

    private static byte[] sourceWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("分类");
            row.createCell(1).setCellValue("值");
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("文本");
            data.createCell(1).setCellValue(10);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void prepareEdit(byte[] source) {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        when(storage.store(any(byte[].class), eq("xlsx"))).thenReturn("versions/v2.xlsx");
        var created = new DocumentVersion();
        created.setId(20L);
        created.setFileId(FILE_ID);
        created.setUserId(USER_ID);
        created.setVersionNo(2);
        created.setStorageKey("versions/v2.xlsx");
        created.setSizeBytes(100L);
        created.setSha256("hash");
        created.setChangeType(ChangeType.AI_EDIT.name());
        when(writer.appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.AI_EDIT), any(), any(), eq(VERSION))).thenReturn(created);
    }

    private byte[] storedBytes() {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("xlsx"));
        return bytes.getValue();
    }

    private static FileDownload download(byte[] bytes) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setDisplayName("source.xlsx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        asset.setExtension("xlsx");
        return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length,
                asset.getContentType(), asset.getDisplayName());
    }
}
