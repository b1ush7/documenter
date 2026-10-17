package com.documenter.service.impl;

import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real OOXML round trips; ownership and version repositories remain substituted. */
class DocxHeaderFooterTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(versions, storage, writer,
            mock(FileService.class), mock(DocxTemplateGenerationService.class));

    @Test
    void structureAddsStableContainersWithoutChangingBodyPathsAndIncludesTableParagraphs() throws Exception {
        byte[] source = fixture();
        prepare(source);
        var first = service.readStructure(7L, 10L, 1);
        assertEquals(first, service.readStructure(7L, 10L, 1));
        assertEquals("body/p0", first.blocks().getFirst().blockId());
        assertEquals("body/p1", first.blocks().get(1).blockId());
        assertEquals(3, first.blocks().stream().filter(b -> b.type().equals("HEADER")).count());
        assertEquals(1, first.blocks().stream().filter(b -> b.type().equals("FOOTER")).count());
        assertTrue(find(first.blocks(), "Odd header").blockId().matches("header\\d+/p\\d+"));
        assertTrue(find(first.blocks(), "First header").blockId().startsWith("header"));
        assertTrue(find(first.blocks(), "Even header").blockId().startsWith("header"));
        assertTrue(find(first.blocks(), "Header table").blockId().matches("header\\d+/t0/r0/c0/p0"));
        assertTrue(find(first.blocks(), "Footer note").blockId().matches("footer\\d+/p\\d+"));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void textReplacementPreservesOtherPartsFieldsFormattingAndSectionReferences() throws Exception {
        byte[] source = fixture();
        prepare(source);
        var structure = service.readStructure(7L, 10L, 1);
        service.replaceParagraphText(7L, 10L, 1, request(
                edit(find(structure.blocks(), "Odd header").blockId(), "New shared header"),
                edit(find(structure.blocks(), "Header table").blockId(), "New table text"),
                edit(find(structure.blocks(), "Footer note").blockId(), "New footer")));
        try (var before = open(source); var after = open(saved())) {
            assertEquals("Body one", after.getParagraphs().getFirst().getText());
            assertEquals("Body two", after.getParagraphs().get(1).getText());
            assertEquals(before.getDocument().getBody().getSectPr().xmlText(), after.getDocument().getBody().getSectPr().xmlText());
            assertEquals(before.getParagraphs().getFirst().getCTP().getPPr().getSectPr().xmlText(),
                    after.getParagraphs().getFirst().getCTP().getPPr().getSectPr().xmlText());
            var rootPolicy = new XWPFHeaderFooterPolicy(after, after.getDocument().getBody().getSectPr());
            var firstSection = new XWPFHeaderFooterPolicy(after, after.getParagraphs().getFirst().getCTP().getPPr().getSectPr());
            assertTrue(rootPolicy.getDefaultHeader().getText().contains("New shared header"));
            assertEquals(rootPolicy.getDefaultHeader().getPackagePart().getPartName(),
                    firstSection.getDefaultHeader().getPackagePart().getPartName());
            assertEquals("New table text", rootPolicy.getDefaultHeader().getTables().getFirst().getRow(0).getCell(0).getText());
            assertTrue(rootPolicy.getFirstPageHeader().getText().contains("First header"));
            assertTrue(rootPolicy.getEvenPageHeader().getText().contains("Even header"));
            var header = rootPolicy.getDefaultHeader().getParagraphs().stream()
                    .filter(p -> p.getText().equals("New shared header")).findFirst().orElseThrow();
            assertEquals(ParagraphAlignment.RIGHT, header.getAlignment());
            assertTrue(header.getRuns().getFirst().isBold());
            assertEquals("0055AA", header.getRuns().getFirst().getColor());
            assertEquals(pageField(before), pageField(after));
            assertTrue(after.getFooterList().getFirst().getText().contains("New footer"));
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.MANUAL_EDIT), any(), any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FIRST", "EVEN"})
    void firstAndEvenHeaderCanBeTargetedWithoutChangingDefaultHeader(String kind) throws Exception {
        prepare(fixture());
        var structure = service.readStructure(7L, 10L, 1);
        String old = kind.equals("FIRST") ? "First header" : "Even header";
        service.replaceParagraphText(7L, 10L, 1, request(edit(find(structure.blocks(), old).blockId(), "Updated " + kind)));
        try (var after = open(saved())) {
            var policy = new XWPFHeaderFooterPolicy(after);
            assertTrue(policy.getDefaultHeader().getText().contains("Odd header"));
            var target = kind.equals("FIRST") ? policy.getFirstPageHeader() : policy.getEvenPageHeader();
            assertTrue(target.getText().contains("Updated " + kind));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLEX_FIELD", "SIMPLE_FIELD", "DRAWING", "HYPERLINK", "BOOKMARK", "CONTENT_CONTROL"})
    void complexParagraphsAreRejectedAfterEarlierEditWithoutSavingPartialResult(String kind) throws Exception {
        byte[] source = document(doc -> {
            doc.createParagraph().createRun().setText("Original body");
            var header = doc.createHeader(HeaderFooterType.DEFAULT);
            var paragraph = header.createParagraph();
            paragraph.createRun().setText("Protected text");
            addProtectedContent(paragraph, kind);
        });
        prepare(source);
        var target = find(service.readStructure(7L, 10L, 1).blocks(), "Protected text", true);
        assertEquals(422, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit("body/p0", "Must not persist"), edit(target.blockId(), "Do not destroy")))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void bodyPageFieldsAreProtectedBySameCheck() throws Exception {
        prepare(document(doc -> {
            var paragraph = doc.createParagraph();
            addProtectedContent(paragraph, "COMPLEX_FIELD");
        }));
        assertEquals(422, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit("body/p0", "Cannot replace page number")))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"header99/p0", "footer0/p999", "header0/t99/r0/c0/p0", "header-1/p0",
            "header999999999999999999999/p0", "unknown/p0", "header0/t0/r0/c0", "header0/p0/extra"})
    void invalidOrMissingTargetsNeverSave(String target) throws Exception {
        prepare(fixture());
        assertEquals(400, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit(target, "invalid")))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void deletingSectionBoundaryCannotRemoveHeaderFooterReferences() throws Exception {
        prepare(fixture());
        var request = new EditDocxParagraphsDTO();
        var operation = new EditDocxParagraphsDTO.Operation();
        operation.setType("DELETE");
        operation.setBlockId("body/p0");
        request.setOperations(List.of(operation));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editParagraphs(7L, 10L, 1, request)).getCode());
        verifyNoInteractions(storage, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT_BEFORE", "INSERT_AFTER"})
    void insertionBesideSectionBoundaryDoesNotDuplicateSection(String type) throws Exception {
        byte[] source = fixture();
        prepare(source);
        var request = new EditDocxParagraphsDTO();
        var operation = new EditDocxParagraphsDTO.Operation();
        operation.setType(type);
        operation.setBlockId("body/p0");
        operation.setText("Inserted");
        request.setOperations(List.of(operation));
        service.editParagraphs(7L, 10L, 1, request);
        try (var original = open(source); var edited = open(saved())) {
            assertEquals(3, edited.getParagraphs().size());
            assertEquals(1, edited.getParagraphs().stream()
                    .filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr()).count());
            var boundary = edited.getParagraphs().stream().filter(p -> p.getText().equals("Body one")).findFirst().orElseThrow();
            assertEquals(original.getParagraphs().getFirst().getCTP().getPPr().getSectPr().xmlText(),
                    boundary.getCTP().getPPr().getSectPr().xmlText());
            assertEquals(original.getDocument().getBody().getSectPr().xmlText(), edited.getDocument().getBody().getSectPr().xmlText());
        }
    }

    @Test
    void existingVersionComparisonIncludesHeaderChanges() throws Exception {
        prepare(fixture());
        var target = find(service.readStructure(7L, 10L, 1).blocks(), "Odd header").blockId();
        service.replaceParagraphText(7L, 10L, 1, request(edit(target, "Updated header")));
        byte[] edited = saved();
        var asset = new FileAsset();
        asset.setExtension("docx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        when(versions.downloadVersion(7L, 10L, 2)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(edited), edited.length, asset.getContentType(), "edited.docx"));
        var comparison = new DocumentComparisonServiceImpl(service, mock(com.documenter.service.XlsxDocumentService.class))
                .compare(7L, 10L, 1, 2);
        assertEquals(1, comparison.changes().size());
        var change = comparison.changes().getFirst();
        assertEquals(target, change.key());
        assertEquals("Odd header", change.before());
        assertEquals("Updated header", change.after());
    }

    @Test
    void duplicateHeaderTargetIsRejectedBeforeReadingOrSaving() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit("header0/p0", "one"), edit("header0/p0", "two")))).getCode());
        verifyNoInteractions(versions, storage, writer);
    }

    @Test
    void ownershipFailureStopsHeaderReadAndWrite() {
        when(versions.downloadVersion(7L, 10L, 1)).thenThrow(new BusinessException(404, "文件不存在"));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.readStructure(7L, 10L, 1)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit("header0/p0", "unowned")))).getCode());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void versionConflictCleansNewArtifactWithoutTouchingOriginalHeader() throws Exception {
        prepare(fixture());
        var target = find(service.readStructure(7L, 10L, 1).blocks(), "Odd header").blockId();
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        when(storage.delete("new.docx")).thenReturn(true);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.replaceParagraphText(7L, 10L, 1,
                request(edit(target, "stale")))).getCode());
        verify(storage).delete("new.docx");
    }

    private static byte[] fixture() throws Exception {
        return document(doc -> {
            var firstBody = doc.createParagraph();
            firstBody.createRun().setText("Body one");
            doc.createParagraph().createRun().setText("Body two");
            var header = doc.createHeader(HeaderFooterType.DEFAULT);
            var paragraph = header.createParagraph();
            paragraph.setAlignment(ParagraphAlignment.RIGHT);
            var run = paragraph.createRun();
            run.setBold(true);
            run.setColor("0055AA");
            run.setText("Odd header");
            header.createTable(1, 1).getRow(0).getCell(0).setText("Header table");
            doc.createHeader(HeaderFooterType.FIRST).createParagraph().createRun().setText("First header");
            doc.createHeader(HeaderFooterType.EVEN).createParagraph().createRun().setText("Even header");
            var footer = doc.createFooter(HeaderFooterType.DEFAULT);
            footer.createParagraph().createRun().setText("Footer note");
            addProtectedContent(footer.createParagraph(), "COMPLEX_FIELD");
            var section = doc.getDocument().getBody().getSectPr();
            section.addNewTitlePg();
            firstBody.getCTP().addNewPPr().setSectPr((CTSectPr) section.copy());
        });
    }

    private static void addProtectedContent(XWPFParagraph paragraph, String kind) {
        switch (kind) {
            case "COMPLEX_FIELD" -> {
                paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
                paragraph.createRun().getCTR().addNewInstrText().setStringValue(" PAGE ");
                paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
                paragraph.createRun().setText("1");
                paragraph.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
            }
            case "SIMPLE_FIELD" -> paragraph.getCTP().addNewFldSimple().setInstr("PAGE");
            case "DRAWING" -> paragraph.createRun().getCTR().addNewDrawing();
            case "HYPERLINK" -> paragraph.createHyperlinkRun("https://example.invalid").setText("Link");
            case "BOOKMARK" -> paragraph.getCTP().addNewBookmarkStart().setName("protected");
            case "CONTENT_CONTROL" -> paragraph.getCTP().addNewSdt();
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private static String pageField(XWPFDocument document) {
        return document.getFooterList().getFirst().getParagraphs().stream()
                .filter(p -> p.getCTP().xmlText().contains("instrText")).findFirst().orElseThrow().getCTP().xmlText();
    }

    private void prepare(byte[] bytes) {
        var asset = new FileAsset();
        asset.setId(10L);
        asset.setUserId(7L);
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension("docx");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(bytes), bytes.length, asset.getContentType(), "source.docx"));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("new.docx");
        var created = new DocumentVersion();
        created.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), any(), any(), any(), eq(1))).thenReturn(created);
    }

    private byte[] saved() {
        var capture = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(capture.capture(), eq("docx"));
        return capture.getValue();
    }

    private static DocxStructureVO.Block find(List<DocxStructureVO.Block> blocks, String text) { return find(blocks, text, false); }
    private static DocxStructureVO.Block find(List<DocxStructureVO.Block> blocks, String text, boolean prefix) {
        for (var block : blocks) {
            if ((block.type().equals("PARAGRAPH") || block.type().equals("HEADING")) && block.text() != null
                    && (prefix ? block.text().startsWith(text) : text.equals(block.text()))) return block;
            var child = find(block.children(), text, prefix);
            if (child != null) return child;
        }
        return null;
    }

    private static ReplaceDocxTextDTO.Operation edit(String id, String value) {
        var operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId(id);
        operation.setText(value);
        return operation;
    }
    private static ReplaceDocxTextDTO request(ReplaceDocxTextDTO.Operation... operations) {
        var request = new ReplaceDocxTextDTO();
        request.setOperations(List.of(operations));
        return request;
    }
    private static XWPFDocument open(byte[] bytes) throws Exception { return new XWPFDocument(new ByteArrayInputStream(bytes)); }
    private static byte[] document(Consumer<XWPFDocument> editor) throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            editor.accept(doc);
            doc.write(bytes);
            return bytes.toByteArray();
        }
    }
}
