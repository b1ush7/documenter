package com.documenter.vo;

import com.documenter.entity.DocTemplate;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公共模板出参（TODO 3.2）。
 *
 * <p>刻意不含 {@code storageKey}：存储标识属于服务端内部信息，
 * 暴露出去等于把存储布局泄露给客户端。
 */
@Data
public class DocTemplateVO {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String description;
    private String originalName;
    private String contentType;
    private String extension;
    private Long sizeBytes;
    /** 内容版本号，每次替换内容递增。 */
    private Integer versionNo;
    /** 变量定义 JSON 字符串，前端解析后渲染表单。 */
    private String variables;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否已上传模板文件。 */
    private Boolean hasFile;

    public static DocTemplateVO from(DocTemplate template) {
        DocTemplateVO vo = new DocTemplateVO();
        vo.setId(template.getId());
        vo.setCode(template.getCode());
        vo.setName(template.getName());
        vo.setCategory(template.getCategory());
        vo.setDescription(template.getDescription());
        vo.setOriginalName(template.getOriginalName());
        vo.setContentType(template.getContentType());
        vo.setExtension(template.getExtension());
        vo.setSizeBytes(template.getSizeBytes());
        vo.setVersionNo(template.getVersionNo());
        vo.setVariables(template.getVariables());
        vo.setEnabled(template.getEnabled());
        vo.setCreateTime(template.getCreateTime());
        vo.setUpdateTime(template.getUpdateTime());
        vo.setHasFile(template.getStorageKey() != null && !template.getStorageKey().isBlank());
        return vo;
    }
}
