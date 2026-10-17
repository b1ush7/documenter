package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.service.AiReplyService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.AiReplyVO;
import com.documenter.vo.AgentRunResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 新增回复闭环，原有用户消息记录接口继续保持原语义。 */
@RestController
@RequestMapping("/ai/sessions/{sessionId}/replies")
public class AiReplyController {
    private final AiReplyService service;

    public AiReplyController(AiReplyService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AiReplyVO> create(@PathVariable Long sessionId, @Valid @RequestBody CreateAiReplyDTO request) {
        AiReplyVO reply = service.create(SecurityUtil.requireUserId(), sessionId, request);
        if (reply.result() != null && (reply.result().termination() == AgentRunResult.Termination.QUOTA_EXCEEDED
                || reply.result().termination() == AgentRunResult.Termination.QUOTA_UNAVAILABLE)) {
            ApiResponse<AiReplyVO> response = ApiResponse.error(
                    reply.result().termination() == AgentRunResult.Termination.QUOTA_EXCEEDED ? 429 : 503,
                    reply.message().getContent());
            response.setData(reply);
            return response;
        }
        return ApiResponse.success(reply);
    }

    @GetMapping("/{messageId}")
    public ApiResponse<AiReplyVO> get(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return ApiResponse.success(service.get(SecurityUtil.requireUserId(), sessionId, messageId));
    }

    @PostMapping("/{messageId}/confirm")
    public ApiResponse<AiReplyVO> confirm(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return ApiResponse.success("计划已确认", service.confirm(SecurityUtil.requireUserId(), sessionId, messageId));
    }

    @PostMapping("/{messageId}/discard")
    public ApiResponse<AiReplyVO> discard(@PathVariable Long sessionId, @PathVariable Long messageId) {
        return ApiResponse.success("计划已放弃", service.discard(SecurityUtil.requireUserId(), sessionId, messageId));
    }
}
