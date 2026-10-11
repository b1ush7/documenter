package com.documenter.service;

import com.documenter.enums.VerificationScene;

/**
 * 验证码发送渠道抽象（TODO 3.1「接入验证码发送服务：服务端生成验证码」）。
 *
 * <p>业务层只依赖本接口，因此更换短信/邮件服务商时不需要改动验证码逻辑。
 * 验证码的生成、摘要、有效期、频率与次数限制都在业务层完成，实现类只负责投递。
 *
 * <p>当前 P0 阶段只有 MockVerificationCodeSender 一个实现，由 Spring 直接注入。
 * 接入真实服务商时会有第二个实现，届时必须在配置上做出明确选择
 * （例如用 @ConditionalOnProperty 让实现类互斥，或加 @Primary），
 * 而不是靠注入顺序碰运气——多实现共存时 Spring 会直接启动失败，这是期望的行为。
 *
 * <p>实现方必须遵守：投递失败要抛出异常，以便业务层释放限流占位并提示用户。
 */
public interface VerificationCodeSender {

    /**
     * 投递验证码。
     *
     * @param identifier 接收对象，例如手机号
     * @param scene      用途
     * @param code       明文验证码
     * @throws com.documenter.exception.BusinessException 投递失败时抛出
     */
    void send(String identifier, VerificationScene scene, String code);
}
