package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在 XLSX 中新建纯值工作表，并可在同一操作中生成基础图表。 */
@Component
public class CreateXlsxTableTool extends AbstractAiTool {

    private final XlsxDocumentService spreadsheets;

    public CreateXlsxTableTool(XlsxDocumentService spreadsheets) {
        this.spreadsheets = spreadsheets;
    }

    @Override
    public String name() {
        return "create_xlsx_table";
    }

    @Override
    public String description() {
        return "在 XLSX 中新建一个纯值工作表。先读取真实列名和用户要求，生成 headers/rows JSON；"
                + "可选同时生成 BAR、LINE 或 PIE 基础图表。只产生待确认计划，不直接保存。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("sheetName", "新工作表名称，不能与当前工作簿重名"),
                ToolParameter.optional("startCell", "表格左上角单元格，默认 A1"),
                ToolParameter.required("dataJson",
                        "JSON 对象：headers 为字符串数组，rows 为二维数组；值可为文本、数字、布尔或 null"),
                ToolParameter.optional("chartType", "可选图表类型：BAR、LINE、PIE；不传则仅建表"),
                ToolParameter.optional("categoryColumn", "图表使用表头名称作为分类列"),
                ToolParameter.optional("valueColumn", "图表使用表头名称作为数值列"),
                ToolParameter.optional("chartTitle", "图表标题"),
                ToolParameter.required("reason", "表格和图表所依据的数据与用户目的"));
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        var args = normalize(arguments);
        requireXlsx(context);
        String sheetName = requireString(args, "sheetName", "新工作表名称").trim();
        if (context.xlsxStructure().sheets().stream()
                .anyMatch(sheet -> sheet.name().equalsIgnoreCase(sheetName))) {
            throw new BusinessException(409, "目标工作表已存在：" + sheetName);
        }
        String startCell = optionalString(args, "startCell");
        if (startCell == null) startCell = "A1";
        JsonNode data = parseObject(requireString(args, "dataJson", "表格数据 JSON"), "表格数据");
        if (!data.path("headers").isArray() || !data.path("rows").isArray()) {
            throw new BusinessException(422, "表格数据必须包含 headers 数组和 rows 二维数组");
        }
        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.set("headers", data.get("headers"));
        content.set("rows", data.get("rows"));
        String chartType = optionalString(args, "chartType");
        if (chartType != null) {
            chartType = chartType.trim().toUpperCase(Locale.ROOT);
            if (!List.of("BAR", "LINE", "PIE").contains(chartType)) {
                throw new BusinessException(422, "图表类型只支持 BAR、LINE、PIE");
            }
            ObjectNode chart = content.putObject("chart");
            chart.put("type", chartType);
            chart.put("categoryColumn", requireString(args, "categoryColumn", "图表分类列表头"));
            chart.put("valueColumn", requireString(args, "valueColumn", "图表数值列表头"));
            String title = optionalString(args, "chartTitle");
            if (title != null) chart.put("title", title);
        } else if (optionalString(args, "categoryColumn") != null
                || optionalString(args, "valueColumn") != null
                || optionalString(args, "chartTitle") != null) {
            throw new BusinessException(422, "未指定 chartType 时不能提供图表列或标题");
        }
        String reason = requireString(args, "reason", "表格说明");
        String target = sheetName + "!" + startCell;
        var plan = new DocumentEditPlan("新建表格 " + sheetName, List.of(
                new DocumentEditPlan.Operation("CREATE_TABLE", target,
                        content.toString(), reason)), null);
        spreadsheets.validateAiPlan(context.userId(), context.fileId(), context.versionNo(), plan);
        String detail = "将新建工作表 " + sheetName + "（" + target + "）"
                + (chartType == null ? "" : "，并生成 " + chartType + " 图表") + "：" + reason;
        return ToolResult.withPlan(detail + "（待确认）", plan);
    }

    private static void requireXlsx(ToolExecutionContext context) {
        if (!"XLSX".equalsIgnoreCase(context.format()) || context.xlsxStructure() == null) {
            throw new BusinessException(422, "create_xlsx_table 仅适用于绑定明确 XLSX 版本的对话");
        }
    }

    private static JsonNode parseObject(String value, String label) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node == null || !node.isObject()) {
                throw new BusinessException(422, label + "必须是 JSON 对象");
            }
            return node;
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new BusinessException(422, label + "不是合法 JSON");
        }
    }
}
