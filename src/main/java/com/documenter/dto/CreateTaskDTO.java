package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建任务请求（TODO 3.4）。
 *
 * <p>幂等键的设计：客户端为「一次用户意图」生成一个稳定值（例如 UUID），
 * 重试提交时沿用同一个键，服务端据此返回已有任务而不是再建一个。
 * 唯一索引是 (user_id, idempotency_key)，因此不同用户可以用相同的键。
 */
@Data
public class CreateTaskDTO {

    @NotNull(message = "任务类型不能为空")
    @Pattern(regexp = "^(OCR|CONVERT|AI_EDIT|EXPORT|IMAGE_GEN)$",
            message = "任务类型不支持，可选值：OCR、CONVERT、AI_EDIT、EXPORT、IMAGE_GEN")
    private String taskType;

    /** 关联文件；纯生成类任务可以不传。 */
    private Long fileId;

    /** 输入版本号；不传表示使用当前最新版本。 */
    @Min(value = 1, message = "输入版本号必须大于 0")
    private Integer inputVersion;

    @Size(max = 2000, message = "指令过长")
    private String instruction;

    /**
     * 幂等键。长度限制与数据库列宽一致，避免插入时被截断导致幂等失效。
     */
    @Size(max = 128, message = "幂等键过长")
    private String idempotencyKey;

    /** 期望的当前版本号，用于确认编辑时防止并发覆盖。 */
    @Min(value = 0, message = "期望版本号不能为负数")
    private Integer expectVersion;

    /** 超时秒数；不传则取配置默认值。 */
    @Min(value = 10, message = "超时时间不能少于 10 秒")
    @Max(value = 3600, message = "超时时间不能超过 3600 秒")
    private Integer timeoutSeconds;
}
