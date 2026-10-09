package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送验证码请求（TODO 3.1）。
 *
 * <p>用途由请求路径决定（/user/register/code、/user/login/code），
 * 不接受前端传入 scene，避免前端指定错误用途导致校验绕过。
 */
@Data
public class SendVerificationCodeDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
