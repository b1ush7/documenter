package com.documenter.vo;

import com.documenter.entity.DocTemplateVersion;

import java.time.LocalDateTime;

/** Only public content metadata; storage keys and template variables are deliberately absent. */
public record DocTemplateVersionVO(Long templateId, Integer versionNo, String originalName,
                                   String contentType, String extension, Long sizeBytes,
                                   String changeType, Integer restoredFromVersion, LocalDateTime createTime) {
    public static DocTemplateVersionVO from(DocTemplateVersion version) {
        return new DocTemplateVersionVO(version.getTemplateId(), version.getVersionNo(), version.getOriginalName(),
                version.getContentType(), version.getExtension(), version.getSizeBytes(), version.getChangeType(),
                version.getRestoredFromVersion(), version.getCreateTime());
    }
}
