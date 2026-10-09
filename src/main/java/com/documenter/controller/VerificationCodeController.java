package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.SendVerificationCodeDTO;
import com.documenter.dto.VerifyCodeDTO;
import com.documenter.enums.VerificationScene;
import com.documenter.service.VerificationCodeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 验证码接口（TODO 3.1）。
 *
 * <p>用途由路径决定，不接受前端指定 scene，避免前端传入错误用途绕过前置校验。
 * 所有路径都在免认证白名单内：注册、登录、找回密码本身就发生在未登录状态。
 *
 * <p>响应中只返回脱敏手机号，绝不返回验证码本身；P0 阶段的验证码打印在服务端日志里
 * （见 MockVerificationCodeSender）。
 */
@RestController
@RequestMapping("/user")
public class VerificationCodeController {

    private final VerificationCodeService verificationCodeService;

    public VerificationCodeController(VerificationCodeService verificationCodeService) {
        this.verificationCodeService = verificationCodeService;
    }

    /** 注册用验证码：要求手机号尚未注册。 */
    @PostMapping("/register/code")
    public ApiResponse<String> sendRegisterCode(@Valid @RequestBody SendVerificationCodeDTO dto) {
        String masked = verificationCodeService.sendCode(dto.getPhone(), VerificationScene.REGISTER);
        return ApiResponse.success("验证码已发送", masked);
    }

    /** 验证码登录用验证码：要求手机号已注册且账号未被禁用。 */
    @PostMapping("/login/code")
    public ApiResponse<String> sendLoginCode(@Valid @RequestBody SendVerificationCodeDTO dto) {
        String masked = verificationCodeService.sendCode(dto.getPhone(), VerificationScene.LOGIN);
        return ApiResponse.success("验证码已发送", masked);
    }

    /**
     * 独立校验验证码，供前端在提交注册前做一次预校验。
     *
     * <p>注意：本接口会真正消费验证码，成功后验证码立即失效。
     */
    @PostMapping("/verify/code")
    public ApiResponse<Void> verifyCode(@Valid @RequestBody VerifyCodeDTO dto) {
        verificationCodeService.consumeCode(dto.getPhone(), VerificationScene.REGISTER, dto.getCode());
        return ApiResponse.success("验证通过", null);
    }
}
