package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.service.impl.LocalFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 定期删除隔离临时目录中超过保留时间的普通文件。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "res.storage.local", name = "temp-cleanup-enabled",
        havingValue = "true", matchIfMissing = true)
public class TemporaryFileCleaner {

    private final LocalFileStorage storage;
    private final StorageProperties properties;

    public TemporaryFileCleaner(LocalFileStorage storage, StorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${res.storage.local.temp-cleanup-interval:1h}")
    public void cleanExpiredFiles() {
        Path root = storage.tempDirectory().toAbsolutePath().normalize();
        Duration retention = properties.getLocal().getTempRetention();
        if (retention == null || retention.isNegative() || retention.isZero()) {
            log.warn("临时文件保留时间无效，跳过清理, retention={}", retention);
            return;
        }

        Instant cutoff = Instant.now().minus(retention);
        int deletedFiles = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> candidates = paths
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !path.equals(root) && path.startsWith(root))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : candidates) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    deletedFiles += deleteIfExpired(path, cutoff);
                } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    deleteIfEmpty(path);
                }
            }
        } catch (IOException e) {
            log.warn("扫描临时目录失败, temp={}", root, e);
            return;
        }

        if (deletedFiles > 0) {
            log.info("临时文件清理完成, temp={}, deletedFiles={}", root, deletedFiles);
        }
    }

    private int deleteIfExpired(Path path, Instant cutoff) {
        try {
            Instant lastModified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
            if (lastModified.isBefore(cutoff) && Files.deleteIfExists(path)) {
                return 1;
            }
        } catch (IOException e) {
            log.warn("清理临时文件失败, path={}", path, e);
        }
        return 0;
    }

    private void deleteIfEmpty(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            if (children.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException e) {
            log.debug("临时子目录暂不能删除, path={}", directory, e);
        }
    }
}
