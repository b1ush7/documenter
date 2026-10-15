package com.documenter.service.impl;

import com.documenter.exception.BusinessException;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.VersionComparisonVO;
import com.documenter.vo.VersionComparisonVO.ChangeType;
import com.documenter.vo.XlsxStructureVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本比较测试（TODO 4.3「支持修改前后内容比较」）。
 *
 * <p>验证要点：
 * <ol>
 *   <li>两个版本都必须明确指定，且都经各自的结构读取路径解析（含归属校验）；</li>
 *   <li>按块对齐：未改动的块不产生差异，改动的报 {@code MODIFIED}，
 *       新增与删除分别报 {@code ADDED} / {@code REMOVED}；</li>
 *   <li>版本不存在或不属于该用户时原样抛出 404，不能被换成笼统的「格式不支持」——
 *       否则可以用来探测他人版本是否存在。</li>
 * </ol>
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class DocumentComparisonServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 10L;
    private static final Integer FROM = 1;
    private static final Integer TO = 2;

    private DocxDocumentService docxService;
    private XlsxDocumentService xlsxService;
    private DocumentComparisonServiceImpl service;

    @BeforeEach
    void setUp() {
        docxService = mock(DocxDocumentService.class);
        xlsxService = mock(XlsxDocumentService.class);
        service = new DocumentComparisonServiceImpl(docxService, xlsxService);
    }

    // ============================================ DOCX 比较

    @Test
    void modifiedParagraphIsReportedWithBeforeAndAfter() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "第一段"),
                        paragraph("body/p1", "旧内容"),
                        paragraph("body/p2", "第三段"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(paragraph("body/p0", "第一段"),
                        paragraph("body/p1", "新内容"),
                        paragraph("body/p2", "第三段"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.changes().size(), "只有一处改动");
        var change = result.changes().get(0);
        assertEquals(ChangeType.MODIFIED, change.type());
        assertEquals("body/p1", change.key());
        assertEquals("旧内容", change.before());
        assertEquals("新内容", change.after());
        assertTrue(change.label().contains("段落"), "标签应说明位置: " + change.label());
    }

    @Test
    void unchangedContentProducesNoChanges() {
        DocxStructureVO same = docx(List.of(paragraph("body/p0", "内容"),
                paragraph("body/p1", "更多内容")));
        when(docxService.readStructure(USER_ID, FILE_ID, FROM)).thenReturn(same);
        when(docxService.readStructure(USER_ID, FILE_ID, TO)).thenReturn(same);

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        // 比较同一版本应当没有差异，而不是把每个块都报成改动
        assertTrue(result.changes().isEmpty(), "实际: " + result.changes());
    }

    @Test
    void addedParagraphIsReportedAsAdded() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "原有"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(paragraph("body/p0", "原有"),
                        paragraph("body/p1", "新增段落"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.changes().size());
        assertEquals(ChangeType.ADDED, result.changes().get(0).type());
        assertEquals("body/p1", result.changes().get(0).key());
        assertEquals(null, result.changes().get(0).before());
        assertEquals("新增段落", result.changes().get(0).after());
    }

    @Test
    void deletedParagraphIsReportedAsRemoved() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "保留"),
                        paragraph("body/p1", "将被删除"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(paragraph("body/p0", "保留"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.changes().size());
        assertEquals(ChangeType.REMOVED, result.changes().get(0).type());
        assertEquals("将被删除", result.changes().get(0).before());
        assertEquals(null, result.changes().get(0).after());
    }

    @Test
    void tableCellChangeIsDetectedAtCellGranularity() {
        // 表格单元格是嵌套块：TABLE -> ROW -> CELL -> PARAGRAPH
        DocxStructureVO.Block cellBefore = cell("body/t0/r0/c0", "旧单元格值");
        DocxStructureVO.Block cellAfter = cell("body/t0/r0/c0", "新单元格值");
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(table("body/t0", cellBefore))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(table("body/t0", cellAfter))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.changes().size(), "实际: " + result.changes());
        assertEquals("body/t0/r0/c0", result.changes().get(0).key());
        assertEquals("旧单元格值", result.changes().get(0).before());
        assertEquals("新单元格值", result.changes().get(0).after());
    }

    @Test
    void headingIsLabelledAsHeading() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(heading("body/p0", "旧标题"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(heading("body/p0", "新标题"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals("HEADING", result.changes().get(0).kind());
        assertTrue(result.changes().get(0).label().contains("标题"),
                "实际: " + result.changes().get(0).label());
    }

    @Test
    void countOfSummarisesEachChangeType() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "改前"),
                        paragraph("body/p1", "将被删除"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(paragraph("body/p0", "改后"),
                        paragraph("body/p2", "新增"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.countOf(ChangeType.MODIFIED));
        assertEquals(1, result.countOf(ChangeType.ADDED));
        assertEquals(1, result.countOf(ChangeType.REMOVED));
    }

    @Test
    void comparisonBindsBothExplicitVersions() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "a"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(docx(List.of(paragraph("body/p0", "b"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(FROM, result.fromVersion());
        assertEquals(TO, result.toVersion());
        assertEquals("DOCX", result.format());
        verify(docxService).readStructure(USER_ID, FILE_ID, FROM);
        verify(docxService).readStructure(USER_ID, FILE_ID, TO);
    }

    // ============================================ XLSX 比较

    @Test
    void xlsxCellChangeIsDetectedBySheetAndAddress() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(xlsxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(xlsx(List.of(cell("Sheet1", "A1", "100"))));
        when(xlsxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(xlsx(List.of(cell("Sheet1", "A1", "200"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals("XLSX", result.format());
        assertEquals(1, result.changes().size());
        assertEquals("Sheet1!A1", result.changes().get(0).key());
        assertEquals("100", result.changes().get(0).before());
        assertEquals("200", result.changes().get(0).after());
    }

    @Test
    void xlsxFormulaChangeIsReportedAsFormula() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(xlsxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(xlsx(List.of(cellWithFormula("Sheet1", "B1", "SUM(A1:A2)", "3"))));
        when(xlsxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(xlsx(List.of(cellWithFormula("Sheet1", "B1", "SUM(A1:A3)", "6"))));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        // 公式变化比缓存值变化更有意义，应优先展示
        assertEquals("=SUM(A1:A2)", result.changes().get(0).before());
        assertEquals("=SUM(A1:A3)", result.changes().get(0).after());
    }

    @Test
    void emptyCellsDoNotProduceNoise() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(xlsxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(xlsx(List.of(cell("Sheet1", "A1", "值"))));
        // 新版本里 A1 变空 —— 应视为删除而不是「修改为空白」
        when(xlsxService.readStructure(USER_ID, FILE_ID, TO))
                .thenReturn(xlsx(List.of()));

        VersionComparisonVO result = service.compare(USER_ID, FILE_ID, FROM, TO);

        assertEquals(1, result.changes().size());
        assertEquals(ChangeType.REMOVED, result.changes().get(0).type());
    }

    // ============================================ 边界与鉴权

    @Test
    void missingVersionIsPropagatedNotMaskedAsFormatError() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenThrow(new BusinessException(404, "版本不存在"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.compare(USER_ID, FILE_ID, FROM, TO));

        // 必须原样保留 404：换成「格式不支持」会掩盖真实原因，
        // 也会让 404 与 400 的语义混淆，可用于探测他人版本是否存在
        assertEquals(404, error.getCode());
    }

    @Test
    void nullVersionIsRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.compare(USER_ID, FILE_ID, null, TO));

        assertEquals(400, error.getCode());
    }

    @Test
    void differentFormatsCannotBeComparedBlockByBlock() {
        when(docxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenReturn(docx(List.of(paragraph("body/p0", "a"))));
        when(docxService.readStructure(USER_ID, FILE_ID, TO))
                .thenThrow(new BusinessException(400, "不是 DOCX"));
        when(xlsxService.readStructure(USER_ID, FILE_ID, FROM))
                .thenThrow(new BusinessException(400, "不是 XLSX"));
        when(xlsxService.readStructure(USER_ID, FILE_ID, TO))
                .thenThrow(new BusinessException(400, "不是 XLSX"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.compare(USER_ID, FILE_ID, FROM, TO));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("格式"), "应说明是格式问题: " + error.getMessage());
    }

    // ============================================ 夹具

    private static DocxStructureVO docx(List<DocxStructureVO.Block> blocks) {
        return new DocxStructureVO(FILE_ID, TO, blocks);
    }

    private static DocxStructureVO.Block paragraph(String id, String text) {
        return new DocxStructureVO.Block(id, "PARAGRAPH", text, null, null, null, null, null, List.of());
    }

    private static DocxStructureVO.Block heading(String id, String text) {
        return new DocxStructureVO.Block(id, "HEADING", text, "Heading1", 1, null, null, null, List.of());
    }

    private static DocxStructureVO.Block table(String id, DocxStructureVO.Block row) {
        return new DocxStructureVO.Block(id, "TABLE", null, null, null, null, null, null, List.of(row));
    }

    private static DocxStructureVO.Block cell(String id, String text) {
        return new DocxStructureVO.Block(id, "CELL", text, null, null, null, null, null, List.of());
    }

    private static XlsxStructureVO xlsx(List<XlsxStructureVO.Cell> cells) {
        return new XlsxStructureVO(FILE_ID, TO, 1, cells.size(), false,
                List.of(new XlsxStructureVO.Sheet(0, "Sheet1", 0, 0, List.of(), List.of(), cells)));
    }

    private static XlsxStructureVO.Cell cell(String sheet, String address, String value) {
        return new XlsxStructureVO.Cell(address, 0, 0, "STRING", "STRING", value, null, null);
    }

    private static XlsxStructureVO.Cell cellWithFormula(String sheet, String address,
                                                        String formula, String cached) {
        return new XlsxStructureVO.Cell(address, 0, 1, "FORMULA", "NUMERIC", cached, formula, null);
    }
}
