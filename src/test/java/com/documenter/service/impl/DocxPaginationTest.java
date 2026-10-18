package com.documenter.service.impl;

import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileDownload;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies OOXML properties and retained content; does not assert real Office page counts. */
class DocxPaginationTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(versions, storage, writer,
            mock(FileService.class), mock(DocxTemplateGenerationService.class));

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void manualPageBreakChangesOnlyDirectPropertyAndPreservesSectionAndStyles(boolean enabled) throws Exception {
        byte[] source = source(!enabled, true);
        prepare(source);
        service.formatParagraphs(7L, 10L, 1, request("body/p1", enabled));
        try (var before = open(source); var after = open(saved())) {
            var original = before.getParagraphs().get(1);
            var edited = after.getParagraphs().get(1);
            assertEquals(original.getText(), edited.getText());
            assertEquals(enabled, edited.isPageBreak());
            assertTrue(edited.getCTP().getPPr().isSetPageBreakBefore());
            assertEquals(original.getRuns().getFirst().getCTR().xmlText(), edited.getRuns().getFirst().getCTR().xmlText());
            var oldProperties = (CTPPr) original.getCTP().getPPr().copy();
            var newProperties = (CTPPr) edited.getCTP().getPPr().copy();
            oldProperties.unsetPageBreakBefore();
            newProperties.unsetPageBreakBefore();
            assertEquals(oldProperties.xmlText(), newProperties.xmlText());
            assertEquals(before.getStyles().getStyle("Heading2").getCTStyle().xmlText(),
                    after.getStyles().getStyle("Heading2").getCTStyle().xmlText());
            assertEquals(before.getHeaderList().getFirst().getText(), after.getHeaderList().getFirst().getText());
            assertEquals(before.getFooterList().getFirst().getText(), after.getFooterList().getFirst().getText());
            assertEquals(before.getDocument().getBody().getSectPr().xmlText(), after.getDocument().getBody().getSectPr().xmlText());
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1));
    }

    @Test
    void structureDistinguishesUnsetDirectTrueAndExplicitFalseWithoutResolvingStyles() throws Exception {
        prepare(source(null, true));
        var structure = service.readStructure(7L, 10L, 1);
        assertNull(structure.blocks().get(1).pageBreakBefore());
        prepare(source(true, true));
        assertEquals(Boolean.TRUE, service.readStructure(7L, 10L, 1).blocks().get(1).pageBreakBefore());
        prepare(source(false, true));
        assertEquals(Boolean.FALSE, service.readStructure(7L, 10L, 1).blocks().get(1).pageBreakBefore());
    }

    @Test
    void paginationPreservesInlineFieldsAndDrawingsWithoutReplacingTheirContent() throws Exception {
        byte[] input;
        try (var doc = open(source(false, false)); var bytes = new ByteArrayOutputStream()) {
            var paragraph = doc.getParagraphs().get(1);
            paragraph.getCTP().addNewFldSimple().setInstr("PAGE");
            paragraph.createRun().getCTR().addNewDrawing();
            doc.write(bytes);
            input = bytes.toByteArray();
        }
        prepare(input);
        service.formatParagraphs(7L, 10L, 1, request("body/p1", true));
        try (var original = open(input); var edited = open(saved())) {
            var before = original.getParagraphs().get(1).getCTP();
            var after = edited.getParagraphs().get(1).getCTP();
            before.getPPr().unsetPageBreakBefore();
            after.getPPr().unsetPageBreakBefore();
            assertEquals(before.xmlText(), after.xmlText());
        }
    }

    @Test
    void clearWritesExplicitFalseEvenWhenStyleHasPageBreakBefore() throws Exception {
        prepare(source(null, true));
        service.formatParagraphs(7L, 10L, 1, request("body/p1", false));
        try (var workbook = open(saved())) {
            assertTrue(workbook.getParagraphs().get(1).getCTP().getPPr().isSetPageBreakBefore());
            assertFalse(workbook.getParagraphs().get(1).isPageBreak());
            assertTrue(workbook.getStyles().getStyle("Heading2").getCTStyle().getPPr().isSetPageBreakBefore());
        }
    }

    @Test
    void confirmedMixedTextAndPaginationPlanWritesOneAiVersion() throws Exception {
        prepare(source(false, false));
        var plan = new DocumentEditPlan("chapter starts on new page", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p1", "New chapter", null),
                pageOperation("body/p1", true)), null);
        service.editPlan(7L, 10L, 1, plan, "new page");
        try (var after = open(saved())) {
            assertEquals("New chapter", after.getParagraphs().get(1).getText());
            assertTrue(after.getParagraphs().get(1).isPageBreak());
            assertEquals("Heading2", after.getParagraphs().get(1).getStyle());
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), eq("new page"), any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT_BEFORE", "INSERT_AFTER"})
    void insertionDoesNotCopyDirectPageBreakToNewParagraph(String type) throws Exception {
        prepare(source(true, false));
        var operation = new EditDocxParagraphsDTO.Operation();
        operation.setType(type);
        operation.setBlockId("body/p1");
        operation.setText("Inserted");
        var request = new EditDocxParagraphsDTO();
        request.setOperations(List.of(operation));
        service.editParagraphs(7L, 10L, 1, request);
        try (var after = open(saved())) {
            var inserted = after.getParagraphs().stream().filter(p -> p.getText().equals("Inserted")).findFirst().orElseThrow();
            assertFalse(inserted.getCTP().getPPr().isSetPageBreakBefore());
            assertEquals("Heading2", inserted.getStyle());
            assertEquals(1, after.getParagraphs().stream().filter(p -> p.isPageBreak()).count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void tableParagraphIsRejectedInManualAndAiPathsWithoutSaving(boolean enabled) throws Exception {
        prepare(source(false, false));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.formatParagraphs(7L, 10L, 1, request("body/t0/r0/c0/p0", enabled))).getCode());
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editPlan(7L, 10L, 1, new DocumentEditPlan("invalid", List.of(
                        pageOperation("body/p1", enabled), pageOperation("body/t0/r0/c0/p0", enabled)), null), "invalid")).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void invalidHeadingLevelOnPageBreakAndMissingParagraphFailBeforeSaving() throws Exception {
        prepare(source(false, false));
        var request = request("body/p1", true);
        request.getOperations().getFirst().setLevel(2);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.formatParagraphs(7L, 10L, 1, request)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.formatParagraphs(7L, 10L, 1, request("body/p999", true))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void outerRollbackCleansPaginationArtifactAndConflictDoesNotRetainOutput() throws Exception {
        prepare(source(false, false));
        when(storage.delete("pagination.docx")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.formatParagraphs(7L, 10L, 1, request("body/p1", true));
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(storage).delete("pagination.docx");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        clearInvocations(storage);
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1)))
                .thenThrow(new BusinessException(409, "conflict"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.formatParagraphs(7L, 10L, 1, request("body/p1", true))).getCode());
        verify(storage).delete("pagination.docx");
    }

    private void prepare(byte[] bytes) {
        var asset = new FileAsset();
        asset.setExtension("docx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(bytes), bytes.length, asset.getContentType(), "source.docx"));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("pagination.docx");
        var saved = new DocumentVersion();
        saved.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1))).thenReturn(saved);
    }

    private static byte[] source(Boolean direct, boolean styleBreak) throws Exception {
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Introduction");
            var chapter = doc.createParagraph();
            chapter.setStyle("Heading2");
            chapter.setIndentationLeft(120);
            chapter.setSpacingBefore(240);
            chapter.createRun().setText("Chapter");
            chapter.getRuns().getFirst().setBold(true);
            if (direct != null) chapter.setPageBreak(direct);
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId("Heading2");
            style.setType(STStyleType.PARAGRAPH);
            style.addNewPPr().addNewPageBreakBefore().setVal(styleBreak);
            doc.createStyles().addStyle(new XWPFStyle(style));
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header");
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer");
            chapter.getCTP().getPPr().setSectPr((CTSectPr) doc.getDocument().getBody().getSectPr().copy());
            doc.createTable(1, 1).getRow(0).getCell(0).setText("Table");
            doc.write(output);
            return output.toByteArray();
        }
    }

    private static FormatDocxParagraphsDTO request(String target, boolean enabled) {
        var operation = new FormatDocxParagraphsDTO.Operation();
        operation.setType(formatType(enabled).name());
        operation.setBlockId(target);
        var request = new FormatDocxParagraphsDTO();
        request.setOperations(List.of(operation));
        return request;
    }
    private static DocumentEditPlan.Operation pageOperation(String target, boolean enabled) {
        return new DocumentEditPlan.Operation("FORMAT_PARAGRAPH", target, null, "pagination", null,
                new DocumentEditPlan.ParagraphFormat(formatType(enabled), null));
    }
    private static DocumentEditPlan.FormatType formatType(boolean enabled) {
        return enabled ? DocumentEditPlan.FormatType.SET_PAGE_BREAK_BEFORE : DocumentEditPlan.FormatType.CLEAR_PAGE_BREAK_BEFORE;
    }
    private byte[] saved() {
        var capture = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(capture.capture(), eq("docx"));
        return capture.getValue();
    }
    private static XWPFDocument open(byte[] bytes) throws Exception { return new XWPFDocument(new ByteArrayInputStream(bytes)); }
}
