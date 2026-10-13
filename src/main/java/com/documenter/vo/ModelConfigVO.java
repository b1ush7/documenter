package com.documenter.vo;

import com.documenter.entity.ModelConfig;
import com.documenter.util.SecretCipher;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置出参（TODO 3.2「模型密钥不得返回前端」）。
 *
 * <p>这是本模块的<b>唯一</b>出口，因此这里刻意没有 {@code apiKeyCipher} 与
 * {@code apiKeyHint} 字段——只要这两个字段不存在，就不可能出现
 * 「某天有人图省事直接 return entity」而把密文或提示位漏出去。
 *
 * <p>密钥信息只通过 {@link #apiKeyMasked} 暴露，形如 {@code sk-a****z9x1}，
 * 用于确认「当前生效的是哪把密钥」，不足以还原密钥。
 * {@link #apiKeyConfigured} 用于前端区分「未配置」和「已配置」。
 */
@Data
public class ModelConfigVO {

    private Long id;
    private String code;
    private String name;
    private String scope;
    private String provider;
    private String baseUrl;
    private String modelName;
    private Integer timeoutSeconds;
    private Integer maxOutputTokens;
    private BigDecimal temperature;
    private String status;
    private Boolean isDefault;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否已配置密钥。 */
    private Boolean apiKeyConfigured;

    /** 脱敏后的密钥提示；未配置时为 null。 */
    private String apiKeyMasked;

    /**
     * 从实体转换。
     *
     * <p>注意：解密需要主密钥，未配置主密钥时无法生成提示，
     * 此时只标记「已配置」而提示为 null，不能因为拿不到提示就报错——
     * 否则管理员连列表都看不了。
     */
    public static ModelConfigVO from(ModelConfig config, SecretCipher cipher) {
        ModelConfigVO vo = new ModelConfigVO();
        vo.setId(config.getId());
        vo.setCode(config.getCode());
        vo.setName(config.getName());
        vo.setScope(config.getScope());
        vo.setProvider(config.getProvider());
        vo.setBaseUrl(config.getBaseUrl());
        vo.setModelName(config.getModelName());
        vo.setTimeoutSeconds(config.getTimeoutSeconds());
        vo.setMaxOutputTokens(config.getMaxOutputTokens());
        vo.setTemperature(config.getTemperature());
        vo.setStatus(config.getStatus());
        vo.setIsDefault(config.getIsDefault());
        vo.setRemark(config.getRemark());
        vo.setCreateTime(config.getCreateTime());
        vo.setUpdateTime(config.getUpdateTime());

        boolean configured = config.getApiKeyCipher() != null && !config.getApiKeyCipher().isBlank();
        vo.setApiKeyConfigured(configured);
        if (configured && cipher != null && cipher.available()) {
            try {
                vo.setApiKeyMasked(SecretCipher.maskHint(cipher.decrypt(config.getApiKeyCipher())));
            } catch (RuntimeException e) {
                // 主密钥变更导致历史密文解不开：不阻断列表，只标记「已配置」而给出空提示
                vo.setApiKeyMasked(null);
            }
        }
        return vo;
    }
}
