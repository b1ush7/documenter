package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型配置查询条件（TODO 3.2）。
 */
@Data
public class ModelConfigQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 留空表示不限用途。 */
    @Pattern(regexp = "^$|^(TEXT|IMAGE|OCR)$", message = "用途只支持 TEXT、IMAGE、OCR")
    private String scope;

    /** 留空表示不限状态。 */
    @Pattern(regexp = "^$|^(ENABLED|DISABLED)$", message = "状态只支持 ENABLED、DISABLED")
    private String status;

    /** 按配置标识或名称模糊搜索。 */
    @Size(max = 100, message = "搜索关键字过长")
    private String keyword;
}
