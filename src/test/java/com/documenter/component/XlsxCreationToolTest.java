package com.documenter.component;

import com.documenter.component.tool.CreateXlsxChartTool;
import com.documenter.component.tool.CreateXlsxTableTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.XlsxStructureVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XlsxCreationToolTest {

    private final XlsxDocumentService spreadsheets = mock(XlsxDocumentService.class);
    private final ToolExecutionContext context = ToolExecutionContext.forVersion(
            7L, 10L, 2, "XLSX", null,
            new XlsxStructureVO(10L, 2, 1, 2, false,
                    List.of(new XlsxStructureVO.Sheet(0, "数据", 1, 2,
                            List.of(), List.of(), List.of()))));

    @Test
    void tableToolBuildsNewSheetPlanWithOptionalChart() {
        var tool = new CreateXlsxTableTool(spreadsheets);

        var result = tool.execute(Map.of(
                "sheetName", "汇总",
                "startCell", "A1",
                "dataJson", """
                        {"headers":["项目","金额"],"rows":[["A",10],["B",20]]}
                        """,
                "chartType", "bar",
                "categoryColumn", "项目",
                "valueColumn", "金额",
                "chartTitle", "金额对比",
                "reason", "生成汇总表"), context);

        var operation = result.plan().operations().getFirst();
        assertEquals("CREATE_TABLE", operation.type());
        assertEquals("汇总!A1", operation.target());
        assertTrue(operation.content().contains("\"type\":\"BAR\""));
        ArgumentCaptor<DocumentEditPlan> plan = ArgumentCaptor.forClass(DocumentEditPlan.class);
        verify(spreadsheets).validateAiPlan(eq(7L), eq(10L), eq(2), plan.capture());
        assertEquals("CREATE_TABLE", plan.getValue().operations().getFirst().type());
    }

    @Test
    void tableToolRejectsExistingSheetAndPartialChartFields() {
        var tool = new CreateXlsxTableTool(spreadsheets);
        assertThrows(BusinessException.class, () -> tool.execute(Map.of(
                "sheetName", "数据", "dataJson",
                "{\"headers\":[\"A\"],\"rows\":[]}", "reason", "重复"), context));
        assertThrows(BusinessException.class, () -> tool.execute(Map.of(
                "sheetName", "汇总", "dataJson",
                "{\"headers\":[\"A\"],\"rows\":[]}",
                "categoryColumn", "A", "reason", "缺少图表类型"), context));
        verify(spreadsheets, never()).validateAiPlan(any(), any(), any(), any());
    }

    @Test
    void chartToolBuildsBoundedExistingRangePlan() {
        var tool = new CreateXlsxChartTool(spreadsheets);

        var result = tool.execute(Map.of(
                "sheetName", "数据",
                "categoriesRange", "A2:A3",
                "valuesRange", "B2:B3",
                "chartType", "pie",
                "positionCell", "D2",
                "title", "金额占比",
                "seriesName", "金额",
                "reason", "展示占比"), context);

        var operation = result.plan().operations().getFirst();
        assertEquals("CREATE_CHART", operation.type());
        assertEquals("数据!D2", operation.target());
        assertTrue(operation.content().contains("\"type\":\"PIE\""));
        verify(spreadsheets).validateAiPlan(eq(7L), eq(10L), eq(2), any());
    }

    @Test
    void toolsRequireBoundXlsxContextAndSupportedChartType() {
        var table = new CreateXlsxTableTool(spreadsheets);
        assertThrows(BusinessException.class, () -> table.execute(Map.of(
                "sheetName", "汇总", "dataJson", "{}", "reason", "test"),
                ToolExecutionContext.withoutDocument(7L)));

        var chart = new CreateXlsxChartTool(spreadsheets);
        assertThrows(BusinessException.class, () -> chart.execute(Map.of(
                "sheetName", "数据", "categoriesRange", "A2:A3",
                "valuesRange", "B2:B3", "chartType", "RADAR",
                "positionCell", "D2", "reason", "test"), context));
        verifyNoInteractions(spreadsheets);
    }
}
