package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文件列表查询条件（TODO 3.3「实现文件列表」）。
 *
 * <p>排序字段不接受前端自由指定，避免 ORDER BY 注入；只暴露固定的几种排序。
 */
@Data
public class FileQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 按展示名模糊搜索，可选。 */
    @Size(max = 100, message = "搜索关键字过长")
    private String keyword;

    /** 状态过滤：READY / DELETED / QUARANTINED，留空默认只看 READY。 */
    private String status;
}
