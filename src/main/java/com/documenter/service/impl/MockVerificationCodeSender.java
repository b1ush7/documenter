package com.documenter.service.impl;

import com.documenter.enums.VerificationScene;
import com.documenter.service.VerificationCodeSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地 Mock 验证码渠道（TODO 3.1，P0 阶段不发真实短信）。
 *
 * <p>验证码只写入服务端日志，开发时从日志里读取即可完成后端自测。
 * 接入真实服务商时新增一个实现类，并用 @ConditionalOnProperty 让两个实现互斥
 * （或给真实实现加 @Primary），否则 Spring 会因为存在两个候选 Bean 而启动失败。
 *
 * <p>注意：日志在生产环境属于敏感信息，因此本类必须靠 res.verification.mock-enabled
 * 控制；默认值为 true 是为了本地开发方便，上线前必须显式关闭。
 */
@Slf4j
@Component
public class MockVerificationCodeSender implements VerificationCodeSender {

    @Override
    public void send(String identifier, VerificationScene scene, String code) {
        // 明确标注这是 Mock，避免运维误以为已接入真实短信通道
        log.warn("[Mock 验证码] 未接入真实短信服务，验证码仅打印在日志中。"
                        + " 手机号={} 用途={} 验证码={}",
                mask(identifier), scene, code);
    }

    /** 日志里只保留后四位，减少敏感信息暴露。 */
    private static String mask(String identifier) {
        if (identifier == null || identifier.length() < 4) {
            return "****";
        }
        return "****" + identifier.substring(identifier.length() - 4);
    }
}
