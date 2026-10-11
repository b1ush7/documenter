package com.documenter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 管理员启用或禁用账号。 */
@Data
public class AdminUpdateUserStatusDTO {

    @NotNull(message = "账号状态不能为空")
    @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "账号状态只支持 ENABLED、DISABLED")
    private String status;
}
