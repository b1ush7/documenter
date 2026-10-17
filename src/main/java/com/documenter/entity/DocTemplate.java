package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公共模板（TODO 3.2「管理公共模板」）。
 *
 * <p>{@code storageKey} 属于服务端内部信息，不通过接口返回；
 * {@code variables} 是 JSON 字符串，描述模板可替换的变量。
 */
@TableName("doc_template")
@Data
public class DocTemplate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String category;
    private String description;
    /** 模板文件存储标识；禁止出现在出参中。 */
    private String storageKey;
    private String originalName;
    private String contentType;
    private String extension;
    private Long sizeBytes;
    /** 内容版本号，每次替换或恢复文件 +1，元数据修改不递增。 */
    private Integer versionNo;
    /** 变量定义 JSON 数组字符串。 */
    private String variables;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否启用（仅业务判断，不入库）。 */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
