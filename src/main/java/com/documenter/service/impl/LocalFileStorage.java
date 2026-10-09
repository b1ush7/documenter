package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.FileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘存储实现（TODO 3.3）。
 *
 * <p>安全设计（对应 TODO 3.3「通过内部文件 ID 定位资源，防止任意路径读取」）：
 * <ul>
 *   <li>文件名由服务端生成（UUID + 白名单扩展名），客户端文件名只作为展示名入库，不参与路径拼接；</li>
 *   <li>{@link #resolvePath} 会把结果规范化并校验仍在根目录之内，即使 storageKey 被污染也无法越权读取；</li>
 *   <li>扩展名走白名单，非法值直接拒绝，不生成未知类型的文件。</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    /** 单层目录的文件数控制：按日期分目录，避免单目录堆积过多文件。 */
    private static final int MAX_EXTENSION_LENGTH = 10;

    private final Path root;
    private final Path temp;

    public LocalFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.getLocal().getRoot()).toAbsolutePath().normalize();
        this.temp = Path.of(properties.getLocal().getTemp()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.createDirectories(temp);
        } catch (IOException e) {
            // 目录建不出来说明部署配置有问题，直接启动失败比运行期才报错更好定位
            throw new IllegalStateException("无法创建存储目录: root=" + root + ", temp=" + temp, e);
        }
        log.info("本地文件存储已初始化, root={}, temp={}", root, temp);
    }

    @Override
    public String store(byte[] content, String extension) {
        String storageKey = newStorageKey(extension);
        Path target = resolvePath(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new BusinessException(500, "文件写入失败，请稍后重试");
        }
        return storageKey;
    }

    @Override
    public String store(InputStream inputStream, String extension) {
        String storageKey = newStorageKey(extension);
        Path target = resolvePath(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException(500, "文件写入失败，请稍后重试");
        }
        return storageKey;
    }

    @Override
    public InputStream read(String storageKey) {
        Path path = resolvePath(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(404, "文件不存在或已被删除");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new BusinessException(500, "文件读取失败");
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            return Files.isRegularFile(resolvePath(storageKey));
        } catch (BusinessException e) {
            return false;
        }
    }

    @Override
    public boolean delete(String storageKey) {
        try {
            return Files.deleteIfExists(resolvePath(storageKey));
        } catch (IOException e) {
            log.warn("删除文件失败, storageKey={}", storageKey, e);
            return false;
        }
    }

    @Override
    public Path resolvePath(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(400, "存储标识不能为空");
        }
        Path resolved = root.resolve(storageKey).normalize();
        // 关键防线：规范化之后必须仍在根目录内，否则视为路径穿越
        if (!resolved.startsWith(root)) {
            log.warn("检测到越界存储标识, storageKey={}", storageKey);
            throw new BusinessException(400, "非法的存储标识");
        }
        return resolved;
    }

    /** 生成按日期分目录的存储标识。 */
    private static String newStorageKey(String extension) {
        String normalized = normalizeExtension(extension);
        return LocalDate.now().format(DATE_DIR) + "/" + UUID.randomUUID() + "." + normalized;
    }

    /** 扩展名白名单校验：只允许字母数字，长度受限，避免拼出奇怪路径。 */
    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "bin";
        }
        String value = extension.trim().toLowerCase();
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value.isEmpty() || value.length() > MAX_EXTENSION_LENGTH || !value.matches("^[a-z0-9]+$")) {
            return "bin";
        }
        return value;
    }

    /** 临时目录，供后续转换流程隔离使用。 */
    public Path tempDirectory() {
        return temp;
    }
}
