package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 文本模型接入配置（TODO 4.2「接入一个选定的文本模型；配置地址、密钥、超时和输出长度」）。
 *
 * <p><b>设计取舍</b>：这里用 yaml/环境变量注入，而不是复用数据库里的
 * {@code model_config} 表。原因是两者的定位不同：
 * <ul>
 *   <li>yaml 注入适合<b>单实例、单一模型</b>的部署，运维在部署时填一次即可，
 *       不需要先启动系统、登录管理员、再配模型 —— 否则系统在配置完成前完全不可用；</li>
 *   <li>{@code model_config} 表适合<b>多模型切换、按用途路由、管理员在线调整</b>。</li>
 * </ul>
 * 当前阶段采用前者，等确实需要多模型路由时再切到表驱动，
 * 因此 {@link com.documenter.service.AiModelProvider} 被抽象成接口，
 * 届时只需换一个实现，其余代码不动。
 *
 * <p>密钥留空时应用仍可启动，但所有对话能力会返回明确提示而不是抛异常 ——
 * 这样部署者可以在接入模型之前先验证其余功能。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.ai")
public class AiModelProperties {

    /** 是否启用 AI 能力。false 时对话接口返回明确提示。 */
    private boolean enabled = false;

    /**
     * 模型提供方实现：auto / openai / stub。
     *
     * <p>{@code auto} 表示「配了 api-key 就用 OpenAI 兼容协议，否则用桩模型」。
     * {@code stub} 用于无网络的测试与演示环境，返回确定性的编造响应。
     */
    private String provider = "auto";

    /**
     * 服务地址。<b>必须指向兼容 OpenAI 协议的端点</b>（如百炼兼容模式、
     * vLLM、Ollama 的 OpenAI 兼容接口、自建网关）。
     */
    private String baseUrl;

    /** 密钥。建议通过环境变量注入，不要写进版本库。 */
    private String apiKey;

    /** 模型名，如 qwen-plus。 */
    private String modelName;

    /** 采样温度。生成类任务偏高、编辑计划类任务偏低更稳定。 */
    private double temperature = 0.2;

    /** 单次请求的最大输出长度（TODO 要求配置输出长度）。 */
    private int maxOutputTokens = 2048;

    /** 单次模型调用超时。 */
    private Duration timeout = Duration.ofSeconds(60);

    /** 失败重试次数。 */
    private int maxRetries = 2;

    // ---------------------------------------------------------- 智能体循环上限

    /**
     * 单次对话允许的最大循环步数（TODO 4.2「限制步骤数、调用次数和重试次数」）。
     *
     * <p>必须有上限：模型可能陷入「调用工具 → 结果不理想 → 再调用」的循环，
     * 没有上限时一次用户请求就能消耗掉大量调用额度。
     */
    private int maxSteps = 16;

    /** 单次对话允许的工具调用总次数。 */
    private int maxToolCalls = 32;

    /** 同工具且相同参数的最大重复次数；不同编辑目标、分页不共享此限额。 */
    private int maxSameToolRepeats = 4;

    /** 单次模型调用失败后的重试次数（与 maxRetries 区分：这个是循环内的重试）。 */
    private int maxModelRetries = 1;

    // ---------------------------------------------------------- 提示词与上下文

    /**
     * 送入模型的文档内容最大字符数（TODO 4.2「长文档按结构分块并控制上下文大小」）。
     *
     * <p>超出时按结构分块截断，而不是简单截字符串 —— 后者会把一个段落切成两半，
     * 模型据此产生的编辑计划会指向不存在的块。
     */
    private int maxDocumentChars = 12000;

    /** 单条工具结果返回给模型的最大字符数，防止一次读取把上下文撑满。 */
    private int maxToolResultChars = 6000;

    /** 历史消息字符预算，独立于每页文档预算，可按部署模型上下文容量调整。 */
    private int maxHistoryChars = 48000;

    /** 是否把工具调用过程写入对话消息（便于前端展示「AI 做了什么」）。 */
    private boolean recordToolMessages = true;

    /**
     * 是否要求提交前确认（TODO 4.2「在提交新版本前提供修改说明和差异预览」）。
     *
     * <p>开启时编辑类工具只产出待确认的变更计划，不直接落版本。
     */
    private boolean requireConfirmation = true;

    // ---------------------------------------------------------- 成本统计

    /**
     * 每千输入 token 的估算单价（用于 TODO 4.2 的「估算成本」）。
     *
     * <p>默认 0 表示不计价。真实价格随模型与供应商变动，
     * 因此这里要求显式配置，而不是内置一张容易过期的价目表 ——
     * 内置价目表一旦过期，给出的成本数字比不给更糟。
     */
    private double costPer1kInputTokens = 0.0;

    /** 每千输出 token 的估算单价。 */
    private double costPer1kOutputTokens = 0.0;

    /** 单用户每日 ChatModel 调用尝试上限，含智能体重试；0 表示不限制。 */
    private int dailyCallLimitPerUser = 0;

    /** 各实例必须使用同一时区定义自然日；默认 Asia/Shanghai。 */
    private ZoneId quotaZone = ZoneId.of("Asia/Shanghai");

    /** 是否已配置到可以真实调用模型的程度。 */
    public boolean configured() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && modelName != null && !modelName.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    /** 未配置时给运维看的说明。 */
    public String configurationHint() {
        if (!enabled) {
            return "AI 能力未启用；请设置 res.ai.enabled=true 并配置 base-url、api-key、model-name";
        }
        return "模型配置不完整；请设置 res.ai.base-url、res.ai.api-key（建议用环境变量 AI_API_KEY）"
                + " 与 res.ai.model-name";
    }
}
