package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.service.ToolExecutionContext;
import com.documenter.util.DocxNormalizationSpec;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class NormalizeDocxFormatTool extends AbstractAiTool {
    @Override public String name() { return "normalize_docx_format"; }
    @Override public boolean mutating() { return true; }
    @Override public String description() {
        return "批量统一 DOCX 正文（可含表格）的字体、字号、行距、段间距和对齐；保留文字、图片、列表和页眉页脚。"
                + "范围依据已有标题样式/大纲级别识别。一次工具调用处理整个范围，不需要逐段调用。只生成待确认计划。";
    }
    @Override public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("formatJson", "JSON 对象：scope=ALL/BODY/HEADINGS（默认 ALL），"
                + "headingLevel=1..9（仅 HEADINGS，可省略表示全部标题），fontFamily=字体名，fontSizePoints=磅数（小四12、三号16），"
                + "lineSpacing=倍数（如1.5），spaceBeforePoints/spaceAfterPoints=段前后磅数，"
                + "alignment=LEFT/CENTER/RIGHT/JUSTIFY，bold/italic=布尔值，includeTables=布尔值（默认true）。"
                + "只填写用户要求修改的字段，未提供的格式保留。标题字体与正文不同应分范围调用。"),
                ToolParameter.optional("reason", "修改理由"));
    }
    @Override public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        var args = normalize(arguments);
        var spec = DocxNormalizationSpec.format(requireString(args, "formatJson", "格式 JSON"));
        var op = new DocumentEditPlan.Operation("NORMALIZE_DOCX_FORMAT", "body", DocxNormalizationSpec.json(spec), optionalString(args, "reason"));
        int count = DocxNormalizationSpec.validateContext(op, context);
        String summary = "统一 " + spec.scope() + (spec.headingLevel() == null ? "" : " H" + spec.headingLevel())
                + " 范围的 " + count + " 个段落：" + op.content();
        return ToolResult.withPlan(summary + "（待确认）", new DocumentEditPlan(summary, List.of(op), null));
    }
}
