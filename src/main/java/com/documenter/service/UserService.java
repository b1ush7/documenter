package com.documenter.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.documenter.dto.LoginByCodeDTO;
import com.documenter.dto.RegisterByCodeDTO;
import com.documenter.dto.UserLoginDTO;
import com.documenter.dto.UserRegisterDTO;
import com.documenter.entity.User;
import com.documenter.vo.TokenPairVO;
import com.documenter.vo.UserInfoVO;

public interface UserService extends IService<User> {

    /**
     * 密码注册（TODO 3.1）。
     *
     * @return 注册成功的用户信息
     */
    UserInfoVO register(UserRegisterDTO dto);

    /**
     * 验证码注册（TODO 3.1），手机号必填且需先通过验证码校验。
     */
    UserInfoVO registerByCode(RegisterByCodeDTO dto);

    /**
     * 密码登录（TODO 3.1），内部调用 JwtUtil.createTokenPair 签发双 Token。
     *
     * @param dto 账号（用户名或手机号）与密码
     * @return 双 Token
     */
    TokenPairVO login(UserLoginDTO dto);

    /**
     * 验证码登录（TODO 3.1），不需要密码。
     */
    TokenPairVO loginByCode(LoginByCodeDTO dto);

    /**
     * 刷新双 Token（TODO 3.1），内部调用 JwtUtil.refreshTokenPair 原子轮换。
     * 刷新不要求 Access Token 未过期。
     */
    TokenPairVO refresh(String refreshToken);

    /** 查询当前登录用户信息。 */
    UserInfoVO currentUser(Long userId);

    /**
     * 按手机号查询用户，供验证码流程判断账号是否存在与是否被禁用。
     *
     * @return 不存在时返回 null
     */
    User findByPhone(String phone);

    /**
     * 修改密码（TODO 3.1：改密后撤销会话）。
     *
     * <p>必须校验原密码；成功后撤销该用户的<b>全部</b>会话，
     * 包括当前会话，所有设备都需要重新登录。
     *
     * @param userId      当前登录用户
     * @param oldPassword 原密码
     * @param newPassword 新密码
     */
    void changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * 找回密码（TODO 3.1 验证码场景）。
     *
     * <p>校验 RESET_PASSWORD 用途的验证码，通过后重设密码并撤销全部会话。
     * 不需要原密码，也不需要登录态。
     */
    void resetPassword(String phone, String code, String newPassword);
}
