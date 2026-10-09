package com.documenter.component;

import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传时建立初始版本（TODO 3.3「保留原文件；编辑产物新增版本」）。
 *
 * <p>为什么单独抽成一个组件而不是放进 DocumentVersionServiceImpl：
 * 后者需要 FileService 做归属校验，而 FileService 上传完成后又要建初始版本，
 * 两者直接依赖会形成环。这里的操作只涉及两个 Mapper 且调用方已持有文件实体，
 * 不依赖任何 Service，因此可以安全地被上传流程调用。
 *
 * <p>版本号固定为 1：file_asset.latest_version 初始为 0，
 * 「0」表示尚未建立版本记录，而不是一个可下载的版本。
 */
@Slf4j
@Component
public class InitialVersionWriter {

    private final DocumentVersionMapper versionMapper;
    private final FileAssetMapper fileAssetMapper;

    public InitialVersionWriter(DocumentVersionMapper versionMapper, FileAssetMapper fileAssetMapper) {
        this.versionMapper = versionMapper;
        this.fileAssetMapper = fileAssetMapper;
    }

    /**
     * 为刚上传的文件建立版本 1。
     *
     * <p>与上传记录在同一个事务内：如果版本记录写失败，整个上传回滚，
     * 不会出现「有文件但没有版本」的半成品状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersion createInitialVersion(FileAsset asset, Long userId) {
        DocumentVersion version = new DocumentVersion();
        version.setFileId(asset.getId());
        version.setUserId(userId);
        version.setVersionNo(1);
        // 原始上传没有来源版本
        version.setParentVersion(null);
        version.setStorageKey(asset.getStorageKey());
        version.setSizeBytes(asset.getSizeBytes());
        version.setSha256(asset.getSha256());
        version.setInstruction(null);
        version.setResultSummary("原始上传");
        version.setChangeType(ChangeType.UPLOAD.name());
        versionMapper.insert(version);

        // latest_version 同步为 1，与刚写入的版本记录保持一致
        FileAsset update = new FileAsset();
        update.setId(asset.getId());
        update.setLatestVersion(1);
        fileAssetMapper.updateById(update);

        log.info("已建立初始版本, fileId={}, versionNo=1", asset.getId());
        return version;
    }
}
