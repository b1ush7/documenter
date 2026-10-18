package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.StorageRecycleMapper;
import com.documenter.vo.StorageRecycleReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 本地正式存储回收；不递归删除目录，不遍历链接，不删除仍被任何活跃版本或模板引用的 blob。 */
@Slf4j
@Service
public class StorageRecycleService {
    private static final Pattern MANAGED_KEY = Pattern.compile(
            "\\d{4}/\\d{2}/\\d{2}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-z0-9]{1,10}");
    private final LocalFileStorage storage;
    private final StorageProperties properties;
    private final StorageRecycleMapper mapper;

    public StorageRecycleService(LocalFileStorage storage, StorageProperties properties, StorageRecycleMapper mapper) {
        this.storage = storage;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${res.storage.recycle.interval:24h}")
    public void scheduledRecycle() {
        if (!properties.getRecycle().isEnabled()) return;
        try {
            var report = recycle(properties.getRecycle().isDryRun());
            log.info("正式存储回收完成, dryRun={}, expiredFiles={}, deletedBlobs={}, deletedBytes={}",
                    report.dryRun(), report.expiredFiles(), report.deletedBlobs(), report.deletedBytes());
        } catch (Exception failure) {
            log.warn("正式存储回收失败，本轮停止", failure);
        }
    }

    public StorageRecycleReport recycle(boolean dryRun) {
        var policy = properties.getRecycle();
        validate(policy);
        if (!dryRun && (!policy.isEnabled() || policy.isDryRun())) {
            throw new BusinessException(409, "正式回收未启用或仍处于预演模式，请先检查存储保留配置");
        }
        Path root = Path.of(properties.getLocal().getRoot()).toAbsolutePath().normalize();
        Path temp = storage.tempDirectory().toAbsolutePath().normalize();
        try {
            Path realTemp = temp.toRealPath();
            if (!root.equals(root.toRealPath()) || root.startsWith(realTemp) || realTemp.startsWith(root)) {
                throw new BusinessException(409, "回收要求正式目录无符号链接，且与临时目录互相隔离");
            }
            // 文件锁跟随共享存储，多实例及同进程的手工/定时任务不会同时回收。
            try (var channel = FileChannel.open(root.resolve(".recycle.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
                if (lock == null) throw new BusinessException(409, "另一个存储回收任务正在运行");
                return collect(root, policy, dryRun);
            }
        } catch (OverlappingFileLockException busy) {
            throw new BusinessException(409, "另一个存储回收任务正在运行");
        } catch (IOException failure) {
            log.warn("正式存储回收 IO 失败", failure);
            throw new BusinessException(500, "正式存储回收失败，请检查存储权限和服务端日志");
        }
    }

    private StorageRecycleReport collect(Path root, StorageProperties.Recycle policy, boolean dryRun) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(policy.getDeletedRetention());
        List<Long> expired = mapper.eligibleFiles(cutoff, policy.getBatchSize());
        if (expired == null) throw new IllegalStateException("回收候选查询没有返回结果");
        int expiredCount = dryRun ? expired.size() : expired.isEmpty() ? 0 : mapper.markPurged(expired, cutoff, now);
        List<Long> ignored = dryRun ? expired : List.of();
        Instant blobCutoff = Instant.now().minus(policy.getOrphanGrace());
        List<StorageRecycleReport.Entry> entries = new ArrayList<>();
        long scanned = 0, deletedBytes = 0;
        int deleted = 0;
        boolean truncated = expired.size() == policy.getBatchSize();
        // walk 不跟随符号链接，最大深度与服务生成的 yyyy/MM/dd/uuid.ext 一致；流式扫描不把全盘装入内存。
        try (var paths = Files.walk(root, 4)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String key = root.relativize(path).toString().replace('\\', '/');
                if (!MANAGED_KEY.matcher(key).matches() || !safeRegularFile(root, path)) continue;
                scanned++;
                BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.lastModifiedTime().toInstant().isBefore(blobCutoff) || referenced(key, ignored)
                        || beingWritten(path)) continue;
                if (entries.size() >= policy.getBatchSize()) { truncated = true; break; }
                if (dryRun) {
                    entries.add(new StorageRecycleReport.Entry(key, before.size(), "WOULD_DELETE"));
                    continue;
                }
                // 删除前重新确认文件身份、修改时间和数据库引用；数据库失败直接终止，绝不当成无引用。
                if (!safeRegularFile(root, path) || referenced(key, List.of())) continue;
                BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()
                        || !java.util.Objects.equals(before.fileKey(), after.fileKey())) continue;
                try {
                    if (Files.deleteIfExists(path)) {
                        deleted++;
                        deletedBytes += before.size();
                        entries.add(new StorageRecycleReport.Entry(key, before.size(), "DELETED"));
                        log.info("正式存储 blob 已回收, storageKey={}, size={}", key, before.size());
                    }
                } catch (IOException failure) {
                    entries.add(new StorageRecycleReport.Entry(key, before.size(), "FAILED"));
                    log.warn("回收文件失败，下次重试, storageKey={}", key, failure);
                }
            }
        }
        return new StorageRecycleReport(dryRun, expiredCount, scanned, deleted, deletedBytes, truncated, List.copyOf(entries));
    }

    private boolean referenced(String key, List<Long> ignored) {
        Integer result = mapper.protectedReference(key, ignored);
        if (result == null) throw new IllegalStateException("回收引用校验没有返回结果");
        return result != 0;
    }

    private static boolean beingWritten(Path path) throws IOException {
        // 发布后的 blob 不再修改；生产者从创建到写完一直持有排他锁。
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            return lock == null;
        } catch (OverlappingFileLockException busy) {
            return true;
        }
    }

    private static boolean safeRegularFile(Path root, Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        return path.toRealPath().equals(path.toAbsolutePath().normalize());
    }

    private static void validate(StorageProperties.Recycle policy) {
        if (policy == null || !positive(policy.getDeletedRetention()) || !positive(policy.getOrphanGrace())
                || policy.getOrphanGrace().compareTo(Duration.ofHours(1)) < 0
                || policy.getBatchSize() < 1 || policy.getBatchSize() > 1000) {
            throw new BusinessException(400, "回收配置无效：保留时间必须为正，孤儿宽限至少 1 小时，批量大小 1～1000");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
