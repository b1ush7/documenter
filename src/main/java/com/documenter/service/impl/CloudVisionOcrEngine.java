package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ModelConfigService;
import com.documenter.service.OcrEngine;
import com.documenter.util.Digests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 复用管理端 OCR 模型配置，通过 OpenAI 兼容视觉接口识别页面。
 *
 * <p>页面模型必须能接收 image_url data URL，并返回包含 text、left、top、width、
 * height、confidence 的严格 JSON。这里不使用供应商私有 SDK，避免把密钥或响应格式
 * 再写一套到配置之外。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "res.ocr", name = "provider", havingValue = "cloud-vision", matchIfMissing = true)
public class CloudVisionOcrEngine implements OcrEngine {

    private static final String OCR_SCOPE = "OCR";
    private final OcrProperties properties;
    private final StorageProperties storageProperties;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public CloudVisionOcrEngine(OcrProperties properties,
                                StorageProperties storageProperties,
                                ModelConfigService modelConfigService,
                                ObjectMapper objectMapper) {
        this(properties, storageProperties, modelConfigService, objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    CloudVisionOcrEngine(OcrProperties properties,
                         StorageProperties storageProperties,
                         ModelConfigService modelConfigService,
                         ObjectMapper objectMapper,
                         HttpClient httpClient) {
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean available() {
        if (!properties.isEnabled()) return false;
        try {
            modelConfigService.requireDefaultRuntimeConfig(OCR_SCOPE);
            return true;
        } catch (BusinessException unavailable) {
            return false;
        }
    }

    @Override
    public String name() {
        return "cloud-vision";
    }

    @Override
    public Result recognize(byte[] pngBytes, String language) {
        if (!properties.isEnabled()) {
            throw new BusinessException(503,
                    "云端 OCR 未启用；请配置默认 OCR 模型并设置 OCR_ENABLED=true");
        }
        if (pngBytes == null || pngBytes.length == 0) {
            throw new BusinessException(400, "OCR 图片不能为空");
        }
        if (pngBytes.length > properties.getMaxImageBytes()) {
            throw new BusinessException(413,
                    "单页 OCR 图片超过云端上传限制，请降低 DPI");
        }
        ModelConfigService.ModelRuntimeConfig config =
                modelConfigService.requireDefaultRuntimeConfig(OCR_SCOPE);
        BufferedImage image = decode(pngBytes);
        byte[] cachedResponse = readCache(pngBytes, language, config);
        if (cachedResponse != null) {
            return parseResponse(cachedResponse, image.getWidth(), image.getHeight(),
                    properties.getLowConfidenceThreshold(), properties.getMaxWords(),
                    properties.getMaxOutputChars(), properties.getMaxTableRows(),
                    properties.getMaxTableColumns(), properties.getMaxTableCells());
        }
        URI endpoint = endpoint(config.baseUrl());
        String payload = requestPayload(pngBytes, language, config);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(1, config.timeoutSeconds())))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new BusinessException(504, "云端 OCR 请求超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "云端 OCR 请求被中断");
        } catch (IOException failure) {
            log.warn("云端 OCR 网络请求失败, model={}", config.modelName(), failure);
            throw new BusinessException(503, "云端 OCR 服务不可用");
        }
        byte[] responseBody;
        try (InputStream body = response.body()) {
            responseBody = readLimited(body, properties.getMaxResponseBytes());
        } catch (IOException invalid) {
            throw new BusinessException(502, "云端 OCR 响应读取失败或超过大小限制");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 429) {
                throw new BusinessException(429, "云端 OCR 请求过于频繁，请稍后重试");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BusinessException(503, "云端 OCR 鉴权失败，请检查 API Key");
            }
            log.warn("云端 OCR 返回失败, status={}, model={}, responseBytes={}",
                    response.statusCode(), config.modelName(), responseBody.length);
            throw new BusinessException(502, "云端 OCR 服务返回失败");
        }

        Result result = parseResponse(responseBody, image.getWidth(), image.getHeight(),
                properties.getLowConfidenceThreshold(), properties.getMaxWords(),
                properties.getMaxOutputChars(), properties.getMaxTableRows(),
                properties.getMaxTableColumns(), properties.getMaxTableCells());
        writeCache(pngBytes, language, config, responseBody);
        return result;
    }

