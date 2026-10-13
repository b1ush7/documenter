package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.FileStorage;
import com.documenter.util.FileTypeDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 上传文件落盘组件（在文件上传与模板上传之间复用）。
 *
 * <p>为什么抽出来：文件上传与模板上传都需要同一套「大小限制 → 按内容识别真实类型 →
 * 结构安全检查 → 边写盘边算摘要」流程。如果模板上传自己写一遍，
 * 很容易漏掉其中一步（尤其是结构检查），从而成为一个绕过校验的后门。
 * 集中到一处后，两条路径的安全性由同一段代码保证。
 *
 * <p>调用方负责数据库事务与清理：本组件只负责落盘，不写任何业务记录。
 */
@Slf4j
@Component
public class StorageWriter {

    private final FileStorage fileStorage;
    private final StorageProperties properties;
    private final UploadContentInspector uploadContentInspector;

    public StorageWriter(FileStorage fileStorage, StorageProperties properties,
                         UploadContentInspector uploadContentInspector) {
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.uploadContentInspector = uploadContentInspector;
    }

    /**
     * 校验并保存上传文件。
     *
     * @param file 上传内容
     * @return 存储结果：存储标识、识别出的类型、大小与摘要
     * @throws BusinessException 大小超限、类型不支持或结构检查不通过
     */
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (file.getSize() > maxSize) {
            throw new BusinessException(413, "文件超过大小限制（最大 " + (maxSize / 1024 / 1024) + "MB）");
        }

        // 按完整内容识别真实类型：只看内容，不信任客户端声明的 Content-Type 与扩展名
        FileTypeDetector.DetectedType detected;
        try (InputStream probe = new BufferedInputStream(file.getInputStream())) {
            detected = FileTypeDetector.detect(probe);
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败，请重试");
        }
        if (!detected.supported()) {
            log.info("拒绝不支持的上传类型, 声明文件名={}, 原因={}",
                    FileTypeDetector.sanitizeDisplayName(file.getOriginalFilename()), detected.message());
            throw new BusinessException(415, detected.message());
        }

        // 写入正式存储前完成结构与资源规模检查（PDF 页数、图片像素、压缩包规模等）
        uploadContentInspector.inspect(file, detected);

        String storageKey;
        byte[] digest;
        try {
            // 边写盘边算摘要：一次遍历同时完成落盘与 sha256，避免大文件读两遍
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(file.getInputStream());
                 DigestInputStream digestInput = new DigestInputStream(input, sha256)) {
                storageKey = fileStorage.store(digestInput, detected.extension());
            }
            digest = sha256.digest();
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败，请稍后重试");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }

        return new StoredFile(storageKey,
                FileTypeDetector.sanitizeDisplayName(file.getOriginalFilename()),
                detected.contentType(),
                detected.extension(),
                file.getSize(),
                HexFormat.of().formatHex(digest));
    }

    /** 落盘结果。 */
    public record StoredFile(String storageKey, String originalName, String contentType,
                             String extension, long sizeBytes, String sha256) {
    }
}
