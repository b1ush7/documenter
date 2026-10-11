package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 任务列表查询条件（TODO 3.4「提供状态查询」）。
 */
@Data
public class TaskQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    /** 状态过滤，留空表示全部。 */
    @Pattern(regexp = "^$|^(PENDING|RUNNING|SUCCEEDED|FAILED|CANCELLED)$",
            message = "状态取值不支持")
    private String status;

    @Pattern(regexp = "^$|^(OCR|CONVERT|AI_EDIT|EXPORT|IMAGE_GEN)$", message = "任务类型取值不支持")
    private String taskType;

    /** 按文件过滤。 */
    private Long fileId;
}
