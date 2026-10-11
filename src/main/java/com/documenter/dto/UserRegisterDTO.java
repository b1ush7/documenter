package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求（TODO 3.1）。
 *
 * <p>密码只做长度与字符集约束，具体强度策略后续可加。
 * 用户名同时用于登录，必须唯一且不能是纯空白。
 */
@Data
public class UserRegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度必须为 4-20 位")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 手机号选填：TODO 3.1 的验证码方案优先短信，但尚未最终确定渠道，
     * 因此注册阶段不强制要求手机号，留空即使用密码登录。
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为 8-64 位")
    private String password;
}
