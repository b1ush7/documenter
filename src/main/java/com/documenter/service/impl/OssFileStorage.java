package com.documenter.service.impl;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 正式内容存入私有 OSS Bucket；数据库只存相对 storageKey，下载继续经过业务权限校验。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "res.storage", name = "type", havingValue = "oss")
public class OssFileStorage implements FileStorage {
    private static final Pattern KEY = Pattern.compile(
            "\\d{4}/\\d{2}/\\d{2}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-z0-9]{1,10}");
    private final OSSClient client;
    private final String bucket;
    private final String prefix;
    private final Path temp;

    public OssFileStorage(StorageProperties properties, OSSClient client) {
        this.client = client;
        this.bucket = properties.getOss().getBucket();
        String configuredPrefix = properties.getOss().getPrefix();
        configuredPrefix = configuredPrefix == null ? "" : configuredPrefix.trim().replaceAll("^/+|/+$", "");
        if (!configuredPrefix.isEmpty() && !configuredPrefix.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")) {
            throw new IllegalStateException("OSS_PREFIX 只能包含字母、数字、下划线、短横线和目录分隔符");
        }
        this.prefix = configuredPrefix.isEmpty() ? "" : configuredPrefix + "/";
        this.temp = Path.of(properties.getLocal().getTemp()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(temp);
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建 OSS 上传及转换临时目录，请设置可写的 STORAGE_LOCAL_TEMP", failure);
        }
        log.info("OSS 文件存储已初始化, bucket={}, prefix={}, temp={}", bucket, prefix, temp);
    }

    @Override
    public String store(byte[] content, String extension) {
        return store(new ByteArrayInputStream(content), extension);
    }

    @Override
    public String store(InputStream inputStream, String extension) {
        String normalized = extension == null ? "bin" : extension.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        if (!normalized.matches("[a-z0-9]{1,10}")) normalized = "bin";
        String storageKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID() + "." + normalized;
        Path staged = null;
        try {
            // 只读取调用方流一次，避免 SDK 重试导致 DigestInputStream 重复累计摘要。
            // 上传使用文件而非把整个文件复制到堆内存，完成后删除临时副本。
            staged = Files.createTempFile(temp, "oss-upload-", ".tmp");
            Files.copy(inputStream, staged, StandardCopyOption.REPLACE_EXISTING);
            client.putObjectFromFile(PutObjectRequest.newBuilder().bucket(bucket).key(objectKey(storageKey))
                    .forbidOverwrite(true).build(), staged);
            return storageKey;
        } catch (IOException | RuntimeException failure) {
            throw unavailable("上传", failure);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException failure) {
                    log.warn("OSS 上传临时副本暂不能删除，将由临时清理任务重试");
                }
            }
        }
    }

    @Override
    public InputStream read(String storageKey) {
        String key = objectKey(storageKey);
        try {
            // 调用方关闭 body 就会释放响应连接；不返回公开链接或预签名地址。
            return client.getObject(GetObjectRequest.newBuilder().bucket(bucket).key(key).build()).body();
        } catch (RuntimeException failure) {
            var error = ServiceException.asCause(failure);
            if (error != null && "NoSuchKey".equals(error.errorCode())) {
                throw new BusinessException(404, "文件不存在或已被删除");
            }
            throw unavailable("读取", failure);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) return false;
        try {
            return client.doesObjectExist(bucket, objectKey(storageKey));
        } catch (RuntimeException failure) {
            // 权限、网络和 Bucket 配置错误不伪装成文件丢失。
            throw unavailable("检查", failure);
        }
    }

    @Override
    public boolean delete(String storageKey) {
        String key = objectKey(storageKey);
        try {
            if (!client.doesObjectExist(bucket, key)) return false;
            client.deleteObject(DeleteObjectRequest.newBuilder().bucket(bucket).key(key).build());
            return true;
        } catch (RuntimeException failure) {
            // 与本地实现一致：回滚清理失败不掩盖原始事务错误。
            logFailure("删除", failure);
            return false;
        }
    }

    @Override
    public Path resolvePath(String storageKey) {
        throw new UnsupportedOperationException("OSS 正式文件没有本地路径，请通过 read 读取");
    }

    @Override
    public Path tempDirectory() {
        return temp;
    }

    private String objectKey(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) {
            throw new BusinessException(400, "非法的存储标识");
        }
        return prefix + storageKey;
    }

    private BusinessException unavailable(String operation, Exception failure) {
        logFailure(operation, failure);
        return new BusinessException(503, "OSS 文件" + operation + "失败，请检查存储配置或稍后重试");
    }

    private void logFailure(String operation, Exception failure) {
        var error = ServiceException.asCause(failure);
        // 不输出 SDK 原始异常，避免签名、访问凭据或请求 URL 混入日志。
        log.warn("OSS 文件{}失败, errorCode={}, requestId={}, failureType={}", operation,
                error == null ? "CLIENT_ERROR" : error.errorCode(),
                error == null ? "" : error.requestId(), failure.getClass().getSimpleName());
    }
}
