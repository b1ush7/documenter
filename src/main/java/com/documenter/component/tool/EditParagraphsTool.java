package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.component.EditPlanValidator;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocumentEditPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将一组段落修改作为完整计划校验、预览和确认。 */
@Component
public class EditParagraphsTool extends AbstractAiTool {
    private final EditParagraphTool single;
    private final EditPlanValidator validator;

    public EditParagraphsTool(EditParagraphTool single, EditPlanValidator validator) {
        this.single = single;
        this.validator = validator;
    }

    @Override public String name() { return "edit_paragraphs"; }
    @Override public boolean mutating() { return true; }
    @Override public String description() {
        return "批量提出 1 到 50 条段落修改，只生成一个待确认计划。多段落标题层级或正文样式修改优先使用本工具。"
                + "operationsJson 是 JSON 数组，每项参数与 edit_paragraph 相同：operation、target、content、formatType、level、reason。"
                + "格式示例：[{\"operation\":\"FORMAT_PARAGRAPH\",\"target\":\"body/p0\",\"formatType\":\"SET_HEADING\",\"level\":1}]";
    }
    @Override public List<ToolParameter> parameters() {
        return List.of(ToolParameter.required("operationsJson", "1 到 50 项的段落操作 JSON 数组；target 必须来自读取结果"));
    }

    @Override public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String raw = requireString(normalize(arguments), "operationsJson", "段落操作数组");
        tools.jackson.databind.JsonNode array;
        try {
            array = OBJECT_MAPPER.readTree(raw);
        } catch (RuntimeException invalidJson) {
            throw new BusinessException(422, "operationsJson 不是合法 JSON 数组");
        }
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > 50) {
            throw new BusinessException(422, "operationsJson 必须是包含 1 到 50 项的数组");
        }
        List<DocumentEditPlan> plans = new ArrayList<>();
        for (var node : array) {
            if (!node.isObject()) throw new BusinessException(422, "每个段落操作必须是对象");
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            node.properties().forEach(entry -> args.put(entry.getKey(), entry.getValue()));
            plans.add(single.execute(args, context).plan());
        }
        DocumentEditPlan plan = EditParagraphTool.merge(plans);
        validator.validateForContext(plan, context);
        return ToolResult.withPlan("已提出 " + plan.operations().size() + " 条段落修改（待确认）", plan);
    }
}
