package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.service.XlsxDocumentService;
import com.documenter.util.XlsxFormulaTarget;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** XLSX 单单元格公式只生成计划，语法、引用和安全检查复用现有编辑实现。 */
@Component
public class SetFormulaTool extends AbstractAiTool {
    private final XlsxDocumentService spreadsheets;
    public SetFormulaTool(XlsxDocumentService spreadsheets) { this.spreadsheets = spreadsheets; }
    @Override public String name() { return "set_formula"; }
    @Override public String description() {
        return "为 XLSX 的单个单元格提出公式。先用 read_document 查看真实列名和数据，"
                + "再提供工作表、目标单元格、公式与说明。可写入空白单元格，仅生成待确认计划。";
    }
    @Override public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("sheetName", "read_document 返回的工作表名称"),
                ToolParameter.required("cell", "单个目标单元格，如 D2，不接受区域"),
                ToolParameter.required("formula", "Excel 公式，可带开头等号，最多 8192 字符"),
                ToolParameter.required("reason", "依据列名、实际数据与用户要求说明公式含义"));
    }
    @Override public boolean mutating() { return true; }
    @Override public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        var args = normalize(arguments);
        if (!"XLSX".equalsIgnoreCase(context.format()) || context.xlsxStructure() == null) {
            throw new BusinessException(422, "set_formula 仅适用于绑定明确 XLSX 版本的对话");
        }
        String requested = requireString(args, "sheetName", "工作表名称");
        String sheet = context.xlsxStructure().sheets().stream().map(s -> s.name())
                .filter(name -> name.equalsIgnoreCase(requested)).findFirst()
                .orElseThrow(() -> new BusinessException(422, "当前版本不存在指定工作表"));
        var target = XlsxFormulaTarget.of(sheet, requireString(args, "cell", "目标单元格"));
        String formula = requireString(args, "formula", "公式");
        if (formula.startsWith("=")) formula = formula.substring(1).trim();
        if (formula.isBlank() || formula.length() > 8192) throw new BusinessException(422, "公式长度必须为 1 到 8192 字符");
        String reason = requireString(args, "reason", "公式说明");
        var plan = new DocumentEditPlan("设置公式 " + target.target(), List.of(
                new DocumentEditPlan.Operation("SET_FORMULA", target.target(), formula, reason)), null);
        spreadsheets.validateFormulaPlan(context.userId(), context.fileId(), context.versionNo(), plan);
        return ToolResult.withPlan("将设置 " + target.target() + " 的公式为 =" + formula + "：" + reason + "（待确认）", plan);
    }
}
