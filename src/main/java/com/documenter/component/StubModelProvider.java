package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiModelProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 无外部依赖的桩模型提供者（TODO 4.2）。
 *
 * <p><b>为什么需要一个「假模型」</b>：智能体循环的正确性（步骤上限、工具白名单、
 * 编辑计划校验、提示词注入防护、错误重试）与模型质量无关，
 * 而这部分恰恰是最容易出错、也最需要测试的地方。没有可用的模型时，
 * 这些逻辑要么无法验证，要么只能靠人工读代码 —— 两者都不可接受。
 *
 * <p>因此桩模型实现完整的 {@link ChatModel} 接口，让智能体循环的真实代码路径
 * 可以被端到端测试：它真的会「决定调用工具」，循环真的会执行工具并回填结果。
 *
 * <p><b>它不是用来糊弄人的</b>：桩模型的响应带明确标识，
 * 会话记录里的 {@code modelName} 会写成 {@code stub}，
 * 前端与管理员都能看出这不是真实模型产出。
 *
 * <p>触发方式：{@code res.ai.provider=stub}，或在 {@code auto} 模式下未配置 api-key。
 */
@Slf4j
@Component
public class StubModelProvider implements AiModelProvider {

    public static final String PROVIDER = "stub";

    /** 会话记录里标注的模型名，便于与真实调用区分。 */
    public static final String MODEL_NAME = "stub";

    private final AiModelProperties properties;
    private final ChatModel chatModel = new StubChatModel();
    private final StreamingChatModel streamingChatModel = new StubStreamingChatModel();

    public StubModelProvider(AiModelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    /**
     * 桩模型始终「可用」。
     *
     * <p>这是刻意的：它就是为了在真实模型不可用时让系统仍能跑通流程，
     * 因此不应因为缺少 api-key 而拒绝服务。
     */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String configurationHint() {
        return "当前使用桩模型（非真实模型）。要接入真实模型请设置 res.ai.enabled=true 与 "
                + "res.ai.base-url / res.ai.api-key / res.ai.model-name";
    }

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        return streamingChatModel;
    }

    /**
     * 确定性的桩对话模型。
     *
     * <p>行为规则（刻意保持简单且可预测，便于断言）：
     * <ul>
     *   <li>若最近一条消息是工具执行结果，则视为「已经拿到资料」，返回最终答复并结束循环；</li>
     *   <li>否则返回一次工具调用请求，工具名与参数从用户消息里的提示标记解析，
     *       便于测试构造任意场景；</li>
     *   <li>无法解析出工具意图时直接给出文本答复，不调用工具。</li>
     * </ul>
     *
     * <p>这样既能让循环真实地走一遍「调用工具 → 回填结果 → 收尾」，
     * 又不会因为随机性导致测试不稳定。
     */
    private final class StubChatModel implements ChatModel {

        private static final String TOOL_MARKER = "[stub-tool]";

        @Override
        public ChatResponse doChat(ChatRequest request) {
            List<ChatMessage> messages = request.messages();
            if (messages == null || messages.isEmpty()) {
                throw new BusinessException(500, "桩模型收到空消息列表");
            }

            // 已经有工具结果 → 收尾
            boolean hasToolResult = messages.stream()
                    .anyMatch(message -> message instanceof dev.langchain4j.data.message
                            .ToolExecutionResultMessage);
            if (hasToolResult) {
                return response(AiMessage.from(
                        "已处理本轮工具请求；如有修改计划，需确认后才会保存。（桩模型响应，非真实模型产出）"));
            }

            String lastUserText = lastUserText(messages);
            ToolExecutionRequest toolRequest = parseToolRequest(lastUserText);
            if (toolRequest == null) {
                return response(AiMessage.from(
                        "当前使用桩模型，未执行任何真实模型调用。"
                                + "要验证工具调用能力，请在消息中带上 [stub-tool] 标记。"));
            }
            log.debug("桩模型决定调用工具: {}", toolRequest.name());
            return response(AiMessage.from(List.of(toolRequest)));
        }

        private ChatResponse response(AiMessage message) {
            return ChatResponse.builder()
                    .aiMessage(message)
                    // 固定的小额用量：让成本统计与额度逻辑能被测试覆盖
                    .tokenUsage(new TokenUsage(120, 40))
                    .modelName(MODEL_NAME)
                    .finishReason(FinishReason.STOP)
                    .build();
        }

        private String lastUserText(List<ChatMessage> messages) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                if (messages.get(index) instanceof dev.langchain4j.data.message.UserMessage user) {
                    return user.singleText();
                }
            }
            return "";
        }

        /**
         * 解析工具调用意图。
         *
         * <p>约定格式：{@code [stub-tool] name={"json args"}}。
         * 用一个显式标记而不是猜自然语言，是为了让测试场景可精确构造 ——
         * 桩模型的价值在于「可预测」，不在于「像真模型」。
         */
        private ToolExecutionRequest parseToolRequest(String text) {
            if (text == null) {
                return null;
            }
            int marker = text.indexOf(TOOL_MARKER);
            if (marker < 0) {
                return null;
            }
            String rest = text.substring(marker + TOOL_MARKER.length()).trim();
            int space = rest.indexOf(' ');
            String name = space < 0 ? rest : rest.substring(0, space);
            if (name.isBlank()) {
                return null;
            }
            // 只取「第一个完整的 JSON 对象」，忽略其后的文字。
            // 不能简单地取「空格之后的全部内容」：真实调用里用户消息在工具标记之后
            // 还带着上下文说明（如「当前文档：版本 v1…」），
            // 一起当成参数会让 JSON 解析失败，表现为工具收到空参数。
            String arguments = space < 0
                    ? "{}" : extractJsonObject(rest.substring(space + 1).trim());
            return ToolExecutionRequest.builder()
                    .id("stub-" + System.nanoTime())
                    .name(name)
                    .arguments(arguments)
                    .build();
        }

        /**
         * 从文本开头提取第一个配平的大括号片段。
         *
         * <p>按配平而不是找最后一个 {@code }}：参数里可能包含嵌套对象或字符串中的括号。
         * 找不到配平片段时返回 {@code {}}，让工具层给出「缺少参数 X」的可读反馈。
         */
        private String extractJsonObject(String text) {
            int start = text.indexOf('{');
            if (start < 0) {
                return "{}";
            }
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < text.length(); index++) {
                char current = text.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (current == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (current == '{') {
                    depth++;
                } else if (current == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, index + 1);
                    }
                }
            }
            return "{}";
        }
    }

    /**
     * 桩流式模型仍走同一套确定性响应，但按固定片段回放，专门验证 SSE 管道。
     */
    private final class StubStreamingChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            try {
                ChatResponse response = chatModel.chat(request);
                String text = response.aiMessage() == null ? null : response.aiMessage().text();
                if (text != null) {
                    int chunkSize = 24;
                    for (int index = 0; index < text.length(); index += chunkSize) {
                        handler.onPartialResponse(text.substring(index,
                                Math.min(text.length(), index + chunkSize)));
                    }
                }
                handler.onCompleteResponse(response);
            } catch (Throwable failure) {
                handler.onError(failure);
            }
        }
    }

    /** 供诊断使用。 */
    public String configuredProvider() {
        return properties.getProvider();
    }
}
