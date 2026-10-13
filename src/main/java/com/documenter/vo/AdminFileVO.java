package com.documenter.vo;

import com.documenter.entity.FileAsset;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理员文件出参；包含所有者，但不暴露底层存储标识。 */
@Data
public class AdminFileVO {

    private Long id;
    private Long userId;
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
    private LocalDateTime deleteTime;

    public static AdminFileVO from(FileAsset asset) {
        AdminFileVO vo = new AdminFileVO();
        vo.setId(asset.getId());
        vo.setUserId(asset.getUserId());
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
        vo.setDeleteTime(asset.getDeleteTime());
        return vo;
    }
}
