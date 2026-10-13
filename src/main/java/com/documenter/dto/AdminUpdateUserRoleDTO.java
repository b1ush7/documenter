package com.documenter.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 管理员调整账号角色。 */
@Data
public class AdminUpdateUserRoleDTO {

    @NotNull(message = "用户角色不能为空")
    @Pattern(regexp = "^(USER|ADMIN)$", message = "用户角色只支持 USER、ADMIN")
    private String role;
}
