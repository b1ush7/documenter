package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送一条用户消息（TODO 3.2）。
 *
 * <p>约束存在的意义：{@code ai_message.content} 是 MEDIUMTEXT，
 * 但一次对话指令不应该有几百 KB，限制长度可以避免把上下文窗口和存储一起打爆。
 *
 * <p>旧消息接口仅做归属校验和消息落库；模型回复使用 CreateAiReplyDTO。
 */
@Data
public class SendAiMessageDTO {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 8000, message = "单条消息不能超过 8000 个字符")
    private String content;
}