    static Result parseResponse(byte[] responseBody, int imageWidth, int imageHeight,
                                double lowConfidenceThreshold, int maxWords,
                                int maxOutputChars, int maxTableRows,
                                int maxTableColumns, int maxTableCells) {
        try {
            JsonNode root = new tools.jackson.databind.ObjectMapper()
                    .readTree(responseBody);
            String content = messageContent(root.path("choices").path(0)
                    .path("message").path("content"));
            String json = extractJson(content);
            JsonNode contentRoot = new tools.jackson.databind.ObjectMapper()
                    .readTree(json);
            JsonNode linesNode = contentRoot.path("lines");
            if (!linesNode.isArray()) {
                throw new BusinessException(502, "云端 OCR 未返回 lines 数组");
            }
            List<Line> lines = new ArrayList<>();
            int characters = 0;
            boolean truncated = false;
            for (JsonNode item : linesNode) {
                String text = item.path("text").asText("").replace("\u0000", "").trim();
                if (text.isEmpty()) continue;
                if (lines.size() >= maxWords) {
                    truncated = true;
                    break;
                }
                characters += text.codePointCount(0, text.length());
                if (characters > maxOutputChars) {
                    truncated = true;
                    break;
                }
                int left = bounded(item.path("left").asInt(0), 0, imageWidth);
                int top = bounded(item.path("top").asInt(0), 0, imageHeight);
                int width = bounded(item.path("width").asInt(0), 0,
                        Math.max(0, imageWidth - left));
                int height = bounded(item.path("height").asInt(0), 0,
                        Math.max(0, imageHeight - top));
                double confidence = item.path("confidence").asDouble(0);
                if (confidence > 0 && confidence <= 1) confidence *= 100;
                confidence = Math.max(0, Math.min(100,
                        Math.round(confidence * 100d) / 100d));
                lines.add(new Line(text, left, top, width, height, confidence,
                        confidence < lowConfidenceThreshold));
            }
            lines.sort(Comparator.comparingInt(Line::top).thenComparingInt(Line::left));
            List<Table> tables = parseTables(contentRoot.path("tables"),
                    imageWidth, imageHeight, lowConfidenceThreshold,
                    maxTableRows, maxTableColumns, maxTableCells);
            return new Result(lines, tables, truncated);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException invalid) {
            throw new BusinessException(502, "云端 OCR 返回格式不合法");
        }
    }

    private static List<Table> parseTables(JsonNode tablesNode, int imageWidth,
                                           int imageHeight, double reviewThreshold,
                                           int maxRows, int maxColumns,
                                           int maxCells) {
        if (tablesNode == null || tablesNode.isMissingNode() || tablesNode.isNull()) {
            return List.of();
        }
        if (!tablesNode.isArray()) {
            throw new BusinessException(502, "云端 OCR tables 必须是数组");
        }
        List<Table> tables = new ArrayList<>();
        int totalCells = 0;
        for (JsonNode tableNode : tablesNode) {
            int rows = tableNode.path("rows").asInt(0);
            int columns = tableNode.path("columns").asInt(0);
            if (rows < 1 || rows > maxRows || columns < 1 || columns > maxColumns) {
                throw new BusinessException(422, "云端 OCR 表格行列数超出允许范围");
            }
            JsonNode cellsNode = tableNode.path("cells");
            if (!cellsNode.isArray()) {
                throw new BusinessException(502, "云端 OCR 表格缺少 cells 数组");
            }
            totalCells += cellsNode.size();
            if (totalCells > maxCells) {
                throw new BusinessException(413, "云端 OCR 表格单元格总数超过限制");
            }
            boolean[][] covered = new boolean[rows][columns];
            List<Cell> cells = new ArrayList<>();
            double confidenceSum = 0;
            boolean needsReview = false;
            for (JsonNode cellNode : cellsNode) {
                int row = cellNode.path("row").asInt(-1);
                int column = cellNode.path("column").asInt(-1);
                int rowSpan = cellNode.path("rowSpan").asInt(1);
                int columnSpan = cellNode.path("columnSpan").asInt(1);
                if (row < 0 || column < 0 || rowSpan < 1 || columnSpan < 1
                        || row + rowSpan > rows || column + columnSpan > columns) {
                    throw new BusinessException(502, "云端 OCR 表格单元格跨度超出范围");
                }
                for (int rowIndex = row; rowIndex < row + rowSpan; rowIndex++) {
                    for (int columnIndex = column;
                         columnIndex < column + columnSpan; columnIndex++) {
                        if (covered[rowIndex][columnIndex]) {
                            throw new BusinessException(502, "云端 OCR 表格单元格区域重叠");
                        }
                        covered[rowIndex][columnIndex] = true;
                    }
                }
                int left = bounded(cellNode.path("left").asInt(0), 0, imageWidth);
                int top = bounded(cellNode.path("top").asInt(0), 0, imageHeight);
                int width = bounded(cellNode.path("width").asInt(0), 0,
                        Math.max(0, imageWidth - left));
                int height = bounded(cellNode.path("height").asInt(0), 0,
                        Math.max(0, imageHeight - top));
                double confidence = normalizeConfidence(
                        cellNode.path("confidence").asDouble(0));
                boolean cellNeedsReview = confidence < reviewThreshold;
                needsReview |= cellNeedsReview;
                confidenceSum += confidence;
                cells.add(new Cell(row, column, rowSpan, columnSpan,
                        cellNode.path("text").asText("").replace("\u0000", "").trim(),
                        left, top, width, height, confidence, cellNeedsReview));
            }
            for (boolean[] row : covered) {
                for (boolean value : row) {
                    if (!value) {
                        throw new BusinessException(502,
                                "云端 OCR 表格存在未覆盖单元格，无法验证结构");
                    }
                }
            }
            cells.sort(Comparator.comparingInt(Cell::row)
                    .thenComparingInt(Cell::column));
            tables.add(new Table(rows, columns,
                    cells.isEmpty() ? 0 : Math.round(
                            confidenceSum / cells.size() * 100d) / 100d,
                    needsReview, cells));
        }
        return List.copyOf(tables);
    }

