package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档版本（TODO 3.3「保留原文件；编辑产物新增版本，记录来源版本、操作者、指令和结果」）。
 *
 * <p>版本号从 1 开始，1 为原始上传；{@code parentVersion} 形成版本链，
 * 用于追溯「这个版本是从哪个版本改出来的」。不做「覆盖当前版本」的操作，
 * 每次变更都新增一条记录。
 */
@TableName("document_version")
@Data
public class DocumentVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Long userId;
    private Integer versionNo;
    /** 来源版本号；原始版本为 null。 */
    private Integer parentVersion;
    private String storageKey;
    private Long sizeBytes;
    private String sha256;
    /** 产生该版本的自然语言指令；上传版本为 null。 */
    private String instruction;
    private String resultSummary;
    private String changeType;
    private LocalDateTime createTime;
}
