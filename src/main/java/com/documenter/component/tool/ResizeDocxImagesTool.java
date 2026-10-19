package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.service.ToolExecutionContext;
import com.documenter.util.DocxNormalizationSpec;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ResizeDocxImagesTool extends AbstractAiTool {
    @Override public String name() { return "resize_docx_images"; }
    @Override public boolean mutating() { return true; }
    @Override public String description() {
        return "批量调整 DOCX 正文及表格中已有图片的显示尺寸，不新增或替换图片、不修改图片原始字节。"
                + "默认按原图比例缩放（不支持读取原图尺寸时保留当前显示比例），自动限制到页面版心/已知单元格宽度。"
                + "浮动图片保留定位；alignment 仅用于独立成段的内嵌图片，含文字或浮动图时请省略。只生成待确认计划。";
    }
    @Override public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("layoutJson", "JSON 对象：widthCm/heightCm 为厘米（至少一项）；"
                + "keepAspectRatio 默认为true；同时传宽高时按该尺寸框等比适配，不裁剪；false 时宽高均必填，可能拉伸。"
                + "alignment 可选 LEFT/CENTER/RIGHT，默认不改变对齐。"), ToolParameter.optional("reason", "调整理由"));
    }
    @Override public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        var args = normalize(arguments);
        var spec = DocxNormalizationSpec.images(requireString(args, "layoutJson", "图片尺寸 JSON"));
        var op = new DocumentEditPlan.Operation("RESIZE_DOCX_IMAGES", "body", DocxNormalizationSpec.json(spec), optionalString(args, "reason"));
        int count = DocxNormalizationSpec.validateContext(op, context);
        String summary = "统一正文 " + count + " 张已有图片的显示尺寸：" + op.content()
                + "；实际尺寸限制到版心/单元格，不修改页眉页脚";
        return ToolResult.withPlan(summary + "（待确认）", new DocumentEditPlan(summary, List.of(op), null));
    }
}
