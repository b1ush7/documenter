package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 验证码策略配置（TODO 3.1：限制发送频率、日发送量和校验错误次数）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.verification")
public class VerificationCodeProperties {

    /** 验证码位数，6 位是短信渠道的常见做法。 */
    private int codeLength = 6;

    /** 验证码有效期。 */
    private Duration ttl = Duration.ofMinutes(5);

    /** 同一接收对象同一用途的重发间隔。 */
    private Duration resendInterval = Duration.ofSeconds(60);

    /** 滑动 24 小时内的最大发送次数，用于日发送量限制。 */
    private int dailyLimit = 10;

    /** 最多允许校验失败几次，超出后验证码立即作废，需重新发送。 */
    private int maxAttempts = 5;
}
