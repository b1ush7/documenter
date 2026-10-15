package com.documenter.vo;

import java.util.List;

/**
 * 结构化编辑计划（TODO 4.2「将需求转换为结构化编辑计划，例如目标块、操作类型、
 * 新内容与预期结果；校验目标、字段和操作范围」）。
 *
 * <p>为什么不让模型直接产出「改好的文档」：那样无法校验、无法审计、
 * 也无法在提交前给用户看差异。把模型的输出约束成一张操作清单后：
 * <ul>
 *   <li>每个操作的目标是<b>确定的块 ID</b>，可以校验它是否真实存在；</li>
 *   <li>操作类型是<b>有限集合</b>，可以拒绝未支持的操作；</li>
 *   <li>计划可以在执行前<b>整体校验</b>，不合格直接退回给模型重来，
 *       而不是执行到一半失败留下半成品；</li>
 *   <li>计划可以落库，用于事后回答「这次修改到底做了什么」。</li>
 * </ul>
 *
 * @param summary    面向用户的修改说明，用于确认界面的标题
 * @param operations 操作清单，按期望的执行顺序排列
 * @param expected   模型对结果的预期描述，用于人工核对是否达到意图
 */
public record DocumentEditPlan(
        String summary,
        List<Operation> operations,
        String expected) {

    public DocumentEditPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** 计划是否为空（模型没能产出任何可执行操作）。 */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * 单个编辑操作。
     *
     * @param type     操作类型，见 {@link OperationType}
     * @param target   目标块 ID（DOCX 结构路径，或 XLSX 的 {@code 工作表!单元格}）
     * @param content  新内容；删除类操作为 null
     * @param reason   模型给出的理由，展示给用户以帮助判断是否采纳
     */
    public record Operation(
            String type,
            String target,
            String content,
            String reason) {

        /** 解析为枚举；未知类型返回 null，由校验器报错而不是抛异常。 */
        public OperationType operationType() {
            return OperationType.parse(type);
        }
    }

    /**
     * 允许的操作类型。
     *
     * <p>刻意做成<b>有限枚举而不是自由字符串</b>：模型很容易编造出
     * 「RENAME_SECTION」这类听起来合理但后端并不支持的操作。
     * 用枚举在校验阶段就拒绝，比执行到一半才发现要好得多。
     */
    public enum OperationType {

        /** 替换段落文本。需要 content。 */
        REPLACE_TEXT(true),

        /** 在指定块之前插入段落。需要 content。 */
        INSERT_BEFORE(true),

        /** 在指定块之后插入段落。需要 content。 */
        INSERT_AFTER(true),

        /** 删除指定块。不需要 content。 */
        DELETE(false),

        /** 设置单元格文本。需要 content。 */
        SET_CELL_TEXT(true),

        /** 设置段落格式（标题层级等）。需要 content。 */
        FORMAT_PARAGRAPH(true);

        private final boolean requiresContent;

        OperationType(boolean requiresContent) {
            this.requiresContent = requiresContent;
        }

        /** 该操作是否必须带内容。 */
        public boolean requiresContent() {
            return requiresContent;
        }

        /** 宽松解析：容忍大小写与空白，无法识别时返回 null。 */
        public static OperationType parse(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            for (OperationType type : values()) {
                if (type.name().equals(normalized)) {
                    return type;
                }
            }
            return null;
        }
    }
}
