package com.documenter.service.impl;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.tea.TeaException;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.OcrEngine;
import com.documenter.util.Digests;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

/** RecognizeAllText 的二进制上传适配器；沿用已有权限、逐页渲染、预处理和人工校正。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "res.ocr", name = "provider", havingValue = "aliyun")
public class AliyunOcrEngine implements OcrEngine {
    private final OcrProperties properties;
    private final StorageProperties storage;
    private final ObjectMapper mapper;
    private final Gateway gateway;

    @Autowired
    public AliyunOcrEngine(OcrProperties properties, StorageProperties storage, ObjectMapper mapper) {
        this(properties, storage, mapper, (config, request, runtime) -> {
            var response = new Client(config).recognizeAllTextWithOptions(request, runtime);
            if (response == null || response.getBody() == null) {
                throw new BusinessException(502, "阿里云 OCR 未返回识别结果");
            }
            return response.getBody().toMap();
        });
    }

    AliyunOcrEngine(OcrProperties properties, StorageProperties storage,
                    ObjectMapper mapper, Gateway gateway) {
        this.properties = properties;
        this.storage = storage;
        this.mapper = mapper;
        this.gateway = gateway;
    }

    @FunctionalInterface
    interface Gateway {
        Map<String, Object> recognize(Config config, RecognizeAllTextRequest request,
                                      RuntimeOptions runtime) throws Exception;
    }

    @Override
    public boolean available() {
        var c = properties.getAliyun();
        return properties.isEnabled() && c != null && text(c.getAccessKeyId())
                && text(c.getAccessKeySecret()) && text(c.getRegionId())
                && c.getEndpoint() != null
                && c.getEndpoint().matches("ocr-api\\.[a-z0-9-]+\\.aliyuncs\\.com")
                && Set.of("Advanced", "General").contains(c.getType())
                && c.getConnectTimeoutMillis() > 0 && c.getReadTimeoutMillis() > 0;
    }

    @Override
    public String name() {
        return "aliyun-" + properties.getAliyun().getType();
    }

    @Override
    public Result recognize(byte[] pngBytes, String language) {
        if (!available()) throw new BusinessException(503, "阿里云 OCR 未启用或配置不完整，请检查 OCR_ENABLED 和阿里云凭据");
        if (language == null || !Set.of("auto", "zh", "en", "zh+en", "en+zh").contains(language)) {
            throw new BusinessException(400, "阿里云通用 OCR 当前支持 auto、zh、en 或 zh+en；由服务自动识别中英文");
        }
        BufferedImage image = validateImage(pngBytes);
        Path cache = cacheFile(pngBytes, language);
        byte[] cached = readCache(cache);
        if (cached != null) {
            try { return AliyunOcrResponseParser.parse(mapper.readTree(cached), image.getWidth(), image.getHeight(), properties); }
            catch (RuntimeException invalid) { log.debug("忽略无效的阿里云 OCR 缓存"); }
        }
        var c = properties.getAliyun();
        Config config = new Config().setAccessKeyId(c.getAccessKeyId())
                .setAccessKeySecret(c.getAccessKeySecret()).setEndpoint(c.getEndpoint())
                .setRegionId(c.getRegionId()).setProtocol("HTTPS");
        if (text(c.getSecurityToken())) config.setSecurityToken(c.getSecurityToken());
        RuntimeOptions runtime = new RuntimeOptions().setAutoretry(false).setMaxAttempts(1)
                .setConnectTimeout(c.getConnectTimeoutMillis()).setReadTimeout(c.getReadTimeoutMillis());
        try (var input = new ByteArrayInputStream(pngBytes)) {
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setType(c.getType()).setBody(input);
            if ("Advanced".equals(c.getType())) {
                request.setOutputCoordinate("points").setOutputOricoord(true)
                        .setAdvancedConfig(new RecognizeAllTextRequest.RecognizeAllTextRequestAdvancedConfig()
                                .setOutputRow(true).setOutputTable(true).setOutputCharInfo(false));
            }
            byte[] response = mapper.writeValueAsBytes(gateway.recognize(config, request, runtime));
            // SDK 已解析 HTTP 响应；这里限制序列化结果及磁盘缓存大小。
            if (response.length > properties.getMaxResponseBytes()) {
                throw new BusinessException(502, "阿里云 OCR 结果超过响应大小限制");
            }
            Result result = AliyunOcrResponseParser.parse(mapper.readTree(response), image.getWidth(), image.getHeight(), properties);
            writeCache(cache, response);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (TeaException e) {
            // 不记录供应商异常文本，其中可能包含请求信息、签名和原文。
            String code = e.getCode() == null ? "" : e.getCode();
            int status = e.getStatusCode() == null ? 0 : e.getStatusCode();
            if (status == 429 || code.startsWith("Throttling")) throw new BusinessException(429, "阿里云 OCR 限流，请稍后重试");
            if (status == 401 || status == 403 || code.contains("AccessKey") || code.contains("Signature")
                    || code.startsWith("Forbidden") || code.contains("Token")) {
                throw new BusinessException(503, "阿里云 OCR 鉴权或授权失败，请检查凭据、RAM 权限和服务开通状态");
            }
            if (status == 400) throw new BusinessException(422, "阿里云 OCR 无法识别该图片，请检查图片及识别模式");
            throw upstreamFailure(e);
        } catch (Exception e) {
            throw upstreamFailure(e);
        }
    }

    private BusinessException upstreamFailure(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new BusinessException(503, "阿里云 OCR 请求已中断");
            }
            if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                return new BusinessException(504, "阿里云 OCR 请求超时");
            }
        }
        return new BusinessException(502, "阿里云 OCR 调用失败，请检查服务状态与网络");
    }

    private BufferedImage validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new BusinessException(400, "OCR 图片不能为空");
        if (bytes.length > Math.min(properties.getMaxImageBytes(), 10L * 1024 * 1024)) {
            throw new BusinessException(413, "阿里云 OCR 单页图片不能超过 10 MiB 或配置上限，请降低 DPI");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(422, "OCR 图片无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 15 || h <= 15 || w >= 8192 || h >= 8192
                        || (double) Math.max(w, h) / Math.min(w, h) >= 50
                        || (long) w * h > storage.getUpload().getMaxImagePixels()) {
                    throw new BusinessException(413, "阿里云 OCR 要求宽高为 16～8191 像素、长宽比小于 50，且不超过本地像素限制；请调整区域或 DPI");
                }
                return reader.read(0);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException invalid) {
            throw new BusinessException(422, "OCR 图片无法解码");
        }
    }

    private Path cacheFile(byte[] image, String language) {
        if (!properties.isCacheEnabled() || !text(properties.getCacheDirectory())) return null;
        Path root = Path.of(storage.getLocal().getTemp()).toAbsolutePath().normalize();
        Path directory = root.resolve(properties.getCacheDirectory()).normalize();
        if (!directory.startsWith(root)) return null;
        var c = properties.getAliyun();
        String scope = "aliyun-ocr-v1|" + c.getEndpoint() + "|" + c.getRegionId() + "|" + c.getType()
                + "|" + language + "|" + Digests.sha256Hex(c.getAccessKeyId() + "|" + c.getAccessKeySecret());
        return directory.resolve("aliyun-" + Digests.sha256Hex(image) + "-" + Digests.sha256Hex(scope) + ".response");
    }

    private byte[] readCache(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(Math.max(0, properties.getMaxResponseBytes()) + 1);
            return bytes.length > properties.getMaxResponseBytes() ? null : bytes;
        } catch (IOException e) { return null; }
    }

    private void writeCache(Path file, byte[] response) {
        if (file == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "aliyun-", ".tmp");
            Files.write(temporary, response);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnavailable) {
                // 某些挂载盘和 Windows 文件系统不支持原子重命名；缓存失败不影响识别。
                Files.write(file, response);
            }
        } catch (IOException e) { log.warn("阿里云 OCR 缓存写入失败, errorType={}", e.getClass().getSimpleName()); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
