package com.documenter.service;

import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.vo.AiReplyVO;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface AiReplyService {
    AiReplyVO create(Long userId, Long sessionId, CreateAiReplyDTO request);

    /**
     * 流式生成回复。事件中的 replyId 在模型调用前即可用于查询和断线恢复；
     * TEXT_DELTA 是模型真实增量文本。
     */
    AiReplyVO createStreaming(Long userId, Long sessionId, CreateAiReplyDTO request,
                              Consumer<StreamEvent> events, BooleanSupplier cancelled);
    AiReplyVO get(Long userId, Long sessionId, Long messageId);
    AiReplyVO confirm(Long userId, Long sessionId, Long messageId);
    AiReplyVO discard(Long userId, Long sessionId, Long messageId);

    record StreamEvent(Type type, Long replyId, String text) {
        public enum Type {
            STARTED,
            TEXT_DELTA,
            COMPLETED
        }
    }
}
