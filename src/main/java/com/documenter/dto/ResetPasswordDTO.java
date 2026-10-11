package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 找回密码请求（TODO 3.1 的验证码场景之一）。
 *
 * <p>适用于「已忘记原密码」的情况，因此不能要求提供原密码，
 * 改用手机验证码证明手机号归属。
 *
 * <p>用途隔离：本次校验固定使用 {@link com.documenter.enums.VerificationScene#RESET_PASSWORD}，
 * 与注册、登录的验证码互不通用。这样即便攻击者拿到了注册场景的验证码，
 * 也无法用于重置他人密码。
 *
 * <p>单步还是两步：这里采用单步（验证码 + 新密码一起提交）。
 * 两步式需要额外引入短期重置凭证及其存储与失效逻辑，
 * 属于过度设计——前端若想提前确认验证码有效，
 * 已有 {@code POST /user/verify/code} 可用。
 */
@Data
public class ResetPasswordDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{4,8}$", message = "验证码格式不正确")
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度必须为 8-64 位")
    private String newPassword;
}
