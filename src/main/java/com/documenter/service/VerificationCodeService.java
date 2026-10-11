package com.documenter.service;

import com.documenter.enums.VerificationScene;

/**
 * 验证码业务（TODO 3.1）。
 *
 * <p>职责划分：
 * <ul>
 *   <li>本接口负责生成、摘要、频率与日限额、错误次数、原子消费；</li>
 *   <li>{@link VerificationCodeSender} 只负责把验证码投递出去；</li>
 *   <li>verification_record 表保存发送与消费记录，用于审计与日限额统计。</li>
 * </ul>
 */
public interface VerificationCodeService {

    /**
     * 发送验证码。
     *
     * @return 脱敏后的接收对象，绝不返回验证码明文
     * @throws com.documenter.exception.BusinessException 频率超限、超过日限额、账号状态不符或投递失败
     */
    String sendCode(String identifier, VerificationScene scene);

    /**
     * 校验并原子消费验证码。
     *
     * <p>成功时验证码立即失效（一次性），失败时累加错误次数，超过上限直接作废。
     *
     * @throws com.documenter.exception.BusinessException 验证码不存在、错误次数超限或校验不通过
     */
    void consumeCode(String identifier, VerificationScene scene, String code);
}
