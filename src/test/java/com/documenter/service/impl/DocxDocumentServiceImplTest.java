package com.documenter.service.impl;

import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.dto.GenerateDocxDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.component.EditPlanExecutor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class DocxDocumentServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int SOURCE_VERSION = 1;

    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final VersionWriter versionWriter = mock(VersionWriter.class);
    private final FileService fileService = mock(FileService.class);
    private final DocxTemplateGenerationService templateGeneration = mock(DocxTemplateGenerationService.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(
            versionService, fileStorage, versionWriter, fileService, templateGeneration);

    @Test
    void templateParametersDispatchToTemplateGenerationWithoutCreatingStructuredDocument() {
        var request = new GenerateDocxDTO();
        request.setTemplateId(11L);
        request.setTemplateVersion(3);
        request.setVariables(java.util.Map.of("title", "周报"));
        var result = new FileAssetVO();
        when(templateGeneration.generate(USER_ID, request)).thenReturn(result);
        assertEquals(result, service.generate(USER_ID, request));
        verify(templateGeneration).generate(USER_ID, request);
        verifyNoInteractions(fileService, fileStorage, versionService, versionWriter);
    }

    @Test
    void mixedFormattingAndTextPlanPreservesContentAndListDefinitions() throws Exception {
        prepareEdit(createDocument(document -> {
            for (String text : List.of("heading", "bullet", "number one", "number two")) {
                document.createParagraph().createRun().setText(text);
            }
        }));
        var plan = new DocumentEditPlan("mixed format", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "new heading", null),
                format("body/p0", DocumentEditPlan.FormatType.SET_HEADING, 2),
                format("body/p1", DocumentEditPlan.FormatType.SET_BULLET, null),
                format("body/p2", DocumentEditPlan.FormatType.SET_NUMBERED, null),
                format("body/p3", DocumentEditPlan.FormatType.SET_NUMBERED, null)), null);

        service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test");

        try (var document = open(storedDocxBytes())) {
            assertEquals("new heading", document.getParagraphs().get(0).getText());
            assertEquals("Heading2", document.getParagraphs().get(0).getStyle());
            assertEquals("bullet", document.getParagraphs().get(1).getNumFmt());
            assertEquals("decimal", document.getParagraphs().get(2).getNumFmt());
            assertEquals(document.getParagraphs().get(2).getNumID(), document.getParagraphs().get(3).getNumID());
        }
        verifyVersionWasAppended(ChangeType.AI_EDIT);
    }

    @Test
    void clearingListOrRestoringNormalPreservesTextAndOnlyRemovesRequestedFormatting() throws Exception {
        prepareEdit(createDocument(document -> {
            var numbered = document.createNumbering();
            var definition = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();
            definition.setAbstractNumId(java.math.BigInteger.ZERO);
            var level = definition.addNewLvl();
            level.setIlvl(java.math.BigInteger.ZERO);
            level.addNewNumFmt().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.DECIMAL);
            var id = numbered.addNum(numbered.addAbstractNum(new org.apache.poi.xwpf.usermodel.XWPFAbstractNum(definition)));
            for (String text : List.of("keep heading", "normal")) {
                var paragraph = document.createParagraph();
                paragraph.setStyle("Heading2");
                paragraph.setNumID(id);
                paragraph.setNumILvl(java.math.BigInteger.ZERO);
                paragraph.createRun().setText(text);
            }
        }));
        var plan = new DocumentEditPlan("clear", List.of(
                format("body/p0", DocumentEditPlan.FormatType.CLEAR_LIST, null),
                format("body/p1", DocumentEditPlan.FormatType.SET_NORMAL, null)), null);

        service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test");

        try (var document = open(storedDocxBytes())) {
            assertEquals("keep heading", document.getParagraphs().get(0).getText());
            assertEquals("Heading2", document.getParagraphs().get(0).getStyle());
            assertEquals("Normal", document.getParagraphs().get(1).getStyle());
            assertEquals("normal", document.getParagraphs().get(1).getText());
            assertNull(document.getParagraphs().get(0).getNumID());
            assertNull(document.getParagraphs().get(1).getNumID());
        }
    }

    @Test
    void failureAfterFormattingDoesNotSavePartialResult() throws Exception {
        prepareEdit(createDocument(document -> {
            document.createParagraph().createRun().setText("heading");
            var cell = document.createTable(1, 1).getRow(0).getCell(0);
            try (var cursor = cell.getCTTc().newCursor()) { cursor.toEndToken(); cell.insertNewTbl(cursor); }
        }));
        var plan = new DocumentEditPlan("fail", List.of(
                format("body/p0", DocumentEditPlan.FormatType.SET_HEADING, 2),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t0/r0/c0", "text", null)), null);
        assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test"));
        verifyNoInteractions(fileStorage, versionWriter);
    }

    private static DocumentEditPlan.Operation format(String target, DocumentEditPlan.FormatType type, Integer level) {
        return new DocumentEditPlan.Operation("FORMAT_PARAGRAPH", target, null, null, null,
                new DocumentEditPlan.ParagraphFormat(type, level));
    }

    @Test
    void imageAndTextPlanProducesOneVersionWithEmbeddedImage() throws Exception {
        byte[] source = createDocument(document -> {
            document.createParagraph().createRun().setText("image anchor");
            document.createParagraph().createRun().setText("old text");
        });
        prepareEdit(source);
        when(versionService.downloadVersion(USER_ID, 30L, 2)).thenReturn(imageDownload());
        var reference = new DocumentEditPlan.ImageReference(30L, 2, 80, 40);
        var plan = new DocumentEditPlan("image and text", List.of(
                new DocumentEditPlan.Operation("INSERT_IMAGE", "body/p0", null, "illustration", reference),
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p1", "new text", null)), null);

        service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test");

        try (var document = open(storedDocxBytes())) {
            assertEquals("image anchor", document.getParagraphs().getFirst().getText());
            assertEquals("new text", document.getParagraphs().get(1).getText());
            assertEquals(1, document.getAllPictures().size());
            assertEquals(1, document.getParagraphs().getFirst().getRuns().stream()
                    .mapToInt(run -> run.getEmbeddedPictures().size()).sum());
            var picture = document.getParagraphs().getFirst().getRuns().getLast().getEmbeddedPictures().getFirst();
            assertEquals(org.apache.poi.util.Units.pixelToEMU(80), picture.getCTPicture().getSpPr().getXfrm().getExt().getCx());
            assertEquals(org.apache.poi.util.Units.pixelToEMU(40), picture.getCTPicture().getSpPr().getXfrm().getExt().getCy());
        }
        verifyVersionWasAppended(ChangeType.AI_EDIT);
        verify(versionService).downloadVersion(USER_ID, 30L, 2);
    }

    @Test
    void imagePermissionFailureAfterTextEditDoesNotSavePartialResult() throws Exception {
        prepareEdit(createDocument(document -> {
            document.createParagraph().createRun().setText("old text");
            document.createParagraph().createRun().setText("image anchor");
        }));
        when(versionService.downloadVersion(USER_ID, 30L, 2))
                .thenThrow(new BusinessException(404, "图片不存在"));
        var plan = new DocumentEditPlan("mixed", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "new text", null),
                new DocumentEditPlan.Operation("INSERT_IMAGE", "body/p1", null, null,
                        new DocumentEditPlan.ImageReference(30L, 2, 80, 40))), null);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test")).getCode());

        verifyNoInteractions(fileStorage, versionWriter);
    }

    @Test
    void laterTableFailureDoesNotSaveAnAlreadyInsertedImage() throws Exception {
        prepareEdit(createDocument(document -> {
            document.createParagraph().createRun().setText("image anchor");
            var cell = document.createTable(1, 1).getRow(0).getCell(0);
            try (var cursor = cell.getCTTc().newCursor()) {
                cursor.toEndToken();
                cell.insertNewTbl(cursor);
            }
        }));
        when(versionService.downloadVersion(USER_ID, 30L, 2)).thenReturn(imageDownload());
        var plan = new DocumentEditPlan("mixed", List.of(
                new DocumentEditPlan.Operation("INSERT_IMAGE", "body/p0", null, null,
                        new DocumentEditPlan.ImageReference(30L, 2, 80, 40)),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t0/r0/c0", "new", null)), null);

        assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test"));

        verify(versionService).downloadVersion(USER_ID, 30L, 2);
        verifyNoInteractions(fileStorage, versionWriter);
    }

    @Test
    void imageVersionThatIsNotAnImageIsRejectedBeforeSaving() throws Exception {
        prepareEdit(createDocument(document -> document.createParagraph().createRun().setText("anchor")));
        when(versionService.downloadVersion(USER_ID, 30L, 2)).thenReturn(download(new byte[0]));
        var plan = new DocumentEditPlan("image", List.of(
                new DocumentEditPlan.Operation("INSERT_IMAGE", "body/p0", null, null,
                        new DocumentEditPlan.ImageReference(30L, 2, 80, 40))), null);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test")).getCode());
        verifyNoInteractions(fileStorage, versionWriter);
    }

    private static FileDownload imageDownload() throws Exception {
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        try (var output = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", output);
            var asset = new FileAsset();
            asset.setId(30L);
            asset.setUserId(USER_ID);
            asset.setExtension("png");
            return new FileDownload(asset, new ByteArrayResource(output.toByteArray()), output.size(),
                    "image/png", "source.png");
        }
    }

    @Test
    void mixedPlanProducesOneReopenableVersionAndPreservesSourceTargets() throws Exception {
        byte[] source = createDocument(document -> {
            document.createParagraph().createRun().setText("first");
            document.createParagraph().createRun().setText("second");
            document.createTable(1, 1).getRow(0).getCell(0).setText("old cell");
        });
        prepareEdit(source);
        var plan = new DocumentEditPlan("mixed edit", List.of(
                new DocumentEditPlan.Operation(" insert_before ", "body/p0", "inserted", null),
                new DocumentEditPlan.Operation("replace_text", "body/p1", "changed second", null),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t0/r0/c0", "changed cell", null)), null);

        var result = new EditPlanExecutor(service, mock(com.documenter.service.XlsxDocumentService.class)).execute(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test");

        assertEquals(2, result.version().getVersionNo());
        assertEquals(1, result.appliedBatches().size());
        try (XWPFDocument document = open(storedDocxBytes())) {
            assertEquals(List.of("inserted", "first", "changed second"),
                    document.getParagraphs().stream().map(p -> p.getText()).toList());
            assertEquals("changed cell", document.getTables().getFirst().getRow(0).getCell(0).getText());
        }
        verifyVersionWasAppended(ChangeType.AI_EDIT);
        verify(versionService).downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION);
    }

    @Test
    void failureAfterParagraphEditDoesNotStoreOrAppendPartialPlan() throws Exception {
        byte[] source = createDocument(document -> {
            document.createParagraph().createRun().setText("original");
            var cell = document.createTable(1, 1).getRow(0).getCell(0);
            try (var cursor = cell.getCTTc().newCursor()) {
                cursor.toEndToken();
                cell.insertNewTbl(cursor);
            }
        });
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION)).thenReturn(download(source));
        var plan = new DocumentEditPlan("mixed edit", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "changed", null),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t0/r0/c0", "new cell", null)), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> new EditPlanExecutor(service, mock(com.documenter.service.XlsxDocumentService.class)).execute(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test"));

        assertEquals(422, error.getCode());
        verifyNoInteractions(fileStorage, versionWriter);
        try (XWPFDocument document = open(source)) {
            assertEquals("original", document.getParagraphs().getFirst().getText());
        }
    }

    @Test
    void mixedPlanConflictCleansTheSingleArtifact() throws Exception {
        byte[] source = createDocument(document -> {
            document.createParagraph().createRun().setText("original");
            document.createTable(1, 1).getRow(0).getCell(0).setText("cell");
        });
        prepareEdit(source);
        when(fileStorage.store(any(byte[].class), eq("docx"))).thenReturn("versions/conflict.docx");
        when(fileStorage.delete("versions/conflict.docx")).thenReturn(true);
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.AI_EDIT),
                any(), any(), eq(SOURCE_VERSION)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        var plan = new DocumentEditPlan("mixed edit", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "changed", null),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t0/r0/c0", "new cell", null)), null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test"));

        assertEquals(409, error.getCode());
        verify(fileStorage).store(any(byte[].class), eq("docx"));
        verify(fileStorage).delete("versions/conflict.docx");
        verifyVersionWasAppended(ChangeType.AI_EDIT);
    }

    @Test
    void invalidTargetInLaterOperationLeavesNoPartialVersion() throws Exception {
        byte[] source = createDocument(document -> document.createParagraph().createRun().setText("original"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION)).thenReturn(download(source));
        var plan = new DocumentEditPlan("invalid", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "changed", null),
                new DocumentEditPlan.Operation("SET_CELL_TEXT", "body/t9/r0/c0", "cell", null)), null);

        assertThrows(BusinessException.class,
                () -> service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test"));

        verifyNoInteractions(fileStorage, versionWriter);
    }

    @Test
    void generatedDocumentCanBeOpenedAndContainsRequestedStructures() throws Exception {
        GenerateDocxDTO request = new GenerateDocxDTO();
        request.setFileName("验收文档.docx");
        request.setTitle("验收标题");
        request.setInstruction("自动化有效性验证");
        request.setBlocks(List.of(
                heading("第一章", 1),
                paragraph("正文内容"),
                list("BULLET_LIST", List.of("项目一", "项目二")),
                list("NUMBERED_LIST", List.of("步骤一", "步骤二")),
                table(List.of(List.of("姓名", "数量"), List.of("示例", "2")))
        ));
        when(fileService.createGeneratedDocx(eq(USER_ID), eq("验收文档.docx"),
                any(byte[].class), eq("自动化有效性验证"))).thenReturn(new FileAssetVO());

        service.generate(USER_ID, request);

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).createGeneratedDocx(eq(USER_ID), eq("验收文档.docx"),
                content.capture(), eq("自动化有效性验证"));
        try (XWPFDocument document = open(content.getValue())) {
            assertEquals(7, document.getParagraphs().size());
            assertEquals("验收标题", document.getParagraphs().get(0).getText());
            assertEquals("第一章", document.getParagraphs().get(1).getText());
            assertEquals("正文内容", document.getParagraphs().get(2).getText());
            assertTrue(document.getParagraphs().get(3).getNumID() != null);
            assertTrue(document.getParagraphs().get(5).getNumID() != null);
            assertEquals(1, document.getTables().size());
            assertEquals("示例", document.getTables().getFirst().getRow(1).getCell(0).getText());
        }
    }

    @Test
    void structureReaderReturnsStablePathsForParagraphsAndTableCells() throws Exception {
        byte[] source = createDocument(document -> {
            var heading = document.createParagraph();
            heading.setStyle("Heading2");
            heading.createRun().setText("结构标题");
            XWPFTable table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("单元格");
        });
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));

        var result = service.readStructure(USER_ID, FILE_ID, SOURCE_VERSION);

        assertEquals(2, result.blocks().size());
        assertEquals("body/p0", result.blocks().get(0).blockId());
        assertEquals("HEADING", result.blocks().get(0).type());
        assertEquals(2, result.blocks().get(0).headingLevel());
        assertEquals("body/t0", result.blocks().get(1).blockId());
        var cell = result.blocks().get(1).children().get(0).children().get(0);
        assertEquals("body/t0/r0/c0", cell.blockId());
        assertEquals("单元格", cell.text());
        assertEquals("body/t0/r0/c0/p0", cell.children().get(0).blockId());
    }

    @Test
    void paragraphReplacementCreatesReopenableDocxVersion() throws Exception {
        byte[] source = createDocument(document -> document.createParagraph().createRun().setText("旧内容"));
        prepareEdit(source);
        ReplaceDocxTextDTO.Operation operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId("body/p0");
        operation.setText("新内容");
        ReplaceDocxTextDTO request = new ReplaceDocxTextDTO();
        request.setInstruction("替换正文");
        request.setOperations(List.of(operation));

        service.replaceParagraphText(USER_ID, FILE_ID, SOURCE_VERSION, request);

        byte[] edited = storedDocxBytes();
        try (XWPFDocument document = open(edited)) {
            assertEquals("新内容", document.getParagraphs().getFirst().getText());
        }
        verifyVersionWasAppended();
    }

    /**
     * 依据来源版本检查编辑冲突，避免多个任务互相覆盖（TODO 4.1）。
     *
     * <p>场景：用户看到的界面基于版本 1，但在他操作期间另一个任务已经把文件改到了版本 2。
     * 此时这次编辑的「来源版本」已经过时，如果仍然保存，就会把版本 2 的改动覆盖掉。
     *
     * <p>验证三点：
     * <ol>
     *   <li>来源版本被当作期望版本传给版本写入，作为乐观锁；</li>
     *   <li>版本已被推进时返回 409，而不是静默覆盖；</li>
     *   <li>冲突时不产生新版本，并且已经落盘的编辑产物要被清理掉 ——
     *       否则每次冲突都会在存储里留下一个没人引用的孤儿文件。</li>
     * </ol>
     */
    @Test
    void staleSourceVersionIsRejectedAndLeavesNoOrphanArtifact() throws Exception {
        byte[] source = createDocument(document -> document.createParagraph().createRun().setText("旧内容"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        when(fileStorage.store(any(byte[].class), eq("docx"))).thenReturn("versions/orphan.docx");
        // 模拟「期间已有其他任务把版本推进到 2」：乐观锁更新影响行数为 0
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION)))
                .thenThrow(new BusinessException(409, "文档已被其他操作修改"));

        ReplaceDocxTextDTO.Operation operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId("body/p0");
        operation.setText("基于旧版本的修改");
        ReplaceDocxTextDTO request = new ReplaceDocxTextDTO();
        request.setOperations(List.of(operation));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replaceParagraphText(USER_ID, FILE_ID, SOURCE_VERSION, request));

        assertEquals(409, error.getCode());
        // 关键：来源版本必须作为期望版本参与冲突判定
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION));
        // 关键：冲突后必须清理已落盘的编辑产物，避免留下孤儿文件
        verify(fileStorage).delete("versions/orphan.docx");
    }

    @Test
    void conflictIsReportedBeforeAnyVersionIsVisible() throws Exception {
        byte[] source = createDocument(document -> document.createParagraph().createRun().setText("旧内容"));
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        when(fileStorage.store(any(byte[].class), eq("docx"))).thenReturn("versions/orphan.docx");
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION)))
                .thenThrow(new BusinessException(409, "文档已被其他操作修改"));

        ReplaceDocxTextDTO request = new ReplaceDocxTextDTO();
        ReplaceDocxTextDTO.Operation operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId("body/p0");
        operation.setText("x");
        request.setOperations(List.of(operation));

        assertThrows(BusinessException.class,
                () -> service.replaceParagraphText(USER_ID, FILE_ID, SOURCE_VERSION, request));

        // 冲突时不能产生任何成功返回值，调用方据此判断需要刷新后重试
        verify(versionWriter, times(1)).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION));
    }

    @Test
    void paragraphInsertionAndDeletionCreateReopenableDocxVersion() throws Exception {
        byte[] source = createDocument(document -> {
            document.createParagraph().createRun().setText("第一段");
            document.createParagraph().createRun().setText("待删除");
        });
        prepareEdit(source);
        EditDocxParagraphsDTO.Operation insert = paragraphEdit("INSERT_AFTER", "body/p0", "新增段落");
        EditDocxParagraphsDTO.Operation delete = paragraphEdit("DELETE", "body/p1", null);
        EditDocxParagraphsDTO request = new EditDocxParagraphsDTO();
        request.setInstruction("插入并删除段落");
        request.setOperations(List.of(insert, delete));

        service.editParagraphs(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XWPFDocument document = open(storedDocxBytes())) {
            assertEquals(List.of("第一段", "新增段落"),
                    document.getParagraphs().stream().map(paragraph -> paragraph.getText()).toList());
        }
        verifyVersionWasAppended();
    }

    @Test
    void tableCellAndRowEditsCreateReopenableDocxVersion() throws Exception {
        byte[] source = createDocument(document -> {
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("旧值");
            table.getRow(0).getCell(1).setText("1");
        });
        prepareEdit(source);
        EditDocxTableDTO.Operation setCell = tableEdit("SET_CELL_TEXT", "body/t0/r0/c0");
        setCell.setText("新值");
        EditDocxTableDTO.Operation insertRow = tableEdit("INSERT_ROW_AFTER", "body/t0/r0");
        insertRow.setValues(List.of("第二行", "2"));
        EditDocxTableDTO request = new EditDocxTableDTO();
        request.setInstruction("修改单元格并新增行");
        request.setOperations(List.of(setCell, insertRow));

        service.editTable(USER_ID, FILE_ID, SOURCE_VERSION, request);

        try (XWPFDocument document = open(storedDocxBytes())) {
            XWPFTable table = document.getTables().getFirst();
            assertEquals(2, table.getNumberOfRows());
            assertEquals("新值", table.getRow(0).getCell(0).getText());
            assertEquals("第二行", table.getRow(1).getCell(0).getText());
            assertEquals("2", table.getRow(1).getCell(1).getText());
        }
        verifyVersionWasAppended();
    }

    private void prepareEdit(byte[] source) {
        when(versionService.downloadVersion(USER_ID, FILE_ID, SOURCE_VERSION))
                .thenReturn(download(source));
        when(fileStorage.store(any(byte[].class), eq("docx"))).thenReturn("versions/v2.docx");
        when(versionWriter.appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), any(ChangeType.class),
                any(), any(), eq(SOURCE_VERSION))).thenReturn(createdVersion());
    }

    private byte[] storedDocxBytes() {
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorage).store(content.capture(), eq("docx"));
        assertTrue(content.getValue().length > 0);
        return content.getValue();
    }

    private void verifyVersionWasAppended() {
        verifyVersionWasAppended(ChangeType.MANUAL_EDIT);
    }

    private void verifyVersionWasAppended(ChangeType changeType) {
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(changeType),
                any(), any(), eq(SOURCE_VERSION));
    }

    @Test
    void outerConfirmationRollbackCleansStoredArtifact() throws Exception {
        prepareEdit(createDocument(document -> document.createParagraph().createRun().setText("old")));
        var plan = new DocumentEditPlan("edit", List.of(
                new DocumentEditPlan.Operation("REPLACE_TEXT", "body/p0", "new", null)), null);
        when(fileStorage.delete("versions/v2.docx")).thenReturn(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.editPlan(USER_ID, FILE_ID, SOURCE_VERSION, plan, "test");
            for (var synchronization : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(fileStorage).delete("versions/v2.docx");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static GenerateDocxDTO.Block heading(String text, int level) {
        GenerateDocxDTO.Block block = new GenerateDocxDTO.Block();
        block.setType("HEADING");
        block.setText(text);
        block.setLevel(level);
        return block;
    }

    private static GenerateDocxDTO.Block paragraph(String text) {
        GenerateDocxDTO.Block block = new GenerateDocxDTO.Block();
        block.setType("PARAGRAPH");
        block.setText(text);
        return block;
    }

    private static GenerateDocxDTO.Block list(String type, List<String> items) {
        GenerateDocxDTO.Block block = new GenerateDocxDTO.Block();
        block.setType(type);
        block.setItems(items);
        return block;
    }

    private static GenerateDocxDTO.Block table(List<List<String>> rows) {
        GenerateDocxDTO.Block block = new GenerateDocxDTO.Block();
        block.setType("TABLE");
        block.setRows(rows);
        return block;
    }

    private static EditDocxParagraphsDTO.Operation paragraphEdit(String type, String blockId, String text) {
        EditDocxParagraphsDTO.Operation operation = new EditDocxParagraphsDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setText(text);
        return operation;
    }

    private static EditDocxTableDTO.Operation tableEdit(String type, String blockId) {
        EditDocxTableDTO.Operation operation = new EditDocxTableDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        return operation;
    }

    private static byte[] createDocument(Consumer<XWPFDocument> writer) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.accept(document);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static XWPFDocument open(byte[] content) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(content));
    }

    private static FileDownload download(byte[] content) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setDisplayName("source.docx");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension("docx");
        return new FileDownload(asset, new ByteArrayResource(content), content.length,
                asset.getContentType(), asset.getDisplayName());
    }

    private static DocumentVersion createdVersion() {
        DocumentVersion version = new DocumentVersion();
        version.setId(20L);
        version.setFileId(FILE_ID);
        version.setUserId(USER_ID);
        version.setVersionNo(2);
        version.setParentVersion(SOURCE_VERSION);
        version.setStorageKey("versions/v2.docx");
        version.setSizeBytes(1L);
        version.setSha256("hash");
        version.setChangeType(ChangeType.MANUAL_EDIT.name());
        return version;
    }
}
