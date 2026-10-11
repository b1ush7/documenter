package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 密码登录请求（TODO 3.1）。
 *
 * <p>account 允许填用户名或手机号，由服务端判断手机号规则后再决定查询方式，
 * 避免前端需要选择登录类型。
 */
@Data
public class UserLoginDTO {

    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;
}
