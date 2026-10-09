package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件元数据（TODO 3.3），字段与 V1__baseline.sql 的 file_asset 对齐。
 *
 * <p>{@code storageKey} 属于服务端内部信息，绝不通过接口返回，因此由 VO 承担出口职责。
 */
@TableName("file_asset")
@Data
public class FileAsset implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所有者。归属校验一律以此字段为准，不信任前端传入的用户 ID。 */
    private Long userId;
    private String originalName;
    private String displayName;
    private String storageKey;
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
}
