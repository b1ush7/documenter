package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 会话查询条件（TODO 3.2）。
 */
@Data
public class AiSessionQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 留空表示不限状态。 */
    @Pattern(regexp = "^$|^(ACTIVE|ARCHIVED)$", message = "状态只支持 ACTIVE、ARCHIVED")
    private String status;

    private Long fileId;

    @Size(max = 100, message = "搜索关键字过长")
    private String keyword;
}
