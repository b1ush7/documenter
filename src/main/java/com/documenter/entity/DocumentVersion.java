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
    /** 此版本的真实格式，独立于文件当前版本。 */
    private String contentType;
    private String extension;
    private Long sizeBytes;
    private String sha256;
    /** 产生该版本的自然语言指令；上传版本为 null。 */
    private String instruction;
    private String resultSummary;
    private String changeType;
    /**
     * 产出该版本的任务 ID；非任务产出（上传、手工编辑）为 null。
     *
     * <p>与 {@link #attemptNo} 一起构成唯一键 {@code uk_document_version_task_attempt}，
     * 用于保证「同一任务的同一次尝试最多产出一个版本」——这是失败重试
     * 不重复生成版本的最终保证（TODO 3.4）。
     */
    private Long taskId;
    /**
     * 任务内的执行尝试序号，从 1 开始；非任务产出为 null。
     *
     * <p>自动重试沿用同一任务和尝试序号，避免失败重入重复产出；
     * 用户显式重试会创建新的任务 ID，因此仍可产生新的处理版本。
     */
    private Integer attemptNo;
    private LocalDateTime createTime;
}
