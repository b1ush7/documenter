package com.documenter.component;

import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具基类（TODO 4.2）。
 *
 * <p>承担两件各工具都要做、但容易写得五花八门的事：
 * <ol>
 *   <li><b>生成 JSON Schema</b>：OpenAI 协议要求所有参数都放在一个
 *       {@code JsonObjectSchema} 里。手写容易漏掉 {@code required} 或类型不符，
 *       导致模型侧报错而错误信息指向协议而不是我们的代码，很难排查。</li>
 *   <li><b>解析模型给出的参数</b>：模型可能返回 null、数组、非对象 JSON，
 *       也可能把数字写成字符串。这些都必须归一化，否则工具内部会出现
 *       ClassCastException —— 那是模型的输入问题，不该表现成服务端故障。</li>
 * </ol>
 */
public abstract class AbstractAiTool implements AiTool {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public ToolSpecification specification() {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters()) {
            // 所有参数一律按字符串暴露：模型对「数字还是字符串」的判断并不可靠，
            // 归一化的工作交给下面的解析方法，比依赖模型守规矩更可靠
            schema.addStringProperty(parameter.name(), parameter.description());
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        if (!required.isEmpty()) {
            schema.required(required);
        }
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(schema.build())
                .build();
    }

    /**
     * 把模型给的参数规范化为字符串映射。
     *
     * <p>返回值不会是 null；非法或缺失的参数在读取时通过 {@link #requireString}
     * 给出明确错误，而不是在这里静默丢弃 —— 静默丢弃会让模型以为参数生效了。
     */
    protected Map<String, String> normalize(Map<String, Object> arguments) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (arguments == null) {
            return normalized;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            normalized.put(entry.getKey(), asText(entry.getValue()));
        }
        return normalized;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            // 有些模型会把整个参数对象再序列化成字符串
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof JsonNode node) {
            return node.isString() ? node.asString() : node.toString();
        }
        return String.valueOf(value);
    }

    /** 必须存在的参数。 */
    protected static String requireString(Map<String, String> arguments, String name,
                                          String description) {
        String value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new BusinessException(422, "缺少必填参数「" + name + "」（" + description + "）");
        }
        return value;
    }

    /** 可选参数。 */
    protected static String optionalString(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 解析为整数。
     *
     * <p>模型经常把数字写成字符串（{@code "1"}）或带单位（{@code "1 页"}），
     * 因此这里做宽松解析并给出可读错误。
     */
    protected static Integer optionalInt(Map<String, String> arguments, String name) {
        String value = optionalString(arguments, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(422,
                    "参数「" + name + "」应为整数，实际为「" + value + "」");
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + name() + "}";
    }
}