    private static double normalizeConfidence(double confidence) {
        if (confidence > 0 && confidence <= 1) confidence *= 100;
        return Math.max(0, Math.min(100, Math.round(confidence * 100d) / 100d));
    }

    private String requestPayload(byte[] pngBytes, String language,
                                  ModelConfigService.ModelRuntimeConfig config) {
        String dataUrl = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(pngBytes);
        Map<String, Object> image = Map.of("url", dataUrl);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(
                Map.of("type", "text", "text", prompt(language)),
                Map.of("type", "image_url", "image_url", image)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.modelName());
        payload.put("messages", List.of(message));
        payload.put("temperature", config.temperature() == null
                ? java.math.BigDecimal.ZERO : config.temperature());
        if (config.maxOutputTokens() != null && config.maxOutputTokens() > 0) {
            payload.put("max_tokens", config.maxOutputTokens());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (tools.jackson.core.JacksonException serializationFailure) {
            throw new BusinessException(500, "云端 OCR 请求构造失败");
        }
    }

    private static String prompt(String language) {
        return """
                识别图片中的所有文字，保持从上到下、从左到右的阅读顺序。
                目标语言：%s。只返回严格 JSON，不要 Markdown 或解释：
                {"lines":[{"text":"原始文字","left":0,"top":0,"width":0,"height":0,"confidence":95}],
                 "tables":[{"rows":2,"columns":2,"cells":[
                   {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"表头",
                    "left":0,"top":0,"width":100,"height":30,"confidence":95}
                 ]}]}
                left/top/width/height 使用输入图片像素坐标；confidence 使用 0 到 100。
                每个可见文字行一个对象，不翻译、不总结、不补写看不清的内容。
                只有能明确识别完整矩形表格时才返回 tables；没有表格或无法验证时返回空数组。
                cells 必须完整覆盖 rows×columns，合并单元格用 rowSpan/columnSpan，不能重叠。
                """.formatted(language);
    }

    private URI endpoint(String baseUrl) {
        String endpoint = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint += "/chat/completions";
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported endpoint");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(503,
                    "默认 OCR 模型 baseUrl 必须是有效的 HTTPS 接口地址");
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        byte[] body = input.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new IOException("response too large");
        }
        return body;
    }

    private static String messageContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            throw new BusinessException(502, "云端 OCR 响应缺少 message.content");
        }
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) {
            throw new BusinessException(502, "云端 OCR message.content 格式不支持");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String value = part.isTextual() ? part.asText() : part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    private static String extractJson(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                value = value.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int firstObject = value.indexOf('{');
        int lastObject = value.lastIndexOf('}');
        if (firstObject < 0 || lastObject < firstObject) {
            throw new BusinessException(502, "云端 OCR 未返回 JSON 对象");
        }
        return value.substring(firstObject, lastObject + 1);
    }

    private static BufferedImage decode(byte[] png) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new BusinessException(422, "OCR 页面不是有效 PNG");
            return image;
        } catch (IOException invalid) {
            throw new BusinessException(422, "OCR 页面无法解析");
        }
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private byte[] readCache(byte[] pngBytes, String language,
                             ModelConfigService.ModelRuntimeConfig config) {
        if (!properties.isCacheEnabled()) return null;
        Path file = cacheFile(pngBytes, language, config);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return Files.readAllBytes(file);
        } catch (IOException invalid) {
            log.debug("忽略无法读取的云端 OCR 缓存, file={}", file);
            return null;
        }
    }

    private void writeCache(byte[] pngBytes, String language,
                            ModelConfigService.ModelRuntimeConfig config,
                            byte[] responseBody) {
        if (!properties.isCacheEnabled()) return;
        Path file = cacheFile(pngBytes, language, config);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "ocr-", ".response");
            Files.write(temporary, responseBody);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.write(file, responseBody);
                Files.deleteIfExists(temporary);
            }
        } catch (IOException cacheFailure) {
            log.warn("云端 OCR 缓存写入失败, file={}", file, cacheFailure);
        }
    }

    private Path cacheFile(byte[] pngBytes, String language,
                           ModelConfigService.ModelRuntimeConfig config) {
        String directory = properties.getCacheDirectory();
        if (directory == null || directory.isBlank()) return null;
        String keyFingerprint = config.baseUrl() + "|" + config.modelName() + "|"
                + config.code() + "|" + language + "|"
                + Digests.sha256Hex(config.apiKey().getBytes(StandardCharsets.UTF_8))
                .substring(0, 12) + "|cloud-ocr-v1";
        String key = Digests.sha256Hex(pngBytes) + "-"
                + Digests.sha256Hex(keyFingerprint.getBytes(StandardCharsets.UTF_8))
                .substring(0, 16) + ".response";
        return Path.of(storageProperties.getLocal().getTemp())
                .toAbsolutePath().normalize().resolve(directory).resolve(key);
    }
}
