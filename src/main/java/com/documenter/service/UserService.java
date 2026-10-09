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
}
