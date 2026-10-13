package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员用户列表查询条件。 */
@Data
public class AdminUserQueryDTO {

    @Min(value = 1, message = "页码必须大于 0")
    private long page = 1;

    @Min(value = 1, message = "每页条数必须大于 0")
    @Max(value = 100, message = "每页最多 100 条")
    private long size = 20;

    @Size(max = 100, message = "搜索关键字过长")
    private String keyword;

    @Pattern(regexp = "^$|^(ENABLED|DISABLED)$", message = "账号状态取值不支持")
    private String status;

    @Pattern(regexp = "^$|^(USER|ADMIN)$", message = "角色取值不支持")
    private String role;
}
