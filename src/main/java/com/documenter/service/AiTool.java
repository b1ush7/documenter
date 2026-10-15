package com.documenter.service;

import com.documenter.vo.DocumentEditPlan;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Map;

/**
 * 受控工具（TODO 4.2「封装受控工具：读取文档、修改段落、编辑表格、插入图片、
 * 生成预览、导出文档；工具统一执行权限和版本检查」）。
 *
 * <p><b>为什么不让模型直接调用业务 Service</b>：那样等于把内部 API
 * 连同其全部参数能力暴露给模型，模型可以构造出任何调用 ——
 * 包括删除文件、读取他人数据这类与当前任务无关的操作。
 * 工具层是权限与参数的收窄面：
 * <ul>
 *   <li>只暴露完成文档编辑任务所需的<b>有限能力</b>；</li>
 *   <li>所有工具都<b>不接受用户 ID 参数</b>，一律使用上下文里已认证的身份，
 *       因此模型无法通过构造参数越权；</li>
 *   <li>所有修改类工具都带<b>明确的版本号</b>，且必须与上下文中的版本一致，
 *       避免「基于 v1 生成的计划被应用到已经变成 v3 的文档上」。</li>
 * </ul>
 *
 * <p>工具不自行拼接提示词，也不返回任意文本给模型：
 * 返回值会被截断到 {@code res.ai.max-tool-result-chars}，
 * 防止一次读取把上下文撑满导致后续调用失败。
 */
public interface AiTool {

    /** 工具名，必须与 {@link #specification()} 中的名字一致。 */
    String name();

    /** 给模型看的工具描述。 */
    String description();

    /**
     * 参数定义，用于生成 JSON Schema。
     *
     * <p>刻意用显式的参数描述而不是反射：反射会把所有公开方法都变成工具，
     * 而我们需要的是「哪些参数可以被模型控制」这件事由人明确决定。
     */
    List<ToolParameter> parameters();

    /** 构造给模型看的工具规范。 */
    ToolSpecification specification();

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数（JSON 对象）
     * @param context   受控执行上下文：提供已认证身份、明确版本、取消检查与产出收集
     * @return 给模型的文本结果（应为简洁的事实描述，而非大段文档内容）
     * @throws com.documenter.exception.BusinessException 参数非法、越权或版本不符
     */
    ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context);

    /**
     * 该工具是否会产生新的文档版本。
     *
     * <p>用于在「提交前确认」模式下区分只读工具与修改类工具：
     * 只读工具可以直接执行，修改类工具需要先收集计划、经用户确认后才落版本。
     */
    default boolean mutating() {
        return false;
    }

    /**
     * 工具参数定义。
     *
     * @param name        参数名
     * @param description 给模型看的说明
     * @param required    是否必填
     */
    record ToolParameter(String name, String description, boolean required) {

        public static ToolParameter required(String name, String description) {
            return new ToolParameter(name, description, true);
        }

        public static ToolParameter optional(String name, String description) {
            return new ToolParameter(name, description, false);
        }
    }

    /**
     * 工具执行结果。
     *
     * @param text        给模型的文本说明
     * @param plan        若该工具产出了编辑计划（修改类工具），通过它交给确认环节；
     *                    只读工具为 null
     * @param artifactRef 产出的资源引用（如生成的预览版本号、导出文件名），供前端展示；
     *                    无产出时为 null
     */
    record ToolResult(String text, DocumentEditPlan plan, String artifactRef) {

        public static ToolResult of(String text) {
            return new ToolResult(text, null, null);
        }

        public static ToolResult withPlan(String text, DocumentEditPlan plan) {
            return new ToolResult(text, plan, null);
        }

        public static ToolResult withArtifact(String text, String artifactRef) {
            return new ToolResult(text, null, artifactRef);
        }
    }
}
