package com.documenter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 启用/停用模型配置（TODO 3.2）。
 *
 * <p>停用而不是删除：已引用该配置的历史任务需要保留可追溯性，
 * 直接删除会让历史记录指向一个不存在的配置。
 */
@Data
public class UpdateModelConfigStatusDTO {

    @NotNull(message = "状态不能为空")
    @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "状态只支持 ENABLED、DISABLED")
    private String status;
}
