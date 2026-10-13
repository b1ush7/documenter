package com.documenter.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 AI 会话（TODO 3.2）。
 *
 * <p>{@code fileId} 可选：绑定文件时会话按文档隔离；
 * 但绑定前必须校验该文件属于当前用户，否则会出现「用自己的会话读别人的文档」。
 */
@Data
public class CreateAiSessionDTO {

    @Size(max = 128, message = "会话标题不能超过 128 个字符")
    private String title;

    private Long fileId;
}
