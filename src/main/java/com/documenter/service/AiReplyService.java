package com.documenter.service;

import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.vo.AiReplyVO;

public interface AiReplyService {
    AiReplyVO create(Long userId, Long sessionId, CreateAiReplyDTO request);
    AiReplyVO get(Long userId, Long sessionId, Long messageId);
    AiReplyVO confirm(Long userId, Long sessionId, Long messageId);
    AiReplyVO discard(Long userId, Long sessionId, Long messageId);
}
