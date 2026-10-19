package com.documenter.service.impl;

import com.documenter.component.EditPlanValidator;
import com.documenter.component.ExecutionLeaseService;
import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.entity.AiMessage;
import com.documenter.entity.AiSession;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AiMessageMapper;
import com.documenter.mapper.AiSessionMapper;
import com.documenter.service.*;
import com.documenter.vo.*;
import com.documenter.util.XlsxFormulaTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 模型调用不持有数据库事务；预留消息、保存结果和确认各使用短事务。 */
@Slf4j
@Service
public class AiReplyServiceImpl implements AiReplyService {
    private ExecutionLeaseService leases;

    @org.springframework.beans.factory.annotation.Autowired
    public void setExecutionLeases(ExecutionLeaseService leases) { this.leases = leases; }
    private final AiChatService chats;
    private final AiSessionMapper sessions;
    private final AiMessageMapper messages;
    private final AgentService agent;
    private final DocxDocumentService documents;
    private final XlsxDocumentService spreadsheets;
    private final DocumentVersionService versions;
    private final AiModelProvider provider;
    private final AiModelProperties properties;
    private final EditPlanValidator validator;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();

    public AiReplyServiceImpl(AiChatService chats, AiSessionMapper sessions, AiMessageMapper messages,
                              AgentService agent, DocxDocumentService documents, XlsxDocumentService spreadsheets,
                              DocumentVersionService versions,
                              AiModelProvider provider, AiModelProperties properties, EditPlanValidator validator,
                              AuditService audit, PlatformTransactionManager transactionManager) {
        this.chats = chats;
        this.sessions = sessions;
        this.messages = messages;
        this.agent = agent;
        this.documents = documents;
        this.spreadsheets = spreadsheets;
        this.versions = versions;
        this.provider = provider;
        this.properties = properties;
        this.validator = validator;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public AiReplyVO create(Long userId, Long sessionId, CreateAiReplyDTO request) {
        return createInternal(userId, sessionId, request, ignored -> { }, null, false);
    }

    @Override
    public AiReplyVO createStreaming(Long userId, Long sessionId, CreateAiReplyDTO request,
                                     Consumer<StreamEvent> events, BooleanSupplier cancelled) {
        Consumer<StreamEvent> safeEvents = events == null ? ignored -> { } : events;
        return createInternal(userId, sessionId, request, safeEvents,
                cancelled == null ? () -> false : cancelled, true);
    }

    private AiReplyVO createInternal(Long userId, Long sessionId, CreateAiReplyDTO request,
                                     Consumer<StreamEvent> events, BooleanSupplier cancelled,
                                     boolean streaming) {
        validateRequest(request);
        // 新 HTTP 流程始终要求确认，禁止配置关闭后工具在模型循环内直接写版本。
        if (!properties.isRequireConfirmation()) {
            throw new BusinessException(503, "回复确认流程要求 res.ai.require-confirmation=true");
        }
        Reservation reservation = transactions.execute(status -> reserve(userId, sessionId, request));
        if (!reservation.created()) {
            AiReplyVO existing = view(userId, reservation.session(), reservation.reply());
            events.accept(new StreamEvent(StreamEvent.Type.COMPLETED,
                    reservation.reply().getId(), null));
            return existing;
        }
        try (var registration = leases == null ? ExecutionLeaseService.NOOP
                : leases.trackReply(reservation.reply().getId())) {
            return executeReservedReply(userId, sessionId, request, events, cancelled, streaming, reservation);
        }
    }

    private AiReplyVO executeReservedReply(Long userId, Long sessionId, CreateAiReplyDTO request,
                                          Consumer<StreamEvent> events, BooleanSupplier cancelled,
                                          boolean streaming, Reservation reservation) {
        events.accept(new StreamEvent(StreamEvent.Type.STARTED,
                reservation.reply().getId(), null));

        AgentRunResult result;
        try {
            List<AgentRunResult.Source> requestSources = sources(request);
            if (streaming) {
                result = agent.runConversationStreaming(userId, reservation.session().getFileId(),
                        request.getVersionNo(), request.getContent().trim(), reservation.history(),
                        requestSources, cancelled, event -> {
                            if (event.type() == AgentService.StreamEventType.TEXT_DELTA) {
                                events.accept(new StreamEvent(StreamEvent.Type.TEXT_DELTA,
                                        reservation.reply().getId(), event.text()));
                            }
                        });
            } else {
                result = requestSources.isEmpty()
                        ? agent.runConversation(userId, reservation.session().getFileId(),
                                request.getVersionNo(), request.getContent().trim(),
                                reservation.history(), null)
                        : agent.runConversationWithSources(userId, reservation.session().getFileId(),
                                request.getVersionNo(), request.getContent().trim(),
                                reservation.history(), requestSources, cancelled);
            }
            if (result == null) {
                throw new IllegalStateException("模型没有返回运行结果");
            }
            if (result.hasPendingPlans()) {
                // 各工具单独合法不代表合并后合法（可能重复修改同一目标或超过总上限）。
                try {
                    var context = planContext(userId, reservation.session(), request.getVersionNo());
                    validator.validateForContext(merge(result), context);
                    if (context.xlsxStructure() != null) {
                        spreadsheets.validateAiPlan(userId, context.fileId(), context.versionNo(), merge(result));
                    }
                } catch (BusinessException invalidPlan) {
                    result = new AgentRunResult("本次编辑计划无法整体执行，请调整指令后重试。",
                            result.steps(), List.of(), result.usage(), AgentRunResult.Termination.INVALID_PLAN, false);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("生成 AI 回复失败, sessionId={}, replyId={}", sessionId, reservation.reply().getId(), failure);
            result = new AgentRunResult("本次回复生成失败，请调整指令或稍后使用新的幂等键重试。",
                    List.of(), List.of(), new AgentRunResult.Usage(0, 0, 0, 0,
                    BigDecimal.ZERO, false, 0), AgentRunResult.Termination.MODEL_UNAVAILABLE, false);
        }
        AgentRunResult completed = result;
        AiMessage reply = transactions.execute(status -> {
            if (leases != null) leases.fenceReply(reservation.reply().getId());
            AiSession session = lockedSession(userId, sessionId, false);
            AiMessage current = requireReply(sessionId, reservation.reply().getId());
            if (!"RUNNING".equals(current.getRunStatus())) {
                return current;
            }
            current.setContent(completed.reply());
            current.setRunStatus(completed.termination() == AgentRunResult.Termination.COMPLETED
                    ? "COMPLETED" : "FAILED");
            current.setPlanStatus("COMPLETED".equals(current.getRunStatus()) && completed.hasPendingPlans()
                    ? "PENDING" : "NONE");
            current.setResultJson(json.writeValueAsString(completed));
            String modelName = "stub".equals(provider.providerName()) ? "stub" : properties.getModelName();
            current.setModelName(modelName == null ? provider.providerName() : modelName);
            current.setPromptTokens(completed.usage().inputTokens());
            current.setCompletionTokens(completed.usage().outputTokens());
            current.setDurationMs(completed.usage().elapsedMs());
            update(current);
            touch(session);
            audit.success(AuditAction.AI_REPLY_CREATE, "AI_SESSION", sessionId,
                    "replyId=" + current.getId() + ", runStatus=" + current.getRunStatus());
            return current;
        });
        AiReplyVO completedView = view(userId, reservation.session(), reply);
        events.accept(new StreamEvent(StreamEvent.Type.COMPLETED, reply.getId(), null));
        return completedView;
    }

    private Reservation reserve(Long userId, Long sessionId, CreateAiReplyDTO request) {
        AiSession session = lockedSession(userId, sessionId, true);
        AiMessage existing = messages.selectByRequestKey(sessionId, request.getIdempotencyKey().trim());
        if (existing != null) {
            AiMessage user = messages.selectById(existing.getUserMessageId());
            if (user == null || !request.getContent().trim().equals(user.getContent())
                    || !Objects.equals(request.getVersionNo(), existing.getSourceVersion())) {
                throw new BusinessException(409, "幂等键已用于不同的消息或版本");
            }
            return new Reservation(session, existing, List.of(), false);
        }
        if (messages.selectRunningReply(sessionId) != null) {
            throw new BusinessException(409, "会话已有回复正在生成，请等待完成后再发送");
        }
        if (session.getFileId() == null) {
            if (request.getVersionNo() != null) {
                throw new BusinessException(400, "未绑定文件的会话不能指定文档版本");
            }
        } else {
            if (request.getVersionNo() == null) {
                throw new BusinessException(400, "绑定文件的会话必须指定明确版本号");
            }
            FileDownload source = versions.downloadVersion(userId, session.getFileId(), request.getVersionNo());
            if (!"application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    .equalsIgnoreCase(source.contentType())
                    && !"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(source.contentType())) {
                throw new BusinessException(400, "当前回复编辑流程仅支持 DOCX 和 XLSX");
            }
        }
        List<AgentService.ConversationTurn> history = history(sessionId);
        AiMessage user = new AiMessage();
        user.setSessionId(sessionId);
        user.setRole("USER");
        user.setContent(request.getContent().trim());
        insert(user);
        AiMessage reply = new AiMessage();
        reply.setSessionId(sessionId);
        reply.setRole("ASSISTANT");
        reply.setContent("正在生成回复");
        reply.setRequestKey(request.getIdempotencyKey().trim());
        reply.setUserMessageId(user.getId());
        reply.setSourceVersion(request.getVersionNo());
        reply.setRunStatus("RUNNING");
        reply.setPlanStatus("NONE");
        insert(reply);
        if (leases != null) {
            leases.claimReply(reply.getId());
            leases.fenceReply(reply.getId());
        }
        touch(session);
        return new Reservation(session, reply, history, true);
    }

    @Override
    public AiReplyVO get(Long userId, Long sessionId, Long messageId) {
        AiSession session = chats.requireOwnedSession(userId, sessionId);
        return view(userId, session, requireReply(sessionId, messageId));
    }

    @Override
    public AiReplyVO confirm(Long userId, Long sessionId, Long messageId) {
        return transactions.execute(status -> {
            AiSession session = lockedSession(userId, sessionId, true);
            AiMessage reply = requireReply(sessionId, messageId);
            if ("CONFIRMED".equals(reply.getPlanStatus())) {
                return view(userId, session, reply);
            }
            requirePending(reply);
            if (session.getFileId() == null || reply.getSourceVersion() == null) {
                throw new BusinessException(409, "本次回复没有绑定可编辑的文档版本");
            }
            AiMessage user = messages.selectById(reply.getUserMessageId());
            if (user == null) {
                throw new BusinessException(409, "原始用户消息已不存在");
            }
            var executed = agent.executePlan(userId, session.getFileId(), reply.getSourceVersion(),
                    merge(result(reply)), user.getContent());
            reply.setOutputVersion(executed.version().getVersionNo());
            reply.setPlanStatus("CONFIRMED");
            update(reply);
            audit.success(AuditAction.AI_REPLY_CONFIRM, "AI_SESSION", sessionId,
                    "replyId=" + messageId + ", outputVersion=" + reply.getOutputVersion());
            return view(userId, session, reply);
        });
    }

    @Override
    public AiReplyVO discard(Long userId, Long sessionId, Long messageId) {
        return transactions.execute(status -> {
            AiSession session = lockedSession(userId, sessionId, true);
            AiMessage reply = requireReply(sessionId, messageId);
            if ("DISCARDED".equals(reply.getPlanStatus())) {
                return view(userId, session, reply);
            }
            requirePending(reply);
            reply.setPlanStatus("DISCARDED");
            update(reply);
            audit.success(AuditAction.AI_REPLY_DISCARD, "AI_SESSION", sessionId, "replyId=" + messageId);
            return view(userId, session, reply);
        });
    }

    private AiReplyVO view(Long userId, AiSession session, AiMessage reply) {
        AgentRunResult result = result(reply);
        List<AiReplyVO.PlannedChange> changes = new ArrayList<>();
        if (result != null && result.hasPendingPlans() && "PENDING".equals(reply.getPlanStatus())) {
            var context = planContext(userId, session, reply.getSourceVersion());
            validator.validateForContext(merge(result), context);
            if (context.xlsxStructure() != null) {
                var before = spreadsheets.validateAiPlan(
                        userId, session.getFileId(), reply.getSourceVersion(), merge(result));
                for (var operation : merge(result).operations()) {
                    if (operation.operationType() == DocumentEditPlan.OperationType.SET_FORMULA) {
                        var target = XlsxFormulaTarget.parse(operation.target());
                        var cell = before.get(target.key());
                        changes.add(new AiReplyVO.PlannedChange(operation.type(), operation.target(),
                                cell == null || "BLANK".equals(cell.type()) ? null
                                        : cell.formula() == null ? cell.value() : "=" + cell.formula(),
                                "=" + operation.content().replaceFirst("^=", ""), operation.reason()));
                    } else {
                        changes.add(new AiReplyVO.PlannedChange(operation.type(), operation.target(),
                                null, xlsxPlanPreview(operation), operation.reason()));
                    }
                }
                return new AiReplyVO(AiMessageVO.from(reply), reply.getRunStatus(), reply.getPlanStatus(),
                        reply.getSourceVersion(), reply.getOutputVersion(), result, List.copyOf(changes));
            }
            Map<String, DocxStructureVO.Block> before = new HashMap<>();
            collectBlocks(context.docxStructure().blocks(), before);
            for (DocumentEditPlan.Operation operation : merge(result).operations()) {
                var type = operation.operationType();
                if (type == DocumentEditPlan.OperationType.NORMALIZE_DOCX_FORMAT
                        || type == DocumentEditPlan.OperationType.RESIZE_DOCX_IMAGES) {
                    int count = com.documenter.util.DocxNormalizationSpec.validateContext(operation, context);
                    changes.add(new AiReplyVO.PlannedChange(operation.type(), operation.target(),
                            "源版本中匹配 " + count + (type == DocumentEditPlan.OperationType.RESIZE_DOCX_IMAGES ? " 张正文图片" : " 个正文段落"),
                            operation.content(), operation.reason()));
                    continue;
                }
                boolean insert = type == DocumentEditPlan.OperationType.INSERT_BEFORE
                        || type == DocumentEditPlan.OperationType.INSERT_AFTER
                        || type == DocumentEditPlan.OperationType.INSERT_IMAGE;
                boolean formatting = type == DocumentEditPlan.OperationType.FORMAT_PARAGRAPH;
                var block = before.get(operation.target());
                changes.add(new AiReplyVO.PlannedChange(operation.type(), operation.target(),
                        insert || formatting || block == null ? null : block.text(),
                        type == DocumentEditPlan.OperationType.DELETE || formatting ? null : operation.content(),
                        operation.reason(), operation.image(), operation.format(),
                        formatting && block != null ? new AiReplyVO.ParagraphStyle(
                                block.styleId(), block.headingLevel(), block.numberingFormat(), block.pageBreakBefore()) : null));
            }
        }
        return new AiReplyVO(AiMessageVO.from(reply), reply.getRunStatus(), reply.getPlanStatus(),
                reply.getSourceVersion(), reply.getOutputVersion(), result, List.copyOf(changes));
    }

    private ToolExecutionContext planContext(Long userId, AiSession session, Integer versionNo) {
        if (session.getFileId() == null) return ToolExecutionContext.withoutDocument(userId);
        var source = versions.downloadVersion(userId, session.getFileId(), versionNo);
        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(source.contentType())) {
            return ToolExecutionContext.forVersion(userId, session.getFileId(), versionNo, "XLSX", null,
                    spreadsheets.readStructure(userId, session.getFileId(), versionNo));
        }
        return ToolExecutionContext.forVersion(userId, session.getFileId(), versionNo, "DOCX",
                documents.readStructure(userId, session.getFileId(), versionNo), null);
    }

    private static void collectBlocks(List<DocxStructureVO.Block> blocks, Map<String, DocxStructureVO.Block> target) {
        for (var block : blocks) {
            target.put(block.blockId(), block);
            collectBlocks(block.children(), target);
        }
    }

    private List<AgentService.ConversationTurn> history(Long sessionId) {
        List<AgentService.ConversationTurn> history = new ArrayList<>();
        int chars = 0;
        for (AiMessage message : messages.selectRecentConversation(sessionId)) {
            if (message.getContent() == null) continue;
            chars += message.getContent().length();
            if (chars > 12000) break;
            history.add(new AgentService.ConversationTurn(message.getRole(), message.getContent()));
        }
        Collections.reverse(history);
        return List.copyOf(history);
    }

    private AiSession lockedSession(Long userId, Long sessionId, boolean active) {
        AiSession session = sessions.selectOwnedForUpdate(userId, sessionId);
        if (session == null) throw new BusinessException(404, "会话不存在");
        if (active && !session.isActive()) throw new BusinessException(409, "会话已归档，不能继续操作");
        return session;
    }

    private AiMessage requireReply(Long sessionId, Long messageId) {
        AiMessage reply = messages.selectById(messageId);
        if (reply == null || !sessionId.equals(reply.getSessionId())
                || !"ASSISTANT".equals(reply.getRole()) || reply.getRequestKey() == null) {
            throw new BusinessException(404, "回复不存在");
        }
        return reply;
    }

    private static void requirePending(AiMessage reply) {
        if (!"COMPLETED".equals(reply.getRunStatus()) || !"PENDING".equals(reply.getPlanStatus())) {
            throw new BusinessException(409, "本次回复没有可确认或可放弃的待处理计划");
        }
    }

    private AgentRunResult result(AiMessage reply) {
        if (reply.getResultJson() == null) return null;
        try {
            return json.readValue(reply.getResultJson(), AgentRunResult.class);
        } catch (RuntimeException failure) {
            throw new BusinessException(500, "已保存的回复结果无法读取");
        }
    }

    private String xlsxPlanPreview(DocumentEditPlan.Operation operation) {
        try {
            var content = json.readTree(operation.content());
            if (operation.operationType() == DocumentEditPlan.OperationType.CREATE_TABLE) {
                int columns = content.path("headers").size();
                int rows = content.path("rows").size();
                var chart = content.path("chart");
                String chartText = chart.isObject()
                        ? "，并生成 " + chart.path("type").asText() + " 图表" : "";
                return "创建 " + columns + " 列 × " + rows + " 行表格" + chartText;
            }
            if (operation.operationType() == DocumentEditPlan.OperationType.CREATE_CHART) {
                return "生成 " + content.path("type").asText() + " 图表："
                        + content.path("categoriesRange").asText() + " -> "
                        + content.path("valuesRange").asText();
            }
            return "XLSX 操作";
        } catch (RuntimeException invalid) {
            return "XLSX 操作";
        }
    }

    private static DocumentEditPlan merge(AgentRunResult result) {
        if (result == null) throw new BusinessException(409, "回复没有可执行计划");
        var operations = result.plans().stream().flatMap(plan -> plan.operations().stream()).toList();
        return new DocumentEditPlan("AI 编辑 " + operations.size() + " 处", operations, null);
    }

    private void insert(AiMessage message) {
        if (messages.insert(message) != 1 || message.getId() == null) {
            throw new BusinessException(500, "消息保存失败");
        }
    }

    private void update(AiMessage message) {
        if (messages.updateById(message) != 1) throw new BusinessException(409, "回复状态已发生变化，请重新查询");
    }

    private void touch(AiSession session) {
        AiSession update = new AiSession();
        update.setId(session.getId());
        update.setUpdateTime(LocalDateTime.now());
        if (sessions.updateById(update) != 1) throw new BusinessException(409, "会话状态已发生变化");
    }

    private static void validateRequest(CreateAiReplyDTO request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()
                || request.getContent().length() > 2000 || request.getIdempotencyKey() == null
                || request.getIdempotencyKey().isBlank() || request.getIdempotencyKey().length() > 128
                || (request.getVersionNo() != null && request.getVersionNo() < 1)
                || (request.getSources() != null && request.getSources().size() > 5)) {
            throw new BusinessException(400, "消息、版本号或幂等键不合法");
        }
        if (request.getSources() != null) {
            for (var source : request.getSources()) {
                if (source == null || source.getTitle() == null || source.getTitle().isBlank()
                        || source.getTitle().length() > 200
                        || source.getUrl() != null && source.getUrl().length() > 1000
                        || source.getExcerpt() != null && source.getExcerpt().length() > 2000) {
                    throw new BusinessException(400, "资料来源字段不合法");
                }
            }
        }
    }

    private static List<AgentRunResult.Source> sources(CreateAiReplyDTO request) {
        if (request.getSources() == null || request.getSources().isEmpty()) return List.of();
        return request.getSources().stream()
                .map(source -> new AgentRunResult.Source(source.getTitle(), source.getUrl(), source.getExcerpt()))
                .toList();
    }

    private record Reservation(AiSession session, AiMessage reply,
                                List<AgentService.ConversationTurn> history, boolean created) { }
}
