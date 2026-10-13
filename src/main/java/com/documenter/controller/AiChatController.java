package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.AiSessionQueryDTO;
import com.documenter.dto.CreateAiSessionDTO;
import com.documenter.dto.SendAiMessageDTO;
import com.documenter.service.AiChatService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.AiMessageVO;
import com.documenter.vo.AiSessionVO;
import com.documenter.vo.PageResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 对话接口（TODO 3.2：对话模块需执行服务端归属校验）。
 *
 * <p>所有接口的归属判定都来自 {@link SecurityUtil#requireUserId()}，
 * 不接受任何由前端传入的用户 ID；读取消息前必须先通过会话归属校验。
 *
 * <p>当前阶段只提供会话与消息的持久化能力，<b>不调用模型</b>：
 * 模型接入属于 TODO 4.2（P1）。因此发送消息只落库用户输入，
 * 不会返回伪造的助手回复。
 */
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /** 会话列表，只返回当前用户自己的会话。 */
    @GetMapping("/sessions")
    public ApiResponse<PageResult<AiSessionVO>> listSessions(@Valid @ModelAttribute AiSessionQueryDTO query) {
        return ApiResponse.success(aiChatService.listSessions(SecurityUtil.requireUserId(), query));
    }

    /**
     * 新建会话。
     *
     * <p>若传入 fileId，服务端会先校验该文件属于当前用户；
     * 传他人文件 ID 会返回「文件不存在」。
     */
    @PostMapping("/sessions")
    public ApiResponse<AiSessionVO> createSession(@Valid @RequestBody CreateAiSessionDTO dto) {
        return ApiResponse.success("会话已创建", aiChatService.createSession(SecurityUtil.requireUserId(), dto));
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<AiSessionVO> sessionDetail(@PathVariable Long sessionId) {
        return ApiResponse.success(aiChatService.sessionDetail(SecurityUtil.requireUserId(), sessionId));
    }

    /** 归档会话；归档后不再接收新消息。 */
    @PostMapping("/sessions/{sessionId}/archive")
    public ApiResponse<AiSessionVO> archiveSession(@PathVariable Long sessionId) {
        return ApiResponse.success("会话已归档", aiChatService.archiveSession(SecurityUtil.requireUserId(), sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        aiChatService.deleteSession(SecurityUtil.requireUserId(), sessionId);
        return ApiResponse.success("会话已删除", null);
    }

    /** 会话内的消息，按时间正序。 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<AiMessageVO>> listMessages(@PathVariable Long sessionId) {
        return ApiResponse.success(aiChatService.listMessages(SecurityUtil.requireUserId(), sessionId));
    }

    /**
     * 发送一条用户消息。
     *
     * <p>注意：当前不会生成助手回复（模型未接入），
     * 前端不要基于本接口展示「AI 已作答」的状态。
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ApiResponse<AiMessageVO> sendMessage(@PathVariable Long sessionId,
                                                @Valid @RequestBody SendAiMessageDTO dto) {
        return ApiResponse.success("消息已记录",
                aiChatService.appendUserMessage(SecurityUtil.requireUserId(), sessionId, dto.getContent()));
    }
}
