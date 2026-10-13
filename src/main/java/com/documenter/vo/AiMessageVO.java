package com.documenter.vo;

import com.documenter.entity.AiMessage;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息出参（TODO 3.2）。
 */
@Data
public class AiMessageVO {

    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String toolName;
    /** 工具参数已脱敏；不含模型密钥。 */
    private String toolArgs;
    private String toolResult;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
    private LocalDateTime createTime;

    public static AiMessageVO from(AiMessage message) {
        AiMessageVO vo = new AiMessageVO();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setToolName(message.getToolName());
        vo.setToolArgs(message.getToolArgs());
        vo.setToolResult(message.getToolResult());
        vo.setModelName(message.getModelName());
        vo.setPromptTokens(message.getPromptTokens());
        vo.setCompletionTokens(message.getCompletionTokens());
        vo.setDurationMs(message.getDurationMs());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }
}
