package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.LoginByCodeDTO;
import com.documenter.dto.RefreshTokenDTO;
import com.documenter.dto.RegisterByCodeDTO;
import com.documenter.dto.UserLoginDTO;
import com.documenter.dto.UserRegisterDTO;
import com.documenter.filter.JwtAuthenticationFilter;
import com.documenter.service.UserService;
import com.documenter.util.JwtUtil;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.TokenPairVO;
import com.documenter.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口（TODO 3.1）。
 *
 * <p>路径约定：
 * <ul>
 *   <li>{@code POST /user/register} 注册</li>
 *   <li>{@code POST /user/login} 密码登录，返回双 Token</li>
 *   <li>{@code POST /user/refresh} 刷新双 Token（原子轮换，旧 Refresh Token 立即失效）</li>
 *   <li>{@code GET  /user/me} 当前用户信息</li>
 *   <li>{@code POST /user/logout} 退出当前设备（由 Spring Security 的 logout 处理）</li>
 *   <li>{@code POST /user/logout-all} 退出全部其他设备</li>
 * </ul>
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public UserController(UserService userService, JwtUtil jwtUtil,
                          JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @PostMapping("/register")
    public ApiResponse<UserInfoVO> register(@Valid @RequestBody UserRegisterDTO dto) {
        return ApiResponse.success("注册成功", userService.register(dto));
    }

    /** 验证码注册（TODO 3.1）：手机号必填，需先调用 /user/register/code 获取验证码。 */
    @PostMapping("/register/by-code")
    public ApiResponse<UserInfoVO> registerByCode(@Valid @RequestBody RegisterByCodeDTO dto) {
        return ApiResponse.success("注册成功", userService.registerByCode(dto));
    }

    @PostMapping("/login")
    public ApiResponse<TokenPairVO> login(@Valid @RequestBody UserLoginDTO dto) {
        return ApiResponse.success("登录成功", userService.login(dto));
    }

    /** 验证码登录（TODO 3.1）：需先调用 /user/login/code 获取验证码。 */
    @PostMapping("/login/by-code")
    public ApiResponse<TokenPairVO> loginByCode(@Valid @RequestBody LoginByCodeDTO dto) {
        return ApiResponse.success("登录成功", userService.loginByCode(dto));
    }

    /**
     * 刷新 Token。此接口不要求 Access Token 未过期，只校验 Refresh Token，
     * 因此前端在 Access Token 过期后仍可凭 localStorage 中的 Refresh Token 续期。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenPairVO> refresh(@Valid @RequestBody RefreshTokenDTO dto) {
        return ApiResponse.success("刷新成功", userService.refresh(dto.getRefreshToken()));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoVO> me() {
        return ApiResponse.success(userService.currentUser(SecurityUtil.requireUserId()));
    }

    /**
     * 退出全部其他设备，保留当前设备登录状态。
     *
     * <p>本接口只撤销「除当前会话之外」的会话，因此用户不会被自己踢下线。
     */
    @PostMapping("/logout-all")
    public ApiResponse<Long> logoutAll(HttpServletRequest request) {
        Long userId = SecurityUtil.requireUserId();
        String accessToken = jwtAuthenticationFilter.getAccessToken(request);
        // 当前会话 ID 从 Access Token 中解析，用于保留当前设备
        String currentSessionId = jwtUtil.verify(accessToken).getClaim("sid").asString();
        long revoked = jwtUtil.revokeAllSessions(String.valueOf(userId), currentSessionId);
        return ApiResponse.success("已退出全部其他设备", revoked);
    }
}
