package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.service.AiTool;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocumentEditPlan.Operation;
import com.documenter.vo.DocumentEditPlan.OperationType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 修改段落（TODO 4.2「封装受控工具：修改段落」）。
 *
 * <p><b>这个工具不落版本</b>。它只把模型的意图收集成一个
 * {@link DocumentEditPlan}，由确认环节决定是否执行。
 * 这样做是为了满足 TODO「在提交新版本前提供修改说明和差异预览；
 * 用户能够确认、继续修改或放弃结果」——
 * 如果工具直接落版本，用户的「放弃」就无从谈起。
 *
 * <p>参数设计上只暴露三项：操作类型、目标块、新内容。
 * 刻意<b>不</b>暴露版本号或文件 ID —— 那些来自上下文，
 * 让模型传就等于给它越权与版本错配的机会。
 */
@Component
public class EditParagraphTool extends AbstractAiTool {

    public static final String NAME = "edit_paragraph";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "修改文档中的段落。支持替换文本、在指定段落前后插入、删除段落。"
                + "target 必须是 read_document 返回的块 ID。"
                + "本工具只生成待确认的修改计划，不会立即改动文档。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("operation",
                        "操作类型：REPLACE_TEXT（替换文本）、INSERT_BEFORE（前插）、"
                                + "INSERT_AFTER（后插）、DELETE（删除）"),
                ToolParameter.required("target", "目标块 ID，来自 read_document 的结果"),
                ToolParameter.optional("content", "新内容；DELETE 操作不需要"),
                ToolParameter.optional("reason", "这么改的理由，会展示给用户帮助判断"));
    }

    @Override
    public boolean mutating() {
        // 虽然这里不直接落版本，但它属于「会产生变更」的意图，
        // 因此在提交前确认模式下需要走确认流程
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        Map<String, String> args = normalize(arguments);

        String operationName = requireString(args, "operation", "操作类型");
        String target = requireString(args, "target", "目标块 ID");
        String content = optionalString(args, "content");
        String reason = optionalString(args, "reason");

        OperationType type = resolveType(operationName);
        if (type == null || !isParagraphOperation(type)) {
            // 明确列出本工具支持的类型，让模型能自我纠正而不是反复尝试同一个错名字
            throw new com.documenter.exception.BusinessException(422,
                    "edit_paragraph 不支持操作类型「" + operationName + "」；支持："
                            + "REPLACE_TEXT、INSERT_BEFORE、INSERT_AFTER、DELETE");
        }

        // 尽早给出目标不存在的反馈。完整的批量校验在 EditPlanValidator 里做，
        // 但在这里就反馈能让模型更快修正，少走一轮循环
        if (!context.blockIds().contains(target)) {
            throw new com.documenter.exception.BusinessException(422,
                    "目标块「" + target + "」在当前版本中不存在；"
                            + "请先调用 read_document 确认可用的块 ID");
        }

        if (type.requiresContent() && (content == null || content.isBlank())) {
            throw new com.documenter.exception.BusinessException(422,
                    "操作 " + type + " 需要提供 content");
        }

        DocumentEditPlan plan = new DocumentEditPlan(
                "修改段落 " + target,
                List.of(new Operation(type.name(), target, content, reason)),
                null);

        String summary = type == OperationType.DELETE
                ? "将删除段落 " + target
                : "将把段落 " + target + " 改为：" + abbreviate(content);
        return ToolResult.withPlan(summary + "（待确认）", plan);
    }

    private static OperationType resolveType(String name) {
        return OperationType.parse(name);
    }

    private static boolean isParagraphOperation(OperationType type) {
        return type == OperationType.REPLACE_TEXT
                || type == OperationType.INSERT_BEFORE
                || type == OperationType.INSERT_AFTER
                || type == OperationType.DELETE;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').trim();
        return single.length() > 80 ? single.substring(0, 80) + "…" : single;
    }

    /** 供聚合工具使用：把多个计划合并成一个，减少确认次数。 */
    public static DocumentEditPlan merge(List<DocumentEditPlan> plans) {
        List<Operation> operations = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (DocumentEditPlan plan : plans) {
            operations.addAll(plan.operations());
            if (summary.length() > 0) {
                summary.append("；");
            }
            summary.append(plan.summary());
        }
        return new DocumentEditPlan(summary.toString(), operations, null);
    }
}
