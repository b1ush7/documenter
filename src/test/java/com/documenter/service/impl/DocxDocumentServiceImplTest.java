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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocxDocumentServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int SOURCE_VERSION = 1;

    private final DocumentVersionService versionService = mock(DocumentVersionService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final VersionWriter versionWriter = mock(VersionWriter.class);
    private final FileService fileService = mock(FileService.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(
            versionService, fileStorage, versionWriter, fileService);

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
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION))).thenReturn(createdVersion());
    }

    private byte[] storedDocxBytes() {
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorage).store(content.capture(), eq("docx"));
        assertTrue(content.getValue().length > 0);
        return content.getValue();
    }

    private void verifyVersionWasAppended() {
        verify(versionWriter).appendVersion(eq(FILE_ID), eq(USER_ID),
                any(VersionWriter.VersionContent.class), eq(ChangeType.MANUAL_EDIT),
                any(), any(), eq(SOURCE_VERSION));
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
