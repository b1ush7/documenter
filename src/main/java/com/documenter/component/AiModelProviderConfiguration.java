package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.service.AiModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

/**
 * 文本模型提供者装配（TODO 4.2）。
 *
 * <p>两个实现都注册为 Bean，再用一个 {@code @Primary} 的委托决定智能体实际用哪个。
 * 为什么不在装配期只创建一个：
 * <ul>
 *   <li>让「当前生效的是真实模型还是桩模型」成为<b>显式且可观测</b>的状态 ——
 *       启动日志会明确写出来，管理员不必去猜；</li>
 *   <li>部署时往往先不配密钥跑通流程，之后再补上配置；
 *       若只创建一个 Bean，切换过程会牵涉到条件装配的重新评估，容易出错。</li>
 * </ul>
 *
 * <p>选择规则：
 * <ul>
 *   <li>{@code provider=stub} → 强制桩模型（用于无网络的测试与演示）</li>
 *   <li>{@code provider=openai} → 强制真实模型，未配齐时调用报 503</li>
 *   <li>{@code provider=auto}（默认）→ 配齐了就用真实模型，否则用桩模型</li>
 * </ul>
 *
 * <p><b>选择桩模型时会打 WARN 级日志</b>：这是一个容易被忽略的状态 ——
 * 系统看起来「能用」，但产出并非真实模型结果。让它只出现在 INFO 里
 * 会让人误以为已经接好模型。
 */
@Slf4j
@Configuration
public class AiModelProviderConfiguration {

    @Bean
    @Primary
    public AiModelProvider activeAiModelProvider(AiModelProperties properties,
                                                 OpenAiCompatibleModelProvider openAiProvider,
                                                 StubModelProvider stubProvider) {
        String requested = properties.getProvider() == null
                ? "auto" : properties.getProvider().trim().toLowerCase(Locale.ROOT);

        return switch (requested) {
            case StubModelProvider.PROVIDER -> {
                log.warn("AI 提供者被显式配置为桩模型（非真实模型），产出内容不可用于生产判断");
                yield stubProvider;
            }
            case "openai" -> {
                log.info("AI 提供者使用 OpenAI 兼容协议");
                yield openAiProvider;
            }
            default -> {
                if (openAiProvider.available()) {
                    log.info("AI 提供者自动选择为 OpenAI 兼容协议, modelName={}",
                            properties.getModelName());
                    yield openAiProvider;
                }
                log.warn("未检测到可用的模型配置（res.ai.base-url / api-key / model-name），"
                        + "当前回退为桩模型：对话能力可用，但产出不是真实模型结果。"
                        + "部署时请补齐配置。");
                yield stubProvider;
            }
        };
    }
}
