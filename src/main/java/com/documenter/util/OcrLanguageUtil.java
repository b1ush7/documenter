package com.documenter.util;

import com.documenter.configuration.OcrProperties;
import com.documenter.exception.BusinessException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 校验 OCR 语言组合，避免把任意用户输入直接传给本地进程参数。 */
public final class OcrLanguageUtil {

    private static final Pattern SAFE_LANGUAGE =
            Pattern.compile("^[A-Za-z0-9_]+(?:\\+[A-Za-z0-9_]+)*$");

    private OcrLanguageUtil() {
    }

    public static String resolve(String requested, OcrProperties properties) {
        String language = requested == null || requested.isBlank()
                ? properties.getDefaultLanguage() : requested.trim().toLowerCase(Locale.ROOT);
        if (language == null || !SAFE_LANGUAGE.matcher(language).matches()) {
            throw new BusinessException(400, "OCR 语言标识格式不合法");
        }
        Set<String> allowed = properties.allowedLanguageSet();
        String[] items = language.split("\\+");
        if (items.length > 1 && java.util.Arrays.asList(items).contains("auto")) {
            throw new BusinessException(400, "OCR 语言 auto 不能与其他语言组合");
        }
        for (String item : items) {
            if (!allowed.contains(item)) {
                throw new BusinessException(400,
                        "未开放 OCR 语言：" + item + "；可配置 OCR_ALLOWED_LANGUAGES");
            }
        }
        return language;
    }
}
