package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置（TODO 3.2「管理模型配置；模型密钥不得返回前端」）。
 *
 * <p>密钥相关字段的约定：
 * <ul>
 *   <li>{@code apiKeyCipher} 存 AES-GCM 密文，加密主密钥来自环境变量，不落库；</li>
 *   <li>{@code apiKeyHint} 只存末尾提示位，供前端显示「已配置哪把密钥」，
 *       不足以还原密钥；</li>
 *   <li>密文与提示位都<b>不允许</b>出现在任何出参中，出口统一走 {@code ModelConfigVO}。</li>
 * </ul>
 *
 * <p>{@code defaultScopeMarker} 是数据库唯一索引所需的辅助列：
 * MariaDB 的唯一索引允许多行 NULL，所以「默认配置」时写入 scope、非默认时写入 NULL，
 * 从而保证同一 scope 下最多一个默认配置。业务代码不应直接读它。
 */
@TableName("model_config")
@Data
public class ModelConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String scope;
    private String provider;
    private String baseUrl;
    private String modelName;
    /** API Key 密文；禁止出现在出参中。 */
    private String apiKeyCipher;
    /** 密钥末尾提示位；禁止出现在出参中（用 ModelConfigVO.apiKeyMasked 代替）。 */
    private String apiKeyHint;
    private Integer timeoutSeconds;
    private Integer maxOutputTokens;
    private BigDecimal temperature;
    private String status;
    private Boolean isDefault;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 内部维护的唯一索引辅助列，业务代码不直接使用。 */
    private String defaultScopeMarker;

    /** 是否启用（仅业务判断，不入库）。MyBatis-Plus 只映射字段，非字段方法无需标注。 */
    public boolean isEnabled() {
        return "ENABLED".equals(status);
    }
}
