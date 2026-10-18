package com.documenter.service.impl;

import com.documenter.component.EditPlanExecutor;
import com.documenter.component.EditPlanValidator;
import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.CreateAiReplyDTO;
import com.documenter.dto.AiSourceDTO;
import com.documenter.entity.AiMessage;
import com.documenter.entity.AiSession;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AiMessageMapper;
import com.documenter.mapper.AiSessionMapper;
import com.documenter.service.*;
import com.documenter.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiReplyServiceImplTest {
    private final AiChatService chats = mock(AiChatService.class);
    private final AiSessionMapper sessions = mock(AiSessionMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final AgentService agent = mock(AgentService.class);
    private final DocxDocumentService documents = mock(DocxDocumentService.class);
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final AiModelProvider provider = mock(AiModelProvider.class);
    private final AiModelProperties properties = new AiModelProperties();
    private final AuditService audit = mock(AuditService.class);
    private final Map<Long, AiMessage> store = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final AiSession session = new AiSession();
    private final TestTransactions transactions = new TestTransactions();
    private AiReplyServiceImpl service;

    @BeforeEach
    void setUp() {
        session.setId(1L);
        session.setUserId(7L);
        session.setFileId(10L);
        session.setStatus("ACTIVE");
        when(sessions.selectOwnedForUpdate(7L, 1L)).thenReturn(session);
        when(chats.requireOwnedSession(7L, 1L)).thenReturn(session);
        when(sessions.updateById(any(AiSession.class))).thenReturn(1);
        when(provider.providerName()).thenReturn("stub");
        when(messages.selectRecentConversation(1L)).thenReturn(List.of());
        when(messages.selectById(anyLong())).thenAnswer(invocation -> copy(store.get(invocation.getArgument(0))));
        when(messages.selectByRequestKey(eq(1L), anyString())).thenAnswer(invocation ->
                store.values().stream().filter(m -> invocation.getArgument(1).equals(m.getRequestKey()))
                        .findFirst().map(this::copy).orElse(null));
        AtomicLong ids = new AtomicLong(100);
        when(messages.insert(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            message.setId(ids.incrementAndGet());
            store.put(message.getId(), copy(message));
            return 1;
        });
        when(messages.updateById(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            store.put(message.getId(), copy(message));
            return 1;
        });
        var asset = new FileAsset();
        asset.setId(10L);
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(new byte[0]), 0,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "test.docx"));
        var block = new DocxStructureVO.Block("body/p0", "PARAGRAPH", "old text", null,
                null, null, null, null, List.of());
        when(documents.readStructure(7L, 10L, 1)).thenReturn(new DocxStructureVO(10L, 1, List.of(block)));
        when(agent.runConversation(eq(7L), eq(10L), eq(1), eq("edit"), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    assertFalse(transactions.lock.isHeldByCurrentThread(), "模型调用不能持有会话事务锁");
                    return result(true);
                });
        var version = new VersionVO();
        version.setVersionNo(2);
        when(agent.executePlan(eq(7L), eq(10L), eq(1), any(), eq("edit")))
                .thenReturn(new EditPlanExecutor.ExecutionResult(version, List.of("edited")));
        service = new AiReplyServiceImpl(chats, sessions, messages, agent, documents, mock(XlsxDocumentService.class), versions,
                provider, properties, new EditPlanValidator(properties), audit, transactions);
    }

    @Test
    void stubFormulaReplyPreviewsExistingFormulaAndConfirmsExactlyOnce() throws Exception {
        verifyFormulaWorkflow(false);
    }

    @Test
    void stubFormulaReplyCanTargetBlankCellInExistingSheet() throws Exception {
        verifyFormulaWorkflow(true);
    }

    private void verifyFormulaWorkflow(boolean blank) throws Exception {
        var workflow = formulaWorkflow(blank, "SUM(A2:A3)");
        var pending = workflow.service().create(7L, 1L, workflow.request());
        assertEquals("PENDING", pending.planStatus());
        var change = pending.changes().getFirst();
        assertEquals("SET_FORMULA", change.type());
        assertEquals(blank ? "数据!C4" : "数据!C2", change.target());
        assertEquals(blank ? null : "=A2*2", change.before());
        assertEquals("=SUM(A2:A3)", change.after());
        assertEquals("汇总金额", change.reason());
        assertEquals(change, workflow.service().get(7L, 1L, pending.message().getId()).changes().getFirst());
        assertEquals(pending.message().getId(), workflow.service().create(7L, 1L, workflow.request()).message().getId());
        verifyNoInteractions(workflow.storage(), workflow.writer());

        var confirmed = workflow.service().confirm(7L, 1L, pending.message().getId());
        assertEquals(2, confirmed.outputVersion());
        assertEquals(2, workflow.service().confirm(7L, 1L, pending.message().getId()).outputVersion());
        var bytes = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(workflow.storage()).store(bytes.capture(), eq("xlsx"));
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes.getValue()))) {
            var cell = workbook.getSheet("数据").getRow(blank ? 3 : 1).getCell(2);
            assertEquals("SUM(A2:A3)", cell.getCellFormula());
            assertEquals(20, workbook.getCreationHelper().createFormulaEvaluator().evaluate(cell).getNumberValue());
            if (!blank) assertTrue(cell.getCellStyle().getWrapText());
        }
        verify(workflow.writer()).appendVersion(eq(10L), eq(7L), any(), eq(com.documenter.enums.ChangeType.AI_EDIT),
                eq(workflow.request().getContent()), any(), eq(1));
    }

    @Test
    void discardedFormulaPlanNeverWritesAndCannotBeConfirmed() throws Exception {
        var workflow = formulaWorkflow(false, "SUM(A2:A3)");
        var pending = workflow.service().create(7L, 1L, workflow.request());
        assertEquals("DISCARDED", workflow.service().discard(7L, 1L, pending.message().getId()).planStatus());
        assertCode(409, () -> workflow.service().confirm(7L, 1L, pending.message().getId()));
        verifyNoInteractions(workflow.storage(), workflow.writer());
    }

    @Test
    void staleFormulaConfirmationRetainsPendingPlanAndCleansOutput() throws Exception {
        var workflow = formulaWorkflow(false, "SUM(A2:A3)");
        var pending = workflow.service().create(7L, 1L, workflow.request());
        when(workflow.writer().appendVersion(eq(10L), eq(7L), any(), eq(com.documenter.enums.ChangeType.AI_EDIT),
                any(), any(), eq(1))).thenThrow(new BusinessException(409, "版本冲突"));
        assertCode(409, () -> workflow.service().confirm(7L, 1L, pending.message().getId()));
        assertEquals("PENDING", store.get(pending.message().getId()).getPlanStatus());
        assertNull(store.get(pending.message().getId()).getOutputVersion());
        verify(workflow.storage()).delete("ai-formula.xlsx");
    }

    @Test
    void invalidFormulaIsReportedAsToolFailureWithoutAConfirmablePlan() throws Exception {
        var workflow = formulaWorkflow(false, "Missing!A1");
        var reply = workflow.service().create(7L, 1L, workflow.request());
        assertEquals("NONE", reply.planStatus());
        assertTrue(reply.changes().isEmpty());
        assertTrue(reply.result().steps().stream().anyMatch(step -> step.kind() == AgentRunResult.StepKind.FAILURE));
        assertCode(409, () -> workflow.service().confirm(7L, 1L, reply.message().getId()));
        verifyNoInteractions(workflow.storage(), workflow.writer());
    }

    @Test
    void tableAndChartReplyPreviewAndConfirmIntoOneWorkbookVersion() throws Exception {
        var workflow = tableChartWorkflow();

        var pending = workflow.service().create(7L, 1L, workflow.request());

        assertEquals("PENDING", pending.planStatus());
        assertEquals("CREATE_TABLE", pending.changes().getFirst().type());
        assertNull(pending.changes().getFirst().before());
        assertTrue(pending.changes().getFirst().after().contains("2 列 × 2 行"));
        assertTrue(pending.changes().getFirst().after().contains("BAR"));
        verifyNoInteractions(workflow.storage(), workflow.writer());

        var confirmed = workflow.service().confirm(7L, 1L, pending.message().getId());
        assertEquals(2, confirmed.outputVersion());
        var content = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(workflow.storage()).store(content.capture(), eq("xlsx"));
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(content.getValue()))) {
            var sheet = workbook.getSheet("汇总");
            assertEquals("产品 A", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(120, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(1, sheet.getDrawingPatriarch().getCharts().size());
        }
    }

    private FormulaWorkflow formulaWorkflow(boolean blank, String formula) throws Exception {
        byte[] source;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("金额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(12.5);
            sheet.createRow(2).createCell(0).setCellValue(7.5);
            var old = row.createCell(2);
            old.setCellFormula("A2*2");
            old.setCellValue(25);
            var style = workbook.createCellStyle();
            style.setWrapText(true);
            old.setCellStyle(style);
            workbook.write(output);
            source = output.toByteArray();
        }
        var asset = new FileAsset();
        asset.setId(10L);
        asset.setExtension("xlsx");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(source), source.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "amount.xlsx"));
        var storage = mock(FileStorage.class);
        var writer = mock(VersionWriter.class);
        when(storage.store(any(byte[].class), eq("xlsx"))).thenReturn("ai-formula.xlsx");
        when(storage.delete("ai-formula.xlsx")).thenReturn(true);
        var saved = new com.documenter.entity.DocumentVersion();
        saved.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(com.documenter.enums.ChangeType.AI_EDIT), any(), any(), eq(1)))
                .thenReturn(saved);
        var spreadsheets = new XlsxDocumentServiceImpl(versions, storage, writer, new com.documenter.configuration.StorageProperties());
        var stub = new com.documenter.component.StubModelProvider(properties);
        var validator = new EditPlanValidator(properties);
        var realAgent = new AgentServiceImpl(properties, stub, documents, spreadsheets, versions, validator,
                new EditPlanExecutor(documents, spreadsheets), mock(com.documenter.component.AiDailyCallQuota.class),
                List.of(new com.documenter.component.tool.SetFormulaTool(spreadsheets)));
        var realService = new AiReplyServiceImpl(chats, sessions, messages, realAgent, documents, spreadsheets,
                versions, stub, properties, validator, audit, transactions);
        var request = request();
        request.setContent("[stub-tool] set_formula " + json.writeValueAsString(Map.of("sheetName", "数据",
                "cell", blank ? "C4" : "C2", "formula", formula, "reason", "汇总金额")));
        return new FormulaWorkflow(realService, storage, writer, request);
    }

    private record FormulaWorkflow(AiReplyServiceImpl service, FileStorage storage, VersionWriter writer,
                                   CreateAiReplyDTO request) { }

    private FormulaWorkflow tableChartWorkflow() throws Exception {
        byte[] source;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new java.io.ByteArrayOutputStream()) {
            workbook.createSheet("数据").createRow(0).createCell(0).setCellValue("source");
            workbook.write(output);
            source = output.toByteArray();
        }
        var asset = new FileAsset();
        asset.setId(10L);
        asset.setExtension("xlsx");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(source), source.length,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "source.xlsx"));
        var storage = mock(FileStorage.class);
        var writer = mock(VersionWriter.class);
        when(storage.store(any(byte[].class), eq("xlsx"))).thenReturn("ai-table-chart.xlsx");
        var saved = new com.documenter.entity.DocumentVersion();
        saved.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(),
                eq(com.documenter.enums.ChangeType.AI_EDIT), any(), any(), eq(1))).thenReturn(saved);
        var spreadsheets = new XlsxDocumentServiceImpl(
                versions, storage, writer, new com.documenter.configuration.StorageProperties());
        var stub = new com.documenter.component.StubModelProvider(properties);
        var validator = new EditPlanValidator(properties);
        var realAgent = new AgentServiceImpl(properties, stub, documents, spreadsheets, versions, validator,
                new EditPlanExecutor(documents, spreadsheets),
                mock(com.documenter.component.AiDailyCallQuota.class),
                List.of(new com.documenter.component.tool.CreateXlsxTableTool(spreadsheets)));
        var realService = new AiReplyServiceImpl(chats, sessions, messages, realAgent, documents,
                spreadsheets, versions, stub, properties, validator, audit, transactions);
        var request = request();
        request.setContent("[stub-tool] create_xlsx_table " + json.writeValueAsString(Map.of(
                "sheetName", "汇总",
                "dataJson", "{\"headers\":[\"项目\",\"金额\"],"
                        + "\"rows\":[[\"产品 A\",120],[\"产品 B\",80]]}",
                "chartType", "BAR",
                "categoryColumn", "项目",
                "valueColumn", "金额",
                "chartTitle", "金额对比",
                "reason", "生成汇总表和图表")));
        return new FormulaWorkflow(realService, storage, writer, request);
    }

    @Test
    void imageReplyRoundTripsPreviewAndConfirmationEmbedsPinnedImageOnce() throws Exception {
        var workflow = imageWorkflow();
        var pending = workflow.service().create(7L, 1L, workflow.request());

        assertEquals("PENDING", pending.planStatus());
        var change = pending.changes().getFirst();
        assertEquals("INSERT_IMAGE", change.type());
        assertNull(change.before());
        assertNull(change.after());
        assertEquals(new DocumentEditPlan.ImageReference(30L, 2, 80, 40), change.image());
        assertEquals(change, workflow.service().get(7L, 1L, pending.message().getId()).changes().getFirst());
        verifyNoInteractions(workflow.storage(), workflow.writer());

        var confirmed = workflow.service().confirm(7L, 1L, pending.message().getId());
        var replay = workflow.service().confirm(7L, 1L, pending.message().getId());

        assertEquals(2, confirmed.outputVersion());
        assertEquals(confirmed.outputVersion(), replay.outputVersion());
        var content = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(workflow.storage()).store(content.capture(), eq("docx"));
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(content.getValue()))) {
            assertEquals("image anchor", document.getParagraphs().getFirst().getText());
            assertEquals(1, document.getAllPictures().size());
            assertArrayEquals(workflow.imageBytes(), document.getAllPictures().getFirst().getData());
        }
        verify(versions, times(2)).downloadVersion(7L, 30L, 2);
        verify(workflow.writer()).appendVersion(eq(10L), eq(7L), any(),
                eq(com.documenter.enums.ChangeType.AI_EDIT), any(), any(), eq(1));
    }

    @Test
    void discardedImagePlanNeverWritesOrReadsImageAgain() throws Exception {
        var workflow = imageWorkflow();
        long id = workflow.service().create(7L, 1L, workflow.request()).message().getId();
        clearInvocations(versions);

        assertEquals("DISCARDED", workflow.service().discard(7L, 1L, id).planStatus());
        assertCode(409, () -> workflow.service().confirm(7L, 1L, id));

        verifyNoInteractions(workflow.storage(), workflow.writer(), versions);
    }

    @Test
    void revokedImageBeforeConfirmationLeavesPlanPendingWithoutPartialOutput() throws Exception {
        var workflow = imageWorkflow();
        long id = workflow.service().create(7L, 1L, workflow.request()).message().getId();
        when(versions.downloadVersion(7L, 30L, 2)).thenThrow(new BusinessException(404, "图片已不可用"));

        assertCode(404, () -> workflow.service().confirm(7L, 1L, id));

        assertEquals("PENDING", store.get(id).getPlanStatus());
        assertNull(store.get(id).getOutputVersion());
        verifyNoInteractions(workflow.storage(), workflow.writer());
    }

    private ImageWorkflow imageWorkflow() throws Exception {
        byte[] source;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("image anchor");
            document.write(output);
            source = output.toByteArray();
        }
        var asset = new FileAsset();
        asset.setId(10L);
        asset.setUserId(7L);
        asset.setExtension("docx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(source), source.length, asset.getContentType(), "test.docx"));
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        byte[] imageBytes;
        try (var output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            imageBytes = output.toByteArray();
        }
        var imageAsset = new FileAsset();
        imageAsset.setId(30L);
        imageAsset.setUserId(7L);
        imageAsset.setExtension("png");
        imageAsset.setLatestVersion(9);
        when(versions.downloadVersion(7L, 30L, 2)).thenReturn(new FileDownload(imageAsset,
                new ByteArrayResource(imageBytes), imageBytes.length, "image/png", "illustration-v2.png"));
        var storage = mock(FileStorage.class);
        var writer = mock(VersionWriter.class);
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("versions/ai-image.docx");
        var saved = new com.documenter.entity.DocumentVersion();
        saved.setVersionNo(2);
        saved.setChangeType("AI_EDIT");
        when(writer.appendVersion(eq(10L), eq(7L), any(),
                eq(com.documenter.enums.ChangeType.AI_EDIT), any(), any(), eq(1))).thenReturn(saved);
        var realDocuments = new DocxDocumentServiceImpl(versions, storage, writer, mock(FileService.class), mock(DocxTemplateGenerationService.class));
        var stub = new com.documenter.component.StubModelProvider(properties);
        var validator = new EditPlanValidator(properties);
        var realAgent = new AgentServiceImpl(properties, stub, realDocuments,
                mock(XlsxDocumentService.class), versions, validator, new EditPlanExecutor(realDocuments, mock(XlsxDocumentService.class)),
                mock(com.documenter.component.AiDailyCallQuota.class),
                List.of(new com.documenter.component.tool.InsertImageTool(realDocuments, properties, versions)));
        var realService = new AiReplyServiceImpl(chats, sessions, messages, realAgent, realDocuments, mock(XlsxDocumentService.class),
                versions, stub, properties, validator, audit, transactions);
        var request = request();
        request.setContent("[stub-tool] insert_image {\"target\":\"body/p0\",\"imageFileId\":30,"
                + "\"imageVersionNo\":2,\"widthPixels\":80,\"heightPixels\":40}");
        return new ImageWorkflow(realService, storage, writer, request, imageBytes);
    }

    private record ImageWorkflow(AiReplyServiceImpl service, FileStorage storage,
                                  VersionWriter writer, CreateAiReplyDTO request, byte[] imageBytes) { }

    @Test
    void stubModelToConfirmedDocxProducesReopenableAiVersion() throws Exception {
        verifyStubModelToConfirmedDocx(null);
    }

    @Test
    void stubFormattingReplyShowsPersistedStyleDiffAndConfirmsOnce() throws Exception {
        verifyStubModelToConfirmedDocx("SET_HEADING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET_PAGE_BREAK_BEFORE", "CLEAR_PAGE_BREAK_BEFORE"})
    void stubPaginationReplyShowsDirectPropertyAndConfirmsExactlyOnce(String type) throws Exception {
        verifyStubModelToConfirmedDocx(type);
    }

    private void verifyStubModelToConfirmedDocx(String formatType) throws Exception {
        boolean formatting = formatType != null;
        boolean pagination = formatting && formatType.endsWith("PAGE_BREAK_BEFORE");
        Boolean previousPageBreak = pagination ? formatType.startsWith("CLEAR") : null;
        byte[] source;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("original text");
            document.getParagraphs().getFirst().setStyle("Heading1");
            if (pagination) document.getParagraphs().getFirst().setPageBreak(previousPageBreak);
            document.write(output);
            source = output.toByteArray();
        }
        var asset = new FileAsset();
        asset.setId(10L);
        asset.setUserId(7L);
        asset.setExtension("docx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(source), source.length, asset.getContentType(), "test.docx"));
        var storage = mock(FileStorage.class);
        var writer = mock(VersionWriter.class);
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("versions/ai.docx");
        var saved = new com.documenter.entity.DocumentVersion();
        saved.setVersionNo(2);
        saved.setChangeType("AI_EDIT");
        when(writer.appendVersion(eq(10L), eq(7L), any(),
                eq(com.documenter.enums.ChangeType.AI_EDIT), any(), any(), eq(1))).thenReturn(saved);
        var realDocuments = new DocxDocumentServiceImpl(versions, storage, writer, mock(FileService.class), mock(DocxTemplateGenerationService.class));
        var stub = new com.documenter.component.StubModelProvider(properties);
        var validator = new EditPlanValidator(properties);
        var realAgent = new AgentServiceImpl(properties, stub, realDocuments,
                mock(XlsxDocumentService.class), versions, validator, new EditPlanExecutor(realDocuments, mock(XlsxDocumentService.class)),
                mock(com.documenter.component.AiDailyCallQuota.class),
                List.of(new com.documenter.component.tool.EditParagraphTool()));
        var realService = new AiReplyServiceImpl(chats, sessions, messages, realAgent, realDocuments, mock(XlsxDocumentService.class),
                versions, stub, properties, validator, audit, transactions);
        var request = request();
        request.setContent("[stub-tool] edit_paragraph {\"operation\":\"REPLACE_TEXT\","
                + "\"target\":\"body/p0\",\"content\":\"edited text\"}");
        if (formatting) {
            request.setContent("[stub-tool] edit_paragraph {\"operation\":\"FORMAT_PARAGRAPH\","
                    + "\"target\":\"body/p0\",\"formatType\":\"" + formatType + "\""
                    + (pagination ? "}" : ",\"level\":2}"));
        }

        var reply = realService.create(7L, 1L, request);
        assertEquals("PENDING", reply.planStatus());
        var change = reply.changes().getFirst();
        if (formatting) {
            assertNull(change.before());
            assertNull(change.after());
            assertEquals(new AiReplyVO.ParagraphStyle("Heading1", 1, null, previousPageBreak), change.beforeFormat());
            assertEquals(new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.valueOf(formatType),
                    pagination ? null : 2), change.format());
            assertEquals(change, realService.get(7L, 1L, reply.message().getId()).changes().getFirst());
        } else {
            assertEquals("original text", change.before());
        }
        verifyNoInteractions(storage, writer);
        var confirmed = realService.confirm(7L, 1L, reply.message().getId());
        assertEquals(confirmed.outputVersion(), realService.confirm(7L, 1L, reply.message().getId()).outputVersion());

        assertEquals(2, confirmed.outputVersion());
        var content = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(content.capture(), eq("docx"));
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(content.getValue()))) {
            assertEquals(formatting ? "original text" : "edited text", document.getParagraphs().getFirst().getText());
            assertEquals(formatting && !pagination ? "Heading2" : "Heading1", document.getParagraphs().getFirst().getStyle());
            if (pagination) assertEquals(formatType.startsWith("SET"), document.getParagraphs().getFirst().isPageBreak());
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(),
                eq(com.documenter.enums.ChangeType.AI_EDIT), eq(request.getContent()), any(), eq(1));
    }

    @Test
    void createPersistsUserAndAssistantAndPreviewsWithoutEditing() {
        var reply = create();

        assertEquals("COMPLETED", reply.runStatus());
        assertEquals("PENDING", reply.planStatus());
        assertEquals("stub", reply.message().getModelName());
        assertEquals("old text", reply.changes().getFirst().before());
        assertEquals("new text", reply.changes().getFirst().after());
        assertEquals(2, store.size());
        verify(agent, never()).executePlan(any(), any(), any(), any(), any());
    }

    @Test
    void replayReturnsSameReplyWithoutAnotherModelCall() {
        var first = create();
        var second = create();

        assertEquals(first.message().getId(), second.message().getId());
        assertEquals(2, store.size());
        verify(agent).runConversation(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void keyReusedForDifferentContentOrVersionIsRejected() {
        create();
        var changed = request();
        changed.setContent("different");
        assertCode(409, () -> service.create(7L, 1L, changed));
        changed.setContent("edit");
        changed.setVersionNo(2);
        assertCode(409, () -> service.create(7L, 1L, changed));
    }

    @Test
    void concurrentConfirmationsProduceOnlyOneVersion() throws Exception {
        long id = create().message().getId();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> service.confirm(7L, 1L, id));
            var second = threads.submit(() -> service.confirm(7L, 1L, id));
            assertEquals(2, first.get(3, TimeUnit.SECONDS).outputVersion());
            assertEquals(2, second.get(3, TimeUnit.SECONDS).outputVersion());
        }
        verify(agent).executePlan(eq(7L), eq(10L), eq(1), any(), eq("edit"));
        assertEquals("CONFIRMED", store.get(id).getPlanStatus());
    }

    @Test
    void discardIsIdempotentAndPreventsConfirmation() {
        long id = create().message().getId();
        assertEquals("DISCARDED", service.discard(7L, 1L, id).planStatus());
        assertEquals("DISCARDED", service.discard(7L, 1L, id).planStatus());
        assertCode(409, () -> service.confirm(7L, 1L, id));
        verify(agent, never()).executePlan(any(), any(), any(), any(), any());
    }

    @Test
    void confirmedPlanCannotBeDiscarded() {
        long id = create().message().getId();
        service.confirm(7L, 1L, id);
        assertCode(409, () -> service.discard(7L, 1L, id));
    }

    @Test
    void staleVersionRollsBackConfirmationAndLeavesPlanPending() {
        long id = create().message().getId();
        when(agent.executePlan(any(), any(), any(), any(), any())).thenThrow(new BusinessException(409, "stale"));

        assertCode(409, () -> service.confirm(7L, 1L, id));

        assertEquals("PENDING", store.get(id).getPlanStatus());
        assertNull(store.get(id).getOutputVersion());
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void replyStateFailureRollsBackConfirmation() {
        long id = create().message().getId();
        when(messages.updateById(any(AiMessage.class))).thenReturn(0);

        assertCode(409, () -> service.confirm(7L, 1L, id));

        assertEquals("PENDING", store.get(id).getPlanStatus());
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void foreignSessionCannotCreateConfirmOrDiscard() {
        assertCode(404, () -> service.create(8L, 1L, request()));
        assertCode(404, () -> service.confirm(8L, 1L, 102L));
        assertCode(404, () -> service.discard(8L, 1L, 102L));
        verifyNoInteractions(agent);
    }

    @Test
    void replyFromAnotherSessionCannotBeReadOrConfirmed() {
        long id = create().message().getId();
        var other = new AiSession();
        other.setId(2L);
        other.setUserId(7L);
        other.setStatus("ACTIVE");
        when(chats.requireOwnedSession(7L, 2L)).thenReturn(other);
        when(sessions.selectOwnedForUpdate(7L, 2L)).thenReturn(other);

        assertCode(404, () -> service.get(7L, 2L, id));
        assertCode(404, () -> service.confirm(7L, 2L, id));
    }

    @Test
    void archivedSessionRejectsCreationAndConfirmation() {
        long id = create().message().getId();
        session.setStatus("ARCHIVED");
        assertCode(409, () -> service.create(7L, 1L, request()));
        assertCode(409, () -> service.confirm(7L, 1L, id));
    }

    @Test
    void documentVersionAndOwnershipAreCheckedBeforeModelCall() {
        var noVersion = request();
        noVersion.setVersionNo(null);
        assertCode(400, () -> service.create(7L, 1L, noVersion));
        when(versions.downloadVersion(7L, 10L, 1)).thenThrow(new BusinessException(404, "not found"));
        assertCode(404, () -> service.create(7L, 1L, request()));
        verifyNoInteractions(agent);
        assertTrue(store.isEmpty());
    }

    @Test
    void modelFailureIsPersistedAndCannotBeConfirmed() {
        when(agent.runConversation(any(), any(), any(), any(), anyList(), any()))
                .thenThrow(new IllegalStateException("provider internal secret"));
        var reply = create();
        assertEquals("FAILED", reply.runStatus());
        assertEquals("NONE", reply.planStatus());
        assertFalse(reply.message().getContent().contains("secret"));
        assertCode(409, () -> service.confirm(7L, 1L, reply.message().getId()));
    }

    @Test
    void runningReplyIsReplayedWithoutAnotherModelCall() {
        long id = create().message().getId();
        store.get(id).setRunStatus("RUNNING");
        store.get(id).setPlanStatus("NONE");
        var replay = create();
        assertEquals("RUNNING", replay.runStatus());
        verify(agent).runConversation(any(), any(), any(), any(), anyList(), any());
    }

    @Test
    void differentRequestCannotInterleaveWithRunningReply() {
        when(messages.selectRunningReply(1L)).thenReturn(new AiMessage());
        assertCode(409, () -> service.create(7L, 1L, request()));
        verifyNoInteractions(agent);
    }

    @Test
    void historyKeepsRolesAndChronologicalOrder() {
        var assistant = new AiMessage();
        assistant.setRole("ASSISTANT");
        assistant.setContent("previous answer");
        var user = new AiMessage();
        user.setRole("USER");
        user.setContent("previous request");
        when(messages.selectRecentConversation(1L)).thenReturn(List.of(assistant, user));

        create();

        verify(agent).runConversation(eq(7L), eq(10L), eq(1), eq("edit"), eq(List.of(
                new AgentService.ConversationTurn("USER", "previous request"),
                new AgentService.ConversationTurn("ASSISTANT", "previous answer"))), isNull());
    }

    @Test
    void userSourcesArePassedToAgentAndPersistedInReplyResult() {
        var source = new AiSourceDTO();
        source.setTitle("产品说明");
        source.setUrl("https://example.test/doc");
        source.setExcerpt("示例摘录");
        CreateAiReplyDTO request = request();
        request.setSources(List.of(source));
        var original = result(false);
        when(agent.runConversationWithSources(eq(7L), eq(10L), eq(1), eq("edit"),
                anyList(), eq(List.of(new AgentRunResult.Source(
                        "产品说明", "https://example.test/doc", "示例摘录"))), isNull()))
                .thenReturn(new AgentRunResult(original.reply(), original.steps(), original.plans(),
                        original.usage(), original.termination(), false, List.of(
                        new AgentRunResult.Source("产品说明", "https://example.test/doc", "示例摘录")),
                        "已回显用户提供的资料来源，服务端未联网核验其真实性。"));

        var reply = service.create(7L, 1L, request);

        assertEquals(1, reply.result().sources().size());
        assertEquals("产品说明", reply.result().sources().getFirst().title());
        assertTrue(reply.result().verificationNotice().contains("未联网核验"));
        assertEquals(reply.result(), service.get(7L, 1L, reply.message().getId()).result());
    }

    @Test
    void streamingCreationEmitsStartedAndDeltaBeforeCompleted() {
        List<AiReplyService.StreamEvent> events = new ArrayList<>();
        when(agent.runConversationStreaming(eq(7L), eq(10L), eq(1), eq("edit"),
                anyList(), anyList(), any(), any())).thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<AgentService.StreamEvent> callback =
                            invocation.getArgument(7, java.util.function.Consumer.class);
                    callback.accept(new AgentService.StreamEvent(
                            AgentService.StreamEventType.TEXT_DELTA, "流式内容"));
                    return result(false);
                });

        service.createStreaming(7L, 1L, request(), events::add, () -> false);

        assertEquals(AiReplyService.StreamEvent.Type.STARTED, events.getFirst().type());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == AiReplyService.StreamEvent.Type.TEXT_DELTA
                        && "流式内容".equals(event.text())));
        assertEquals(AiReplyService.StreamEvent.Type.COMPLETED, events.getLast().type());
    }

    @Test
    void pureConversationDoesNotRequireOrProduceDocumentVersion() {
        session.setFileId(null);
        var request = request();
        request.setVersionNo(null);
        when(agent.runConversation(eq(7L), isNull(), isNull(), eq("edit"), anyList(), any())).thenReturn(result(false));

        var reply = service.create(7L, 1L, request);

        assertEquals("COMPLETED", reply.runStatus());
        assertEquals("NONE", reply.planStatus());
        verifyNoInteractions(documents, versions);
    }

    @Test
    void exhaustedQuotaPersistsFailedReplyAndReplayNeverRunsAgentOrConfirmsPartialPlans() {
        var original = result(true);
        var exhausted = new AgentRunResult("quota denied", original.steps(), original.plans(), original.usage(),
                AgentRunResult.Termination.QUOTA_EXCEEDED, true);
        when(agent.runConversation(any(), any(), any(), any(), anyList(), any())).thenReturn(exhausted);

        var first = create();
        var replay = create();
        var queried = service.get(7L, 1L, first.message().getId());

        assertEquals("FAILED", first.runStatus());
        assertEquals("NONE", first.planStatus());
        assertTrue(first.changes().isEmpty());
        assertEquals(20, first.message().getPromptTokens());
        assertEquals(exhausted, queried.result());
        assertEquals(first.message().getId(), replay.message().getId());
        assertCode(409, () -> service.confirm(7L, 1L, first.message().getId()));
        verify(agent).runConversation(eq(7L), eq(10L), eq(1), eq("edit"), anyList(), isNull());
        verify(agent, never()).executePlan(any(), any(), any(), any(), any());
    }

    @Test
    void conflictingPlansAreRejectedAsAWholeWithoutLosingUsage() {
        var original = result(true);
        var conflicting = new AgentRunResult("two changes", List.of(),
                List.of(original.plans().getFirst(), original.plans().getFirst()), original.usage(),
                AgentRunResult.Termination.COMPLETED, false);
        when(agent.runConversation(any(), any(), any(), any(), anyList(), any())).thenReturn(conflicting);

        var reply = create();

        assertEquals("FAILED", reply.runStatus());
        assertEquals(AgentRunResult.Termination.INVALID_PLAN, reply.result().termination());
        assertEquals(20, reply.message().getPromptTokens());
        assertFalse(reply.result().hasPendingPlans());
        verify(agent, never()).executePlan(any(), any(), any(), any(), any());
    }

    @Test
    void longHistoryIsBoundedBeforeBeingSentToModel() {
        var recent = new AiMessage();
        recent.setRole("ASSISTANT");
        recent.setContent("a".repeat(8000));
        var older = new AiMessage();
        older.setRole("USER");
        older.setContent("b".repeat(8000));
        when(messages.selectRecentConversation(1L)).thenReturn(List.of(recent, older));

        create();

        verify(agent).runConversation(eq(7L), eq(10L), eq(1), eq("edit"),
                eq(List.of(new AgentService.ConversationTurn("ASSISTANT", recent.getContent()))), isNull());
    }

    @Test
    void unsafeConfigurationCannotBypassConfirmation() {
        properties.setRequireConfirmation(false);
        assertCode(503, () -> service.create(7L, 1L, request()));
        verifyNoInteractions(agent);
    }

    private AiReplyVO create() { return service.create(7L, 1L, request()); }

    private CreateAiReplyDTO request() {
        var request = new CreateAiReplyDTO();
        request.setContent("edit");
        request.setVersionNo(1);
        request.setIdempotencyKey("request-1");
        return request;
    }

    private static AgentRunResult result(boolean withPlan) {
        var plan = new DocumentEditPlan("edit", List.of(new DocumentEditPlan.Operation(
                "REPLACE_TEXT", "body/p0", "new text", "formal")), null);
        return new AgentRunResult("reply", List.of(), withPlan ? List.of(plan) : List.of(),
                new AgentRunResult.Usage(2, 1, 20, 10, BigDecimal.ZERO, false, 50),
                AgentRunResult.Termination.COMPLETED, false);
    }

    private AiMessage copy(AiMessage message) {
        return message == null ? null : json.readValue(json.writeValueAsString(message), AiMessage.class);
    }

    private static void assertCode(int code, Runnable work) {
        assertEquals(code, assertThrows(BusinessException.class, work::run).getCode());
    }

    /** 仅在单元测试模拟事务串行与回滚；真实 MariaDB 行锁仍需集成验收。 */
    private class TestTransactions implements PlatformTransactionManager {
        private final ReentrantLock lock = new ReentrantLock();
        private Map<Long, AiMessage> snapshot;
        private int rollbacks;
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            lock.lock();
            snapshot = new HashMap<>();
            store.forEach((id, row) -> snapshot.put(id, copy(row)));
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { lock.unlock(); }
        @Override public void rollback(TransactionStatus status) {
            store.clear();
            store.putAll(snapshot);
            rollbacks++;
            lock.unlock();
        }
    }
}
