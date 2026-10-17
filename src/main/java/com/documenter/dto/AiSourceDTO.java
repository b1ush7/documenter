package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户随本轮指令提供的来源；服务端只回显，不把“用户提供”解释为已验证。 */
@Data
public class AiSourceDTO {

    @NotBlank(message = "来源标题不能为空")
    @Size(max = 200, message = "来源标题不能超过 200 字符")
    private String title;

    @Size(max = 1000, message = "来源链接不能超过 1000 字符")
    private String url;

    @Size(max = 2000, message = "来源摘录不能超过 2000 字符")
    private String excerpt;
}
