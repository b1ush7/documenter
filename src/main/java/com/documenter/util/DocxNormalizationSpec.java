package com.documenter.util;

import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocxStructureVO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/** Shared strict parsing for model arguments, persisted plans and confirmation. */
public final class DocxNormalizationSpec {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int MAX_PARAGRAPHS = 5000;
    public static final int MAX_IMAGES = 500;

    private DocxNormalizationSpec() { }

    public record Format(String scope, Integer headingLevel, String fontFamily, Double fontSizePoints,
                         Double lineSpacing, Double spaceBeforePoints, Double spaceAfterPoints,
                         String alignment, Boolean bold, Boolean italic, boolean includeTables) {
        public String selector() { return scope + ":" + headingLevel + ":" + includeTables; }
        public boolean matches(Integer heading, boolean inTable) {
            if (!includeTables && inTable) return false;
            return switch (scope) {
                case "ALL" -> true;
                case "BODY" -> heading == null;
                case "HEADINGS" -> heading != null && (headingLevel == null || headingLevel.equals(heading));
                default -> false;
            };
        }
    }

    public record Images(Double widthCm, Double heightCm, boolean keepAspectRatio, String alignment) { }

    public static Format format(String content) {
        var n = object(content, Set.of("scope", "headingLevel", "fontFamily", "fontSizePoints", "lineSpacing",
                "spaceBeforePoints", "spaceAfterPoints", "alignment", "bold", "italic", "includeTables"));
        String scope = text(n, "scope");
        if (scope == null) scope = "ALL";
        if (!Set.of("ALL", "BODY", "HEADINGS").contains(scope)) fail("scope 仅支持 ALL、BODY、HEADINGS");
        Double level = number(n, "headingLevel", 1, 9);
        if (level != null && (level != Math.rint(level) || !"HEADINGS".equals(scope))) {
            fail("仅 HEADINGS 可提供整数 headingLevel（1 到 9）");
        }
        String family = text(n, "fontFamily");
        if (family != null && (family.length() > 100 || family.chars().anyMatch(Character::isISOControl))) {
            fail("字体名称过长或包含控制字符");
        }
        Double size = number(n, "fontSizePoints", 1, 200);
        if (size != null && size * 2 != Math.rint(size * 2)) fail("字号必须为 0.5 磅的整数倍");
        Double line = number(n, "lineSpacing", .5, 5);
        Double before = number(n, "spaceBeforePoints", 0, 200);
        Double after = number(n, "spaceAfterPoints", 0, 200);
        String alignment = alignment(n, Set.of("LEFT", "CENTER", "RIGHT", "JUSTIFY"));
        Boolean bold = bool(n, "bold"), italic = bool(n, "italic");
        if (family == null && size == null && line == null && before == null && after == null
                && alignment == null && bold == null && italic == null) fail("至少指定一项实际格式");
        return new Format(scope, level == null ? null : level.intValue(), family, size, line, before, after,
                alignment, bold, italic, !Boolean.FALSE.equals(bool(n, "includeTables")));
    }

    public static Images images(String content) {
        var n = object(content, Set.of("widthCm", "heightCm", "keepAspectRatio", "alignment"));
        Double width = number(n, "widthCm", .1, 50), height = number(n, "heightCm", .1, 50);
        boolean keep = !Boolean.FALSE.equals(bool(n, "keepAspectRatio"));
        if (width == null && height == null) fail("至少指定图片宽度或高度（厘米）");
        if (!keep && (width == null || height == null)) fail("不保持比例时必须同时指定宽高");
        return new Images(width, height, keep, alignment(n, Set.of("LEFT", "CENTER", "RIGHT")));
    }

    public static String json(Object spec) { return JSON.writeValueAsString(spec); }

    public static int validateContext(DocumentEditPlan.Operation operation, ToolExecutionContext context) {
        if (!"DOCX".equals(context.format()) || context.docxStructure() == null) fail("此工具只支持绑定明确版本的 DOCX");
        Format format = operation.operationType() == DocumentEditPlan.OperationType.NORMALIZE_DOCX_FORMAT
                ? format(operation.content()) : null;
        if (format == null) images(operation.content());
        int count = count(context.docxStructure().blocks(), format);
        if (count == 0) fail(format == null ? "正文没有可调整的已有图片" : "当前版本没有匹配范围的段落；标题需先具有标题样式或大纲级别");
        if (count > (format == null ? MAX_IMAGES : MAX_PARAGRAPHS)) fail("匹配对象数量超过批量处理上限");
        return count;
    }

    private static int count(List<DocxStructureVO.Block> blocks, Format format) {
        int count = 0;
        for (var b : blocks) {
            if (b.blockId() == null || !b.blockId().startsWith("body/")) continue;
            if (format == null && "IMAGE".equals(b.type())) count++;
            if (format != null && ("PARAGRAPH".equals(b.type()) || "HEADING".equals(b.type()))
                    && format.matches(b.headingLevel(), b.blockId().contains("/t"))) count++;
            count += count(b.children(), format);
        }
        return count;
    }

    private static JsonNode object(String content, Set<String> keys) {
        try {
            if (content == null || content.length() > 20000) fail("批量参数为空或超长");
            var n = JSON.readTree(content);
            if (n == null || !n.isObject()) fail("批量参数必须为 JSON 对象");
            for (var entry : n.properties()) if (!keys.contains(entry.getKey())) fail("不支持的批量参数：" + entry.getKey());
            return n;
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(422, "批量参数不是合法 JSON 对象"); }
    }

    private static String text(JsonNode n, String key) {
        var v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isString() || v.asString().isBlank()) { fail(key + " 必须为非空字符串"); return null; }
        return v.asString().trim();
    }

    private static Double number(JsonNode n, String key, double min, double max) {
        var v = n.get(key);
        if (v == null || v.isNull()) return null;
        double value = v.isNumber() ? v.asDouble() : Double.NaN;
        if (!Double.isFinite(value) || value < min || value > max) fail(key + " 必须在 " + min + " 到 " + max + " 之间");
        return value;
    }

    private static Boolean bool(JsonNode n, String key) {
        var v = n.get(key);
        if (v == null || v.isNull()) return null;
        if (!v.isBoolean()) fail(key + " 必须为布尔值");
        return v.asBoolean();
    }

    private static String alignment(JsonNode n, Set<String> allowed) {
        String value = text(n, "alignment");
        if (value != null && !allowed.contains(value)) fail("alignment 仅支持 " + allowed);
        return value;
    }

    private static void fail(String message) { throw new BusinessException(422, message); }
}
