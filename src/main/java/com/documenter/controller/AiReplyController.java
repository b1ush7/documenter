package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.configuration.AiStreamExecutionConfiguration;
import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiReplyService;
import com.documenter.service.VerificationRequestContextHolder;
import org.slf4j.MDC;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.AiReplyVO;
import com.documenter.vo.AgentRunResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 新增回复闭环，原有用户消息记录接口继续保持原语义。 */
@RestController
@RequestMapping("/ai/sessions/{sessionId}/replies")
public class AiReplyController {
    private final AiReplyService service;
    private final ExecutorService streamExecutor;

    public AiReplyController(AiReplyService service,
                             @Qualifier(AiStreamExecutionConfiguration.AI_STREAM_EXECUTOR)
                             ExecutorService streamExecutor) {
        this.service = service;
        this.streamExecutor = streamExecutor;
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

    /**
     * SSE 增量回复。事件名：
     * started（replyId）、delta（模型增量文本）、completed（完整 AiReplyVO）、error。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@PathVariable Long sessionId,
                             @Valid @RequestBody CreateAiReplyDTO request) {
        Long userId = SecurityUtil.requireUserId();
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        var requestContext = VerificationRequestContextHolder.get();
        var loggingContext = MDC.getCopyOfContextMap();
        try {
            streamExecutor.execute(new DelegatingSecurityContextRunnable(() -> {
                VerificationRequestContextHolder.set(requestContext);
                if (loggingContext != null) MDC.setContextMap(loggingContext);
                try {
                    AiReplyVO reply = service.createStreaming(userId, sessionId, request, event -> {
                        try {
                            switch (event.type()) {
                                case STARTED -> emitter.send(SseEmitter.event().name("started")
                                        .data(Map.of("replyId", event.replyId())));
                                case TEXT_DELTA -> emitter.send(SseEmitter.event().name("delta")
                                        .data(Map.of("text", event.text() == null ? "" : event.text())));
                                case COMPLETED -> { }
                            }
                        } catch (IOException e) {
                            cancelled.set(true);
                        }
                    }, cancelled::get);
                    emitter.send(SseEmitter.event().name("completed").data(reply));
                    emitter.complete();
                } catch (Exception failure) {
                    int code = failure instanceof BusinessException business
                            ? business.getCode() : 500;
                    String message = failure instanceof BusinessException business
                            ? business.getMessage() : "流式回复失败，请稍后重试";
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(ApiResponse.error(code, message)));
                    } catch (Exception ignored) {
                        // 客户端已经断开时无需再次写响应。
                    }
                    emitter.completeWithError(failure);
                } finally {
                    VerificationRequestContextHolder.clear();
                    MDC.clear();
                }
            }));
        } catch (RejectedExecutionException rejected) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(ApiResponse.error(503, "流式回复繁忙，请稍后重试")));
            } catch (IOException ignored) {
                // 写响应失败时仍以 completeWithError 结束连接。
            }
            emitter.completeWithError(rejected);
        }
        return emitter;
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
