package com.documenter.vo;

import com.documenter.entity.DocumentVersion;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档版本出参（TODO 3.3）。
 *
 * <p>不包含 {@code storageKey}：存储标识属于服务端内部信息。
 */
@Data
public class VersionVO {

    private Long id;
    private Long fileId;
    private Integer versionNo;
    private Integer parentVersion;
    private Long sizeBytes;
    private String sha256;
    private String instruction;
    private String resultSummary;
    private String changeType;
    private LocalDateTime createTime;

    public static VersionVO from(DocumentVersion version) {
        VersionVO vo = new VersionVO();
        vo.setId(version.getId());
        vo.setFileId(version.getFileId());
        vo.setVersionNo(version.getVersionNo());
        vo.setParentVersion(version.getParentVersion());
        vo.setSizeBytes(version.getSizeBytes());
        vo.setSha256(version.getSha256());
        vo.setInstruction(version.getInstruction());
        vo.setResultSummary(version.getResultSummary());
        vo.setChangeType(version.getChangeType());
        vo.setCreateTime(version.getCreateTime());
        return vo;
    }
}
