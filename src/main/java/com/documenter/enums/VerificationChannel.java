package com.documenter.enums;

/**
 * 验证码发送渠道（TODO 3.1）。
 *
 * <p>渠道尚未最终确定，因此这里只描述渠道本身，具体由 {@code VerificationCodeSender} 实现决定。
 */
public enum VerificationChannel {

    /** 短信。现有 user 表有 phone 字段，是 P0 优先评估的渠道。 */
    SMS,

    /** 邮件。需要 SMTP 配置，暂作为备选。 */
    EMAIL
}
