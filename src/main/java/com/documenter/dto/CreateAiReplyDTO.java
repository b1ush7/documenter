package com.documenter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 同步生成一次回复；绑定文件的会话必须同时传明确版本。 */
@Data
public class CreateAiReplyDTO {
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息不能超过 2000 字符")
    private String content;

    @Min(value = 1, message = "版本号必须大于 0")
    private Integer versionNo;

    @NotBlank(message = "幂等键不能为空")
    @Size(max = 128, message = "幂等键不能超过 128 字符")
    private String idempotencyKey;
}
