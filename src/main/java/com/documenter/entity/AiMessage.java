package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 消息（TODO 3.2）。
 *
 * <p>刻意<b>不</b>在本表冗余 {@code userId}：消息的归属只能通过
 * {@code sessionId} 回到 {@link AiSession} 判断。若这里也存一份 userId，
 * 一旦两处不一致就会出现「按消息表查是本人、按会话表查是他人」的归属漏洞。
 *
 * <p>{@code toolArgs} / {@code toolResult} 存储前必须脱敏，
 * 不得写入模型密钥或完整文件正文。
 */
@TableName("ai_message")
@Data
public class AiMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** USER / ASSISTANT / TOOL / SYSTEM。 */
    private String role;
    private String content;
    private String toolName;
    private String toolArgs;
    private String toolResult;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
    private LocalDateTime createTime;
}
