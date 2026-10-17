package com.documenter.service.impl;

import com.documenter.dto.CreateDocxTableOfContentsDTO;
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
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocxTableOfContentsTest {

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
    void insertsTocAtStartAndMarksFieldsForUpdate() throws Exception {
        prepare(simpleDocument());
        CreateDocxTableOfContentsDTO request = request("START", 3, true, "目录");

        service.createOrRefreshTableOfContents(USER_ID, FILE_ID, VERSION, request);

        byte[] edited = saved();
        try (XWPFDocument document = open(edited)) {
            assertTrue(document.getSettings().getCTSettings().isSetUpdateFields());
            assertEquals("目录", document.getParagraphs().getFirst().getText());
            String instruction = instruction(document.getParagraphs().get(1));
            assertTrue(instruction.startsWith("TOC "));
            assertTrue(instruction.contains("\\o \"1-3\""));
            assertTrue(instruction.contains("\\h"));
            assertEquals("标题", document.getParagraphs().get(2).getText());
        }
        when(versions.downloadVersion(USER_ID, FILE_ID, 2))
                .thenReturn(download(edited));
        var result = service.readTableOfContents(USER_ID, FILE_ID, 2);
        assertTrue(result.present());
        assertTrue(result.instruction().startsWith("TOC "));
        assertTrue(result.updateFieldsOnOpen());
        assertEquals("body/p1", result.blockId());
    }

    @Test
    void insertsTocAtEndAndSupportsNoHyperlinksOrTitle() throws Exception {
        prepare(simpleDocument());

        service.createOrRefreshTableOfContents(USER_ID, FILE_ID, VERSION,
                request("END", 2, false, ""));

        try (XWPFDocument document = open(saved())) {
            assertEquals("标题", document.getParagraphs().getFirst().getText());
            String instruction = instruction(document.getParagraphs().getLast());
            assertTrue(instruction.contains("\\o \"1-2\""));
            assertFalse(instruction.contains("\\h"));
        }
    }

    @Test
    void refreshExistingTocDoesNotDuplicateField() throws Exception {
        prepare(tocDocument());

        service.createOrRefreshTableOfContents(USER_ID, FILE_ID, VERSION,
                request("START", 3, true, "目录"));

        try (XWPFDocument document = open(saved())) {
            assertEquals(1, document.getParagraphs().stream()
                    .filter(paragraph -> instruction(paragraph).startsWith("TOC "))
                    .count());
            assertTrue(document.getSettings().getCTSettings().isSetUpdateFields());
        }
    }

    @Test
    void rejectsInvalidPositionLevelAndTitleBeforeSaving() throws Exception {
        prepare(simpleDocument());
        assertThrows(BusinessException.class, () -> service.createOrRefreshTableOfContents(
                USER_ID, FILE_ID, VERSION, request("MIDDLE", 3, true, "目录")));
        assertThrows(BusinessException.class, () -> service.createOrRefreshTableOfContents(
                USER_ID, FILE_ID, VERSION, request("END", 10, true, "目录")));
        assertThrows(BusinessException.class, () -> service.createOrRefreshTableOfContents(
                USER_ID, FILE_ID, VERSION, request("END", 3, true, "x".repeat(101))));
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    private void prepare(byte[] source) {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("toc.docx");
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

    private static byte[] simpleDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("标题");
            document.createParagraph().createRun().setText("正文");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] tocDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("标题");
            var paragraph = document.createParagraph();
            XWPFRun begin = paragraph.createRun();
            begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            XWPFRun instr = paragraph.createRun();
            instr.getCTR().addNewInstrText().setStringValue("TOC \\o \"1-3\" \\h \\z \\u");
            XWPFRun separate = paragraph.createRun();
            separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            XWPFRun end = paragraph.createRun();
            end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static String instruction(XWPFParagraph paragraph) {
        return paragraph.getRuns().stream()
                .flatMap(run -> run.getCTR().getInstrTextList().stream())
                .map(text -> text.getStringValue())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
    }

    private static CreateDocxTableOfContentsDTO request(
            String position, int level, boolean links, String title) {
        var request = new CreateDocxTableOfContentsDTO();
        request.setPosition(position);
        request.setMaxLevel(level);
        request.setHyperlinks(links);
        request.setTitle(title);
        return request;
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
        return new XWPFDocument(new java.io.ByteArrayInputStream(bytes));
    }
}
