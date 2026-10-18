package com.documenter.service.impl;

import com.documenter.dto.EditDocxFootnotesDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocxFootnoteTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 1;

    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(
            versions, storage, writer, mock(FileService.class),
            mock(DocxTemplateGenerationService.class));

    @Test
    void addsFootnoteReferenceAndReadsItBack() throws Exception {
        prepare(simpleDocument());

        service.editFootnotes(USER_ID, FILE_ID, VERSION, request(operation(
                "ADD", "body/p0", null, "来源说明")));

        byte[] edited = saved();
        try (XWPFDocument document = open(edited)) {
            assertTrue(document.getParagraphs().getFirst().getText().startsWith("正文"));
            var referencedIds = document.getParagraphs().getFirst().getRuns().stream()
                    .flatMap(run -> run.getCTR().getFootnoteReferenceList().stream())
                    .map(reference -> reference.getId().intValue()).toList();
            assertEquals(1, referencedIds.size());
            assertEquals("来源说明", footnoteText(document, referencedIds.getFirst()));
        }
        when(versions.downloadVersion(USER_ID, FILE_ID, 2)).thenReturn(download(edited));
        var result = service.readFootnotes(USER_ID, FILE_ID, 2);
        assertEquals(1, result.footnotes().size());
        assertEquals("来源说明", result.footnotes().getFirst().text());
        assertEquals(List.of("body/p0"), result.footnotes().getFirst().referencedBy());
    }

    @Test
    void addsFootnoteReferenceToTableCellParagraph() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文");
            document.createTable(1, 1).getRow(0).getCell(0).setText("单元格");
            document.write(output);
            source = output.toByteArray();
        }
        prepare(source);

        service.editFootnotes(USER_ID, FILE_ID, VERSION, request(operation(
                "ADD", "body/t0/r0/c0/p0", null, "表格来源")));

        try (XWPFDocument document = open(saved())) {
            var cellParagraph = document.getTables().getFirst().getRow(0).getCell(0).getParagraphs().getFirst();
            assertEquals(1, cellParagraph.getRuns().stream()
                    .flatMap(run -> run.getCTR().getFootnoteReferenceList().stream()).count());
        }
        when(versions.downloadVersion(USER_ID, FILE_ID, 2)).thenReturn(download(savedBytesForRead()));
        assertEquals(List.of("body/t0/r0/c0/p0"),
                service.readFootnotes(USER_ID, FILE_ID, 2).footnotes().getFirst().referencedBy());
    }

    @Test
    void updatesExistingFootnoteTextWithoutChangingReferenceId() throws Exception {
        FootnoteFixture source = fixtureWithFootnote();
        prepare(source.bytes());

        service.editFootnotes(USER_ID, FILE_ID, VERSION, request(operation(
                "UPDATE", null, source.id(), "更新后的来源")));

        try (XWPFDocument document = open(saved())) {
            assertEquals("更新后的来源", footnoteText(document, source.id()));
            assertEquals(source.id(), document.getParagraphs().getFirst().getRuns().stream()
                    .flatMap(run -> run.getCTR().getFootnoteReferenceList().stream())
                    .findFirst().orElseThrow().getId().intValue());
        }
    }

    @Test
    void deletesFootnoteAndEveryBodyReference() throws Exception {
        FootnoteFixture source = fixtureWithFootnote();
        prepare(source.bytes());

        service.editFootnotes(USER_ID, FILE_ID, VERSION, request(operation(
                "DELETE", null, source.id(), null)));

        try (XWPFDocument document = open(saved())) {
            assertTrue(document.getFootnotes().isEmpty());
            assertTrue(document.getParagraphs().stream()
                    .flatMap(paragraph -> paragraph.getRuns().stream())
                    .flatMap(run -> run.getCTR().getFootnoteReferenceList().stream())
                    .findAny().isEmpty());
        }
    }

    @Test
    void rejectsInvalidOperationsAndMissingReferencedFootnoteBeforeSaving() throws Exception {
        prepare(simpleDocument());
        assertThrows(BusinessException.class, () -> service.editFootnotes(
                USER_ID, FILE_ID, VERSION, request(operation("ADD", "header0/p0", null, "x"))));
        assertThrows(BusinessException.class, () -> service.editFootnotes(
                USER_ID, FILE_ID, VERSION, request(operation("UPDATE", null, 99, "x"))));
        assertThrows(BusinessException.class, () -> service.editFootnotes(
                USER_ID, FILE_ID, VERSION, request(operation("DELETE", null, 99, null))));
        verify(storage, never()).store(any(byte[].class), anyString());
        verifyNoInteractions(writer);
    }

    private void prepare(byte[] source) {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("footnotes.docx");
        var created = new DocumentVersion();
        created.setVersionNo(2);
        created.setChangeType(ChangeType.MANUAL_EDIT.name());
        when(writer.appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.MANUAL_EDIT), any(), any(), eq(VERSION))).thenReturn(created);
    }

    private byte[] saved() {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("docx"));
        return bytes.getValue();
    }

    private byte[] savedBytesForRead() {
        var bytes = org.mockito.Mockito.mockingDetails(storage).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("store"))
                .findFirst().orElseThrow().getArgument(0);
        return (byte[]) bytes;
    }

    private static byte[] simpleDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static FootnoteFixture fixtureWithFootnote() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var body = document.createParagraph().createRun();
            body.setText("正文");
            var footnote = document.createFootnote();
            var paragraph = footnote.createParagraph();
            footnote.ensureFootnoteRef(paragraph);
            paragraph.createRun().setText("旧来源");
            body.getCTR().addNewFootnoteReference().setId(footnote.getId());
            document.write(output);
            return new FootnoteFixture(output.toByteArray(), footnote.getId().intValue());
        }
    }

    private static FileDownload download(byte[] bytes) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension("docx");
        return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length,
                asset.getContentType(), "source.docx");
    }

    private static XWPFDocument open(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private static String footnoteText(XWPFDocument document, int id) {
        return document.getFootnoteByID(id).getParagraphs().stream()
                .map(paragraph -> paragraph.getText())
                .reduce("", (left, right) -> left + right);
    }

    private static EditDocxFootnotesDTO request(EditDocxFootnotesDTO.Operation operation) {
        var request = new EditDocxFootnotesDTO();
        request.setInstruction("脚注测试");
        request.setOperations(List.of(operation));
        return request;
    }

    private static EditDocxFootnotesDTO.Operation operation(
            String type, String target, Integer id, String text) {
        var operation = new EditDocxFootnotesDTO.Operation();
        operation.setType(type);
        operation.setTargetBlockId(target);
        operation.setFootnoteId(id);
        operation.setText(text);
        return operation;
    }

    private record FootnoteFixture(byte[] bytes, int id) { }
}
