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
 * <p>注意：模型调用尚未接入（属于 TODO 4.2 P1 阶段），
 * 当前接口只完成「归属校验 + 消息落库」，
 * 不会返回伪造的助手回复——那会让前端误以为对话已可用。
 */
@Data
public class SendAiMessageDTO {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 8000, message = "单条消息不能超过 8000 个字符")
    private String content;
}
