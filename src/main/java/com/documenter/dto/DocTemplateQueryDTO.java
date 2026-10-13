package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 公共模板查询条件（TODO 3.2）。
 */
@Data
public class DocTemplateQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 留空表示不限启用状态。 */
    @Pattern(regexp = "^$|^(true|false)$", message = "enabled 只支持 true 或 false")
    private String enabled;

    @Size(max = 32, message = "分类过长")
    private String category;

    /** 按模板标识或名称模糊搜索。 */
    @Size(max = 100, message = "搜索关键字过长")
    private String keyword;
}
