package com.documenter.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新建模型配置（TODO 3.2「管理模型配置」）。
 *
 * <p>用途用正则而不是枚举：枚举校验失败时 Spring 返回的错误信息指向字段名，
 * 前端拿不到「可选值是什么」，这里直接给出候选值。
 */
@Data
public class SaveModelConfigDTO {

    @NotBlank(message = "配置标识不能为空")
    @Size(max = 64, message = "配置标识不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "配置标识只能包含字母、数字、下划线和连字符")
    private String code;

    @NotBlank(message = "配置名称不能为空")
    @Size(max = 128, message = "配置名称不能超过 128 个字符")
    private String name;

    @NotBlank(message = "用途不能为空")
    @Pattern(regexp = "^(TEXT|IMAGE|OCR)$", message = "用途只支持 TEXT、IMAGE、OCR")
    private String scope;

    @NotBlank(message = "服务商标识不能为空")
    @Size(max = 32, message = "服务商标识不能超过 32 个字符")
    private String provider;

    @Size(max = 512, message = "接口地址不能超过 512 个字符")
    private String baseUrl;

    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名不能超过 128 个字符")
    private String modelName;

    /**
     * API Key 明文，仅在写入时接收。
     *
     * <p>服务端会用 AES-GCM 加密后落库，并在出参里只返回脱敏提示，
     * 因此这个字段永远不会被回读。更新时留空表示「保持原有密钥不变」。
     */
    @Size(max = 512, message = "API Key 过长")
    private String apiKey;

    @Min(value = 1, message = "超时时间不能少于 1 秒")
    @Max(value = 600, message = "超时时间不能超过 600 秒")
    private Integer timeoutSeconds;

    @Min(value = 1, message = "输出长度上限必须大于 0")
    @Max(value = 1000000, message = "输出长度上限过大")
    private Integer maxOutputTokens;

    @DecimalMin(value = "0.0", message = "温度不能小于 0")
    @DecimalMax(value = "2.0", message = "温度不能大于 2")
    private BigDecimal temperature;

    /** 是否设为该用途下的默认配置。 */
    private Boolean isDefault;

    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;
}
