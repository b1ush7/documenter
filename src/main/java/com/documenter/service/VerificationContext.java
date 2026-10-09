package com.documenter.service;

import com.documenter.enums.VerificationScene;

/**
 * 验证码请求上下文（TODO 3.1）。
 *
 * <p>发送与校验都需要记录来源 IP 与请求 ID，用于风控和排障，
 * 因此把这两个值从 Web 层透传到业务层，而不是在业务层直接依赖 HttpServletRequest。
 */
public record VerificationContext(String requestIp, String requestId) {

    public static VerificationContext empty() {
        return new VerificationContext(null, null);
    }
}
