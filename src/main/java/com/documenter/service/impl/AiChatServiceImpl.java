package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.dto.AiSessionQueryDTO;
import com.documenter.dto.CreateAiSessionDTO;
import com.documenter.entity.AiMessage;
import com.documenter.entity.AiSession;
import com.documenter.entity.FileAsset;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AiMessageMapper;
import com.documenter.mapper.AiSessionMapper;
import com.documenter.service.AiChatService;
import com.documenter.service.AuditService;
import com.documenter.service.FileService;
import com.documenter.vo.AiMessageVO;
import com.documenter.vo.AiSessionVO;
import com.documenter.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 对话会话与消息实现（TODO 3.2）。
 *
 * <p>归属校验全部集中在 {@link #requireOwnedSession(Long, Long)}：
 * 所有读写入口都先过这一关，因此不存在「某个方法忘了校验」的漏网之鱼。
 * 这是刻意的设计——把鉴权点收敛到一处比在每个方法里各写一遍更不容易出错。
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String ROLE_USER = "USER";
    private static final int MAX_TITLE_LENGTH = 128;

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final FileService fileService;
    private final AuditService auditService;

    public AiChatServiceImpl(AiSessionMapper sessionMapper, AiMessageMapper messageMapper,
                             FileService fileService, AuditService auditService) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.fileService = fileService;
        this.auditService = auditService;
    }

    @Override
    public PageResult<AiSessionVO> listSessions(Long userId, AiSessionQueryDTO query) {
        String status = trimToNull(query.getStatus());
        String keyword = trimToNull(query.getKeyword());
        long offset = (query.getPage() - 1) * query.getSize();

        // 条件里始终带 user_id：即使前端传了别人的 fileId，也只会命中自己的会话
        Long total = sessionMapper.selectCount(Wrappers.<AiSession>lambdaQuery()
                .eq(AiSession::getUserId, userId)
                .eq(status != null, AiSession::getStatus, status)
                .eq(query.getFileId() != null, AiSession::getFileId, query.getFileId())
                .like(keyword != null, AiSession::getTitle, keyword));

        List<AiSession> records = sessionMapper.selectList(Wrappers.<AiSession>lambdaQuery()
                .eq(AiSession::getUserId, userId)
                .eq(status != null, AiSession::getStatus, status)
                .eq(query.getFileId() != null, AiSession::getFileId, query.getFileId())
                .like(keyword != null, AiSession::getTitle, keyword)
                .orderByDesc(AiSession::getUpdateTime)
                .last("LIMIT " + query.getSize() + " OFFSET " + offset));

        PageResult<AiSessionVO> result = new PageResult<>();
        result.setRecords(records.stream().map(AiSessionVO::from).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public AiSessionVO createSession(Long userId, CreateAiSessionDTO dto) {
        Long fileId = null;
        if (dto.getFileId() != null) {
            // 绑定文件前必须校验归属：否则可以建一个「挂在自己名下、指向他人文档」的会话，
            // 后续工具调用就会绕过文件归属校验去读别人的文档
            FileAsset asset = fileService.requireOwned(userId, dto.getFileId());
            fileId = asset.getId();
        }

        AiSession session = new AiSession();
        session.setUserId(userId);
        session.setFileId(fileId);
        session.setTitle(resolveTitle(dto.getTitle()));
        session.setStatus(STATUS_ACTIVE);
        sessionMapper.insert(session);

        if (session.getId() == null) {
            throw new BusinessException(500, "创建会话失败，请稍后重试");
        }
        auditService.success(AuditAction.AI_SESSION_CREATE, "AI_SESSION", session.getId(),
                "新建会话" + (fileId == null ? "（未绑定文件）" : "（绑定文件 " + fileId + "）"));
        return AiSessionVO.from(requireOwnedSession(userId, session.getId()));
    }

    @Override
    public AiSessionVO sessionDetail(Long userId, Long sessionId) {
        return AiSessionVO.from(requireOwnedSession(userId, sessionId));
    }

    @Override
    public AiSessionVO archiveSession(Long userId, Long sessionId) {
        AiSession session = requireOwnedSession(userId, sessionId);
        if (STATUS_ARCHIVED.equals(session.getStatus())) {
            // 幂等：已归档再归档直接返回
            return AiSessionVO.from(session);
        }
        AiSession update = new AiSession();
        update.setId(session.getId());
        update.setStatus(STATUS_ARCHIVED);
        sessionMapper.updateById(update);
        return AiSessionVO.from(requireOwnedSession(userId, sessionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, Long sessionId) {
        AiSession session = requireOwnedSession(userId, sessionId);
        // 消息先删：外键关系由应用层维护，避免留下指向已删会话的孤儿消息
        messageMapper.delete(Wrappers.<AiMessage>lambdaQuery().eq(AiMessage::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
        auditService.success(AuditAction.AI_SESSION_DELETE, "AI_SESSION", sessionId,
                "删除会话 code=" + session.getTitle());
    }

    @Override
    public List<AiMessageVO> listMessages(Long userId, Long sessionId) {
        // 先鉴权再读消息：消息表不存 userId，因此这一步是唯一的归属关卡
        requireOwnedSession(userId, sessionId);
        return messageMapper.selectBySession(sessionId).stream()
                .map(AiMessageVO::from)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiMessageVO appendUserMessage(Long userId, Long sessionId, String content) {
        AiSession session = requireOwnedSession(userId, sessionId);
        if (!STATUS_ACTIVE.equals(session.getStatus())) {
            throw new BusinessException(409, "会话已归档，不能继续发送消息");
        }

        AiMessage message = new AiMessage();
        message.setSessionId(session.getId());
        message.setRole(ROLE_USER);
        message.setContent(content);
        messageMapper.insert(message);

        if (message.getId() == null) {
            throw new BusinessException(500, "消息保存失败，请稍后重试");
        }

        // 更新会话的 update_time，让列表按最近活跃排序
        AiSession touch = new AiSession();
        touch.setId(session.getId());
        touch.setUpdateTime(java.time.LocalDateTime.now());
        sessionMapper.updateById(touch);

        auditService.success(AuditAction.AI_MESSAGE_SEND, "AI_SESSION", session.getId(),
                "发送用户消息，长度=" + content.length());
        return AiMessageVO.from(message);
    }

    @Override
    public AiSession requireOwnedSession(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            throw new BusinessException(400, "会话 ID 不能为空");
        }
        AiSession session = sessionMapper.selectById(sessionId);
        // 他人会话统一按「不存在」处理：返回 403 会暴露「该 ID 真实存在」
        if (session == null || !userId.equals(session.getUserId())) {
            throw new BusinessException(404, "会话不存在");
        }
        return session;
    }

    /** 未提供标题时用占位标题，避免前端列表出现空白项。 */
    private static String resolveTitle(String rawTitle) {
        String title = trimToNull(rawTitle);
        if (title == null) {
            return "新会话";
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
