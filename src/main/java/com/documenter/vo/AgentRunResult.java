package com.documenter.vo;

import java.util.List;

/**
 * 一次智能体执行的过程与结果（TODO 4.2「建立分析需求 → 选择工具 → 执行 → 检查结果的循环，
 * 限制步骤数、调用次数和重试次数」）。
 *
 * <p>把「过程」也返回，而不只是最终答复，是为了满足两个实际需求：
 * <ul>
 *   <li>用户要能看出 AI <b>做了什么</b>，而不是只看到一个结论 ——
 *       否则无法判断结果是否可信；</li>
 *   <li>出问题时能定位是<b>哪一步</b>出了问题，而不是只知道「失败了」。</li>
 * </ul>
 *
 * @param reply          给用户的最终文本答复
 * @param steps          实际发生的步骤（含工具调用与结果摘要）
 * @param plans          本次产生的待确认编辑计划；为空表示没有需要用户确认的修改
 * @param usage          用量统计，用于成本核算与额度控制
 * @param termination    循环结束的原因，便于区分「正常完成」与「被上限截断」
 * @param truncated      是否因达到上限而被截断；true 时 reply 可能不是完整结论
 */
public record AgentRunResult(
        String reply,
        List<Step> steps,
        List<DocumentEditPlan> plans,
        Usage usage,
        Termination termination,
        boolean truncated,
        List<Source> sources,
        String verificationNotice) {

    public AgentRunResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        plans = plans == null ? List.of() : List.copyOf(plans);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 兼容不含来源信息的内部构造点。 */
    public AgentRunResult(String reply, List<Step> steps, List<DocumentEditPlan> plans,
                          Usage usage, Termination termination, boolean truncated) {
        this(reply, steps, plans, usage, termination, truncated, List.of(),
                "未提供资料来源；涉及新增事实、数字或结论时请人工核验。");
    }

    /** 本次是否有待用户确认的修改。 */
    public boolean hasPendingPlans() {
        return !plans.isEmpty();
    }

    /**
     * 单步记录。
     *
     * @param index      步骤序号，从 1 开始
     * @param kind       步骤类型
     * @param name       工具名或模型调用标识
     * @param detail     结果摘要或错误说明
     * @param durationMs 耗时
     */
    public record Step(int index, StepKind kind, String name, String detail, long durationMs) {
    }

    /** 用户为本轮提供的来源，服务端未联网核验其真实性。 */
    public record Source(String title, String url, String excerpt) { }

    /** 步骤类型。 */
    public enum StepKind {
        /** 调用模型。 */
        MODEL_CALL,
        /** 执行工具。 */
        TOOL_CALL,
        /** 工具或模型调用失败。 */
        FAILURE
    }

    /**
     * 用量统计（TODO 4.2「记录模型调用量、耗时和估算成本」）。
     *
     * @param modelCalls        智能体调用 ChatModel 的尝试次数（含失败重试，不含客户端内部 HTTP 重试）
     * @param toolCalls         工具调用次数
     * @param inputTokens       输入 token 总数
     * @param outputTokens      输出 token 总数
     * @param estimatedCost     估算成本；未配置单价时为 0，**不代表免费**
     * @param costConfigured    是否配置了计价单价。false 时 estimatedCost 无意义，
     *                          前端必须据此提示「未配置计价」而不是显示 0 元
     * @param elapsedMs         总耗时
     */
    public record Usage(int modelCalls, int toolCalls, Integer inputTokens, Integer outputTokens,
                        java.math.BigDecimal estimatedCost, boolean costConfigured,
                        long elapsedMs) {
    }

    /** 循环结束原因。 */
    public enum Termination {
        /** 模型给出了最终答复。 */
        COMPLETED,
        /** 达到最大步数。 */
        MAX_STEPS,
        /** 达到最大工具调用次数。 */
        MAX_TOOL_CALLS,
        /** 同一工具重复调用过多。 */
        REPEATED_TOOL,
        /** 调用方请求中止。 */
        CANCELLED,
        /** 工具计划合并后无法整体执行。 */
        INVALID_PLAN,
        /** 用户当日调用额度已用尽。 */
        QUOTA_EXCEEDED,
        /** 无法安全检查额度，未继续调用模型。 */
        QUOTA_UNAVAILABLE,
        /** 模型不可用。 */
        MODEL_UNAVAILABLE
    }
}
