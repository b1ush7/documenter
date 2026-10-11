package com.documenter.vo;

import com.documenter.entity.FileAsset;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件出参（TODO 3.3）。
 *
 * <p>刻意不包含 {@code storageKey}：存储标识属于服务端内部信息，
 * 暴露出去等于把存储布局泄露给客户端（TODO 3.3 要求通过内部文件 ID 定位资源）。
 */
@Data
public class FileAssetVO {

    private Long id;
    private String originalName;
    private String displayName;
    private String contentType;
    private String extension;
    private Long sizeBytes;
    private String sha256;
    private String source;
    private String status;
    private Integer latestVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static FileAssetVO from(FileAsset asset) {
        FileAssetVO vo = new FileAssetVO();
        vo.setId(asset.getId());
        vo.setOriginalName(asset.getOriginalName());
        vo.setDisplayName(asset.getDisplayName());
        vo.setContentType(asset.getContentType());
        vo.setExtension(asset.getExtension());
        vo.setSizeBytes(asset.getSizeBytes());
        vo.setSha256(asset.getSha256());
        vo.setSource(asset.getSource());
        vo.setStatus(asset.getStatus());
        vo.setLatestVersion(asset.getLatestVersion());
        vo.setCreateTime(asset.getCreateTime());
        vo.setUpdateTime(asset.getUpdateTime());
        return vo;
    }
}
