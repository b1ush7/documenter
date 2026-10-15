package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocumentEditPlan.Operation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 编辑表格（TODO 4.2「封装受控工具：编辑表格」）。
 *
 * <p>与段落编辑一样只产出待确认计划，不直接落版本。
 *
 * <p>只暴露「设置单元格文本」这一个操作。行/列的插入删除虽然是后端已有能力，
 * 但它们的参数结构（起始行、数量）与「目标块 + 新内容」的模型不同，
 * 混在同一个工具里会让模型的参数构造更容易出错。
 * 需要时再单独加一个工具，而不是把两种形态塞进一个参数集。
 */
@Component
public class EditTableTool extends AbstractAiTool {

    public static final String NAME = "edit_table";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "修改表格单元格的文本。target 必须是 read_document 返回的单元格块 ID"
                + "（形如 body/t0/r1/c0）。本工具只生成待确认的修改计划，不会立即改动文档。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("target", "单元格块 ID，来自 read_document 的结果"),
                ToolParameter.required("content", "单元格的新文本"),
                ToolParameter.optional("reason", "这么改的理由，会展示给用户帮助判断"));
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        Map<String, String> args = normalize(arguments);
        String target = requireString(args, "target", "单元格块 ID");
        String content = requireString(args, "content", "单元格的新文本");
        String reason = optionalString(args, "reason");

        if (!"XLSX".equalsIgnoreCase(context.format()) && !isCellBlock(target)) {
            // 这是一个常见的模型错误：把段落 ID 当成单元格用。
            // 提前给出明确提示，比让它一路执行到后端再报「不是单元格」更有帮助
            throw new BusinessException(422,
                    "目标「" + target + "」看起来不是表格单元格；"
                            + "单元格块 ID 形如 body/t0/r0/c0，请先用 read_document 确认");
        }

        if (!context.blockIds().contains(target)) {
            throw new BusinessException(422,
                    "目标「" + target + "」在当前版本中不存在；请先调用 read_document");
        }

        DocumentEditPlan plan = new DocumentEditPlan(
                "修改单元格 " + target,
                List.of(new Operation("SET_CELL_TEXT", target, content, reason)),
                null);
        return ToolResult.withPlan("将把单元格 " + target + " 改为："
                + abbreviate(content) + "（待确认）", plan);
    }

    /** DOCX 的单元格块 ID 形如 body/t0/r0/c0（可能更深，如 body/t0/r0/c1/p0）。 */
    private static boolean isCellBlock(String blockId) {
        return blockId != null && blockId.matches("^body(?:/t\\d+/r\\d+/c\\d+)+$");
    }

    private static String abbreviate(String text) {
        String single = text.replace('\n', ' ').trim();
        return single.length() > 60 ? single.substring(0, 60) + "…" : single;
    }
}
