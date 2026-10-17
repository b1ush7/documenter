package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 基于现有 XLSX 区域生成基础统计图计划。 */
@Component
public class CreateXlsxChartTool extends AbstractAiTool {

    private final XlsxDocumentService spreadsheets;

    public CreateXlsxChartTool(XlsxDocumentService spreadsheets) {
        this.spreadsheets = spreadsheets;
    }

    @Override
    public String name() {
        return "create_xlsx_chart";
    }

    @Override
    public String description() {
        return "基于现有 XLSX 工作表区域生成 BAR、LINE 或 PIE 基础统计图。"
                + "先用 read_document 或 read_range 确认真实分类和数值，只产生待确认计划。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("sheetName", "图表所在工作表"),
                ToolParameter.required("categoriesRange", "单列分类区域，如 A2:A10"),
                ToolParameter.required("valuesRange", "与分类区域行数相同的单列数值区域"),
                ToolParameter.required("chartType", "BAR、LINE 或 PIE"),
                ToolParameter.required("positionCell", "图表左上锚点单元格，如 E2"),
                ToolParameter.optional("title", "图表标题"),
                ToolParameter.optional("seriesName", "系列名称"),
                ToolParameter.required("reason", "图表用途说明"));
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        var args = normalize(arguments);
        if (!"XLSX".equalsIgnoreCase(context.format()) || context.xlsxStructure() == null) {
            throw new BusinessException(422, "create_xlsx_chart 仅适用于绑定明确 XLSX 版本的对话");
        }
        String requestedSheet = requireString(args, "sheetName", "图表工作表");
        String sheetName = context.xlsxStructure().sheets().stream().map(sheet -> sheet.name())
                .filter(name -> name.equalsIgnoreCase(requestedSheet)).findFirst()
                .orElseThrow(() -> new BusinessException(422, "当前版本不存在指定工作表"));
        String chartType = requireString(args, "chartType", "图表类型")
                .trim().toUpperCase(Locale.ROOT);
        if (!List.of("BAR", "LINE", "PIE").contains(chartType)) {
            throw new BusinessException(422, "图表类型只支持 BAR、LINE、PIE");
        }
        String categoriesRange = requireString(args, "categoriesRange", "分类区域");
        String valuesRange = requireString(args, "valuesRange", "数值区域");
        String positionCell = requireString(args, "positionCell", "图表位置");
        String reason = requireString(args, "reason", "图表说明");
        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.put("type", chartType);
        content.put("categoriesRange", categoriesRange);
        content.put("valuesRange", valuesRange);
        String title = optionalString(args, "title");
        if (title != null) content.put("title", title);
        String seriesName = optionalString(args, "seriesName");
        if (seriesName != null) content.put("seriesName", seriesName);
        String target = sheetName + "!" + positionCell;
        var plan = new DocumentEditPlan("生成 " + chartType + " 图表", List.of(
                new DocumentEditPlan.Operation("CREATE_CHART", target,
                        content.toString(), reason)), null);
        spreadsheets.validateAiPlan(context.userId(), context.fileId(), context.versionNo(), plan);
        return ToolResult.withPlan("将在 " + target + " 生成 " + chartType + " 图表，"
                + categoriesRange + " -> " + valuesRange + "：" + reason + "（待确认）", plan);
    }
}
