package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 OpenAI 兼容协议的模型提供者（TODO 4.2）。
 *
 * <p>用 OpenAI 兼容协议而不是各家私有 SDK，是因为它已成为事实标准：
 * 阿里百炼、vLLM、Ollama、One-API 等网关都提供该接口。
 * 这样切换供应商只改 {@code res.ai.base-url} 与 {@code model-name}，
 * 不需要引入新的客户端依赖。
 *
 * <p><b>关于密钥</b>：只从配置读取，绝不写进日志或返回给前端。
 * {@link #toString()} 也已避免输出密钥（见类内 toString 说明）。
 */
@Slf4j
@Component
public class OpenAiCompatibleModelProvider implements AiModelProvider {

    private static final String PROVIDER = "openai-compatible";

    private final AiModelProperties properties;

    /** 惰性构建：未配置时不创建客户端，也不做任何网络相关初始化。 */
    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingChatModel;

    public OpenAiCompatibleModelProvider(AiModelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean available() {
        return properties.configured();
    }

    @Override
    public String configurationHint() {
        return properties.configurationHint();
    }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new BusinessException(503, "文本模型未就绪；" + configurationHint());
        }
        ChatModel current = chatModel;
        if (current == null) {
            synchronized (this) {
                current = chatModel;
                if (current == null) {
                    current = build();
                    chatModel = current;
                }
            }
        }
        return current;
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        if (!available()) {
            throw new BusinessException(503, "文本模型未就绪；" + configurationHint());
        }
        StreamingChatModel current = streamingChatModel;
        if (current == null) {
            synchronized (this) {
                current = streamingChatModel;
                if (current == null) {
                    current = buildStreaming();
                    streamingChatModel = current;
                }
            }
        }
        return current;
    }

    private ChatModel build() {
        Duration timeout = properties.getTimeout();
        log.info("初始化文本模型, provider={}, baseUrl={}, modelName={}, timeout={}, maxOutputTokens={}",
                PROVIDER, properties.getBaseUrl(), properties.getModelName(), timeout,
                properties.getMaxOutputTokens());
        // 刻意不打印 apiKey：日志常被集中收集，密钥一旦进去很难彻底清除
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxOutputTokens())
                .timeout(timeout)
                .maxRetries(properties.getMaxRetries())
                .build();
    }

    private StreamingChatModel buildStreaming() {
        Duration timeout = properties.getTimeout();
        log.info("初始化流式文本模型, provider={}, baseUrl={}, modelName={}, timeout={}",
                PROVIDER, properties.getBaseUrl(), properties.getModelName(), timeout);
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxOutputTokens())
                .timeout(timeout)
                .build();
    }

    /** 只暴露非敏感信息，避免密钥随对象被打印到日志。 */
    @Override
    public String toString() {
        return "OpenAiCompatibleModelProvider{modelName=" + properties.getModelName()
                + ", baseUrl=" + properties.getBaseUrl()
                + ", apiKey=***}";
    }
}
