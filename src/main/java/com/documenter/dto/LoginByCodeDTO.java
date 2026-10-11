package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 验证码登录请求（TODO 3.1）。
 *
 * <p>与 {@link UserLoginDTO} 的区别：不需要密码，改由短信验证码证明手机号归属。
 */
@Data
public class LoginByCodeDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{4,8}$", message = "验证码格式不正确")
    private String code;
}
