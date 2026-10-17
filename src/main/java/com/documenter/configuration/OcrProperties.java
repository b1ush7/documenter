package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 云端视觉 OCR、页面渲染、输出限额与缓存配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.ocr")
public class OcrProperties {

    /** 默认关闭，避免误把敏感页面发送到云端；启用前必须由管理员配置 OCR 模型。 */
    private boolean enabled;

    private String defaultLanguage = "auto";

    /** 允许请求的语言组合，逗号分隔，例如 auto,zh,en。 */
    private String allowedLanguages = "auto,zh,en";

    /** PDF 扫描页送入 OCR 时的渲染 DPI。 */
    private int dpi = 200;

    private int maxDpi = 300;

    /** 单次最多识别页数；调用方可用 pages 精确选择。 */
    private int maxPages = 50;

    private int maxWords = 200_000;

    private int maxOutputChars = 2_000_000;

    private int maxTableRows = 200;

    private int maxTableColumns = 100;

    private int maxTableCells = 5000;

    /** 发送到云端的单页 PNG 最大字节数。 */
    private long maxImageBytes = 20L * 1024 * 1024;

    /** 云端单次响应最大字节数，防止异常服务返回超大 JSON。 */
    private int maxResponseBytes = 16 * 1024 * 1024;

    /** 低于该百分比的行标记 needsReview，由调用方进入人工校正流程。 */
    private double lowConfidenceThreshold = 60;

    private boolean cacheEnabled = true;

    /** 放在存储临时目录下，由既有临时文件清理任务回收。 */
    private String cacheDirectory = "ocr-cache";

    public Set<String> allowedLanguageSet() {
        Set<String> result = new LinkedHashSet<>();
        if (allowedLanguages == null) return result;
        Arrays.stream(allowedLanguages.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .forEach(result::add);
        return Set.copyOf(result);
    }
}
