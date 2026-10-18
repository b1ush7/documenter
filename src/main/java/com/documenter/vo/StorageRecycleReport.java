package com.documenter.vo;

import java.util.List;

/** 仅管理员可读取 storageKey；结果仅包含本批次，truncated 表示需再次运行。 */
public record StorageRecycleReport(boolean dryRun, int expiredFiles, long scannedFiles,
                                   int deletedBlobs, long deletedBytes, boolean truncated,
                                   List<Entry> entries) {
    public record Entry(String storageKey, long sizeBytes, String outcome) {}
}
