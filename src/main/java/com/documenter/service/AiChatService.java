package com.documenter.service;

import com.documenter.dto.AiSessionQueryDTO;
import com.documenter.dto.CreateAiSessionDTO;
import com.documenter.entity.AiSession;
import com.documenter.vo.AiMessageVO;
import com.documenter.vo.AiSessionVO;
import com.documenter.vo.PageResult;

import java.util.List;

/**
 * AI 对话会话与消息（TODO 3.2「对话模块尚未实现；实现后需同样执行服务端归属校验」）。
 *
 * <p>归属校验规则（本模块的核心要求）：
 * <ul>
 *   <li>会话归属以 {@code ai_session.user_id} 为唯一依据，<b>不接受</b>前端传入的用户 ID；</li>
 *   <li>消息本身不存 userId，读取消息前必须先通过会话做归属校验，
 *       因此「拿到别人的 sessionId」也无法读到消息；</li>
 *   <li>他人会话统一按「不存在」处理（404），避免用状态码枚举出哪些会话 ID 真实存在；</li>
 *   <li>会话绑定文件时必须校验该文件属于当前用户，否则会通过会话绕过文件归属校验。</li>
 * </ul>
 *
 * <p>本接口只负责会话与消息的持久化和鉴权；模型回复由 {@link AiReplyService} 提供。
 */
public interface AiChatService {

    PageResult<AiSessionVO> listSessions(Long userId, AiSessionQueryDTO query);

    AiSessionVO createSession(Long userId, CreateAiSessionDTO dto);

    AiSessionVO sessionDetail(Long userId, Long sessionId);

    /** 归档会话；归档后不再接收新消息。 */
    AiSessionVO archiveSession(Long userId, Long sessionId);

    void deleteSession(Long userId, Long sessionId);

    /** 按时间正序读取会话内全部消息。 */
    List<AiMessageVO> listMessages(Long userId, Long sessionId);

    /**
     * 追加一条用户消息。
     *
     * <p>不调用模型，保留旧消息记录接口的语义。
     */
    AiMessageVO appendUserMessage(Long userId, Long sessionId, String content);

    /**
     * 取会话实体并校验归属，供其它模块（任务、工具调用）复用。
     *
     * @throws com.documenter.exception.BusinessException 会话不存在或不属于该用户
     */
    AiSession requireOwnedSession(Long userId, Long sessionId);
}
