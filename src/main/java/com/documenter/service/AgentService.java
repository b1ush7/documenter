package com.documenter.service;

import com.documenter.vo.AgentRunResult;

import java.util.function.BooleanSupplier;

/**
 * 智能体循环（TODO 4.2「建立分析需求 → 选择工具 → 执行 → 检查结果的循环，
 * 限制步骤数、调用次数和重试次数」）。
 *
 * <p>为什么手写这个循环而不用 LangChain4j 的 {@code AiServices}：
 * TODO 明确要求限制步骤数、工具调用次数与重试次数，并且要求工具执行前
 * 统一做权限与版本检查。这些约束需要在「模型决定调用工具」与
 * 「工具真正执行」之间插入我们的判断，而 {@code AiServices} 把这段控制权
 * 收在框架内部。手写循环让每一次工具执行都必须经过我们的检查点。
 */
public interface AgentService {

    /** 持久化会话的历史角色与内容；仅 USER / ASSISTANT 参与模型上下文。 */
    record ConversationTurn(String role, String content) { }

    /** SSE 增量事件；TEXT_DELTA 是模型实际产生的增量文本。 */
    record StreamEvent(StreamEventType type, String text) { }

    enum StreamEventType {
        STARTED,
        TEXT_DELTA,
        COMPLETED
    }

    com.documenter.vo.AgentRunResult runConversation(Long userId, Long fileId, Integer versionNo,
            String instruction, java.util.List<ConversationTurn> history, BooleanSupplier cancelled);

    /**
     * 带用户来源执行一轮对话。
     *
     * <p>来源会作为不可信资料放入模型上下文，并在结果中原样回显。服务端不会把用户提供的资料
     * 冒充成联网核验结果。
     */
    default com.documenter.vo.AgentRunResult runConversationWithSources(
            Long userId, Long fileId, Integer versionNo, String instruction,
            java.util.List<ConversationTurn> history, java.util.List<com.documenter.vo.AgentRunResult.Source> sources,
            BooleanSupplier cancelled) {
        return runConversation(userId, fileId, versionNo, instruction, history, cancelled);
    }

    /** 流式执行；默认实现仍返回完整结果，只发出开始与完成事件。 */
    default com.documenter.vo.AgentRunResult runConversationStreaming(
            Long userId, Long fileId, Integer versionNo, String instruction,
            java.util.List<ConversationTurn> history, java.util.List<com.documenter.vo.AgentRunResult.Source> sources,
            BooleanSupplier cancelled, java.util.function.Consumer<StreamEvent> events) {
        events.accept(new StreamEvent(StreamEventType.STARTED, null));
        com.documenter.vo.AgentRunResult result = runConversationWithSources(
                userId, fileId, versionNo, instruction, history, sources, cancelled);
        if (result.reply() != null && !result.reply().isBlank()) {
            events.accept(new StreamEvent(StreamEventType.TEXT_DELTA, result.reply()));
        }
        events.accept(new StreamEvent(StreamEventType.COMPLETED, null));
        return result;
    }

    /**
     * 执行一轮智能体交互。
     *
     * @param userId       已认证用户，<b>不接受外部传入</b>
     * @param fileId       关联文件；为 null 时不绑定文档（纯对话）
     * @param versionNo    绑定的明确文档版本；有 fileId 时必填
     * @param instruction  用户本轮的自然语言要求
     * @param history      历史消息（用于多轮对话），可为空
     * @param cancelled    外部取消信号；循环在每步检查它，供用户中止长时间运行
     * @return 过程与结果
     */
    AgentRunResult run(Long userId, Long fileId, Integer versionNo, String instruction,
                       java.util.List<String> history, BooleanSupplier cancelled);

    /**
     * 执行经用户确认的编辑计划（TODO 4.2「用户能够确认、继续修改或放弃结果」）。
     *
     * <p>默认提交前确认模式下，{@link #run} 的段落、表格和图片工具均只产出计划。
     * 本方法在用户确认后把计划保存为新版本；显式关闭确认时图片工具可直接写入。
     *
     * @param userId        已认证用户
     * @param fileId        文件 ID
     * @param expectVersion 计划所依据的版本，作为乐观锁期望值。
     *                      若期间文档已被其他操作改动，会以 409 失败而不是覆盖他人修改
     * @param plan          待执行的计划（来自 {@code run} 返回的 plans）
     * @param instruction   原始的自然语言指令，写入版本记录便于追溯
     * @return 执行结果，含新版本号与实际生效的批次
     */
    com.documenter.component.EditPlanExecutor.ExecutionResult executePlan(
            Long userId, Long fileId, Integer expectVersion,
            com.documenter.vo.DocumentEditPlan plan, String instruction);
}
