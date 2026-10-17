package com.documenter.util;

import com.documenter.exception.BusinessException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变量定义 JSON 校验（TODO 3.2 公共模板的 variables 字段）。
 *
 * <p>为什么必须校验而不只是存字符串：variables 会在「按模板生成文档」时被解析执行，
 * 非法结构会在生成阶段才暴露，而且可能出现在批量生成的中途。
 * 存之前就校验能把问题挡在录入环节，错误信息也能直接告诉录入者哪里不对。
 *
 * <p>约定格式（JSON 数组，元素为对象）：
 * <pre>
 * [
 *   {"name": "title", "label": "标题", "required": true, "description": "文档标题"}
 * ]
 * </pre>
 * 只有 {@code name} 是必填项，其余字段可省略。
 */
public final class TemplateVariables {

    /** 单个模板允许的变量数量上限，防止把超长 JSON 塞进 TEXT 列。 */
    private static final int MAX_VARIABLES = 100;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemplateVariables() {
    }

    /**
     * 校验并规范化变量定义 JSON。
     *
     * @param raw 前端传入的 JSON 字符串，null 或空白视为「无变量」
     * @return 规范化后的 JSON 字符串；无变量时返回 null
     * @throws BusinessException 格式不合法
     */
    public static String validateAndNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<Map<String, Object>> parsed;
        try {
            // 显式指定泛型，避免 readValue 返回原始 List 后再做未检查转换
            JavaType listType = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Map.class);
            parsed = MAPPER.readValue(raw, listType);
        } catch (Exception e) {
            throw new BusinessException(400, "变量定义必须是 JSON 数组，例如 [{\"name\":\"title\",\"label\":\"标题\"}]");
        }
        if (parsed == null) {
            return null;
        }
        if (parsed.size() > MAX_VARIABLES) {
            throw new BusinessException(400, "变量数量不能超过 " + MAX_VARIABLES + " 个");
        }

        Set<String> names = new HashSet<>();
        for (int i = 0; i < parsed.size(); i++) {
            Map<String, Object> item = parsed.get(i);
            if (item == null) {
                throw new BusinessException(400, "第 " + (i + 1) + " 个变量不能为 null");
            }
            Object name = item.get("name");
            if (name == null || name instanceof String text && text.isBlank()) {
                throw new BusinessException(400, "第 " + (i + 1) + " 个变量缺少 name 字段");
            }
            if (!(name instanceof String text)) {
                throw new BusinessException(400, "第 " + (i + 1) + " 个变量的 name 必须是字符串");
            }
            String nameText = text.trim();
            if (nameText.length() > MAX_NAME_LENGTH) {
                throw new BusinessException(400, "变量 name 不能超过 " + MAX_NAME_LENGTH + " 个字符");
            }
            // 变量名会被用作占位符标识，限制字符集避免出现难以定位的解析问题
            if (!nameText.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                throw new BusinessException(400,
                        "变量 name「" + nameText + "」只能包含字母、数字和下划线，且不能以数字开头");
            }
            if (!names.add(nameText)) {
                throw new BusinessException(400, "变量 name「" + nameText + "」重复");
            }
            item.put("name", nameText);
            checkOptionalText(item.get("label"), MAX_LABEL_LENGTH, "label", i);
            checkOptionalText(item.get("description"), MAX_DESCRIPTION_LENGTH, "description", i);
            if (item.containsKey("required") && !(item.get("required") instanceof Boolean)) {
                throw new BusinessException(400, "第 " + (i + 1) + " 个变量的 required 必须是布尔值");
            }
        }

        try {
            return MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new BusinessException(400, "变量定义序列化失败");
        }
    }

    private static void checkOptionalText(Object value, int max, String field, int index) {
        if (value == null) {
            return;
        }
        if (!(value instanceof String text)) {
            throw new BusinessException(400,
                    "第 " + (index + 1) + " 个变量的 " + field + " 必须是字符串");
        }
        if (text.length() > max) {
            throw new BusinessException(400,
                    "第 " + (index + 1) + " 个变量的 " + field + " 不能超过 " + max + " 个字符");
        }
    }
}
