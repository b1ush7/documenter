package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable template file snapshot; editable metadata and enabled status are not versioned. */
@Data
@TableName("doc_template_version")
public class DocTemplateVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Integer versionNo;
    private String storageKey;
    private String originalName;
    private String contentType;
    private String extension;
    private Long sizeBytes;
    private String changeType;
    private Integer restoredFromVersion;
    private LocalDateTime createTime;
}
