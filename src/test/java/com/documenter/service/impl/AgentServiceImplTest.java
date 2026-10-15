package com.documenter.service.impl;

import com.documenter.component.EditPlanValidator;
import com.documenter.component.StubModelProvider;
import com.documenter.configuration.AiModelProperties;
import com.documenter.entity.FileAsset;
import com.documenter.service.AiTool;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.AgentRunResult;
import com.documenter.vo.AgentRunResult.StepKind;
import com.documenter.vo.AgentRunResult.Termination;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能体循环测试（TODO 4.2「建立分析需求 → 选择工具 → 执行 → 检查结果的循环，
 * 限制步骤数、调用次数和重试次数」）。
 *
 * <p><b>为什么用桩模型而不是 mock 掉模型调用</b>：本类要验证的正是
 * 「模型要求调用工具之后会发生什么」——工具是否真的被执行、
 * 结果是否回填、计划是否被收集、上限是否被强制。
 * 如果直接 mock 掉整个模型交互，这些逻辑就完全没被覆盖。
 * 桩模型实现完整的 {@code ChatModel} 接口，因此循环走的是真实代码路径。
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class AgentServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 10L;
    private static final Integer VERSION_NO = 1;

    private AiModelProperties properties;
    private DocxDocumentService docxService;
    private XlsxDocumentService xlsxService;
    private DocumentVersionService versionService;

    @BeforeEach
    void setUp() {
        properties = new AiModelProperties();
        properties.setEnabled(true);
        properties.setMaxSteps(5);
        properties.setMaxToolCalls(6);
        properties.setMaxSameToolRepeats(2);
        properties.setMaxModelRetries(0);

        docxService = mock(DocxDocumentService.class);
        xlsxService = mock(XlsxDocumentService.class);
        versionService = mock(DocumentVersionService.class);
    }

    /** 用给定的工具集合构造循环。 */
    private AgentServiceImpl agentWith(List<AiTool> tools) {
        StubModelProvider stub = new StubModelProvider(properties);
        return new AgentServiceImpl(properties, stub, docxService, xlsxService,
                versionService, new EditPlanValidator(properties),
                new com.documenter.component.EditPlanExecutor(docxService), tools);
    }

    // ============================================ 端到端循环

    @Test
    void loopExecutesToolAndCollectsPlan() {
        stubDocxVersion();
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        // 桩模型的约定格式：[stub-tool] <工具名> <JSON 参数>
        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                        + "\"target\":\"body/p1\",\"content\":\"新内容\"}",
                List.of(), null);

        // 工具确实被执行了，并且产出的计划被收集
        assertEquals(Termination.COMPLETED, result.termination());
        assertTrue(result.hasPendingPlans(), "应收集到待确认的修改计划");
        assertEquals(1, result.plans().size());
        assertEquals(1, result.plans().get(0).operations().size());
        assertEquals("body/p1", result.plans().get(0).operations().get(0).target());
        assertEquals("新内容", result.plans().get(0).operations().get(0).content());

        // 过程被记录下来，用户能看到 AI 做了什么
        assertTrue(result.steps().stream().anyMatch(step -> step.kind() == StepKind.TOOL_CALL),
                "应记录工具调用步骤");
        assertTrue(result.steps().stream().anyMatch(step -> step.kind() == StepKind.MODEL_CALL),
                "应记录模型调用步骤");
    }

    @Test
    void unknownToolIsReportedBackToModelInsteadOfFailing() {
        stubDocxVersion();
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] delete_everything {}", List.of(), null);

        // 模型编造工具名是常见情况：应作为失败步骤回填，让模型自我纠正，
        // 而不是让整个请求失败
        assertEquals(Termination.COMPLETED, result.termination());
        assertTrue(result.steps().stream().anyMatch(step -> step.kind() == StepKind.FAILURE),
                "应记录未知工具的失败步骤");
        assertFalse(result.hasPendingPlans());
    }

    // ============================================ 上限强制

    @Test
    void maxStepsIsEnforced() {
        stubDocxVersion();
        // maxSteps=1：第一步模型要求调工具，没有机会走第二步
        properties.setMaxSteps(1);
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                        + "\"target\":\"body/p1\",\"content\":\"x\"}",
                List.of(), null);

        assertEquals(Termination.MAX_STEPS, result.termination());
        assertTrue(result.truncated(), "被上限截断时必须标记，否则用户以为已经做完");
    }

    @Test
    void repeatedSameToolIsBlocked() {
        stubDocxVersion();
        properties.setMaxSameToolRepeats(1);
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                        + "\"target\":\"body/p1\",\"content\":\"x\"}",
                List.of(), null);

        // 同一个工具超过重复上限后应被阻止，避免模型原地打转消耗额度
        assertTrue(result.usage().toolCalls() <= 1 || result.steps().stream()
                        .anyMatch(step -> step.detail() != null && step.detail().contains("重复调用")),
                "应阻止同一工具被反复调用");
    }

    // ============================================ 版本与鉴权

    @Test
    void editingWithoutVersionIsRejected() {
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, null, "改一下", List.of(), null);

        // 不允许「用最新版本」：计划最终要落到某个确定版本上
        assertEquals(Termination.CANCELLED, result.termination());
        assertFalse(result.hasPendingPlans());
        verify(versionService, never()).downloadVersion(USER_ID, FILE_ID, null);
    }

    @Test
    void ownershipIsCheckedWhenPreparingContext() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenThrow(new com.documenter.exception.BusinessException(404, "版本不存在"));
        AgentServiceImpl agent = agentWith(List.of());

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO, "改一下", List.of(), null);

        // 文档读不到时不进入循环：否则模型会对着不存在的文档编造编辑计划
        assertEquals(Termination.CANCELLED, result.termination());
        assertFalse(result.hasPendingPlans());
    }

    @Test
    void unsupportedFormatIsRejectedBeforeCallingModel() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf")));
        AgentServiceImpl agent = agentWith(List.of());

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO, "改一下", List.of(), null);

        assertTrue(result.reply().contains("仅支持"), "应说明支持的格式，实际: " + result.reply());
    }

    // ============================================ 取消

    @Test
    void cancellationStopsTheLoop() {
        stubDocxVersion();
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                        + "\"target\":\"body/p1\",\"content\":\"x\"}",
                List.of(), () -> true);

        assertEquals(Termination.CANCELLED, result.termination());
    }

    // ============================================ 用量统计

    @Test
    void usageIsRecorded() {
        stubDocxVersion();
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO,
                "[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                        + "\"target\":\"body/p1\",\"content\":\"x\"}",
                List.of(), null);

        var usage = result.usage();
        assertTrue(usage.modelCalls() >= 1, "应记录模型调用次数");
        assertTrue(usage.toolCalls() >= 1, "应记录工具调用次数");
        assertTrue(usage.inputTokens() > 0, "应累计输入 token");
        assertTrue(usage.outputTokens() > 0, "应累计输出 token");
        // 未配置单价时不能报告出「成本 0 元」这种误导性结论
        assertFalse(usage.costConfigured(),
                "未配置单价时必须标记 costConfigured=false，避免显示成免费");
        assertEquals(0, usage.estimatedCost().compareTo(java.math.BigDecimal.ZERO));
    }

    @Test
    void costIsComputedWhenUnitPriceConfigured() {
        stubDocxVersion();
        properties.setCostPer1kInputTokens(0.001);
        properties.setCostPer1kOutputTokens(0.002);
        AgentServiceImpl agent = agentWith(List.of(new com.documenter.component.tool.EditParagraphTool()));

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO, "你好", List.of(), null);

        assertTrue(result.usage().costConfigured());
        assertTrue(result.usage().estimatedCost().signum() >= 0);
    }

    // ============================================ 未就绪

    @Test
    void unavailableModelReturnsHintInsteadOfThrowing() {
        AiModelProperties disabled = new AiModelProperties();
        disabled.setEnabled(false);
        // 用一个永远不可用的提供者
        var provider = new com.documenter.service.AiModelProvider() {
            @Override
            public String providerName() {
                return "none";
            }

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String configurationHint() {
                return "请配置 res.ai.base-url";
            }

            @Override
            public dev.langchain4j.model.chat.ChatModel chatModel() {
                throw new IllegalStateException("不应被调用");
            }
        };
        AgentServiceImpl agent = new AgentServiceImpl(disabled, provider, docxService, xlsxService,
                versionService, new EditPlanValidator(disabled),
                new com.documenter.component.EditPlanExecutor(docxService), List.of());

        AgentRunResult result = agent.run(USER_ID, FILE_ID, VERSION_NO, "改一下", List.of(), null);

        // 未就绪时返回明确说明，让调用方展示给用户，而不是变成 500
        assertEquals(Termination.MODEL_UNAVAILABLE, result.termination());
        assertTrue(result.reply().contains("res.ai"));
    }

    // ============================================ 夹具

    private void stubDocxVersion() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_TYPE)));
        when(docxService.readStructure(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(new DocxStructureVO(FILE_ID, VERSION_NO, List.of(
                        paragraph("body/p0", "第一段"),
                        paragraph("body/p1", "第二段"))));
    }

    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static DocxStructureVO.Block paragraph(String id, String text) {
        return new DocxStructureVO.Block(id, "PARAGRAPH", text, null, null, null, null, null, List.of());
    }

    private static FileAsset asset(String extension, String contentType) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setExtension(extension);
        asset.setContentType(contentType);
        asset.setDisplayName("文档." + extension);
        asset.setSha256("f".repeat(64));
        asset.setLatestVersion(VERSION_NO);
        return asset;
    }

    private static FileDownload download(FileAsset asset) {
        byte[] content = new byte[]{1, 2, 3};
        return FileDownload.of(asset, new ByteArrayInputStream(content), content.length);
    }
}
