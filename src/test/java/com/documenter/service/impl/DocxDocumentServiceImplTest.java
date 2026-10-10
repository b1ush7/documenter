package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.entity.DocumentVersion;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
class DocxDocumentServiceImplTest {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void readsParagraphHeadingTableAndImageInDocumentOrder() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading2");
            heading.createRun().setText("项目标题");

            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("正文");
            run.addPicture(new java.io.ByteArrayInputStream(ONE_PIXEL_PNG),
                    Document.PICTURE_TYPE_PNG, "pixel.png", 9525, 9525);

            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("左侧");
            table.getRow(0).getCell(1).setText("右侧");
            document.write(output);
            content = output.toByteArray();
        }

        DocumentVersionService versionService = downloadService(
                7L, 11L, 3, download(content, DOCX_CONTENT_TYPE, "docx"));

        DocxStructureVO structure = new DocxDocumentServiceImpl(
                versionService, unusedStorage(), unusedWriter())
                .readStructure(7L, 11L, 3);

        assertEquals(11L, structure.fileId());
        assertEquals(3, structure.versionNo());
        assertEquals(3, structure.blocks().size());
        assertEquals("body/p0", structure.blocks().get(0).blockId());
        assertEquals("HEADING", structure.blocks().get(0).type());
        assertEquals(2, structure.blocks().get(0).headingLevel());
        assertEquals("IMAGE", structure.blocks().get(1).children().getFirst().type());
        assertTrue(structure.blocks().get(1).children().getFirst().name().endsWith(".png"));
        assertEquals("image/png", structure.blocks().get(1).children().getFirst().contentType());
        assertEquals("TABLE", structure.blocks().get(2).type());
        assertEquals("左侧", structure.blocks().get(2).children().getFirst()
                .children().getFirst().text());
        assertEquals("body/t0/r0/c1", structure.blocks().get(2).children().getFirst()
                .children().get(1).blockId());
    }

    @Test
    void rejectsNonDocxVersion() {
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(new byte[]{1, 2, 3}, "application/pdf", "pdf"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DocxDocumentServiceImpl(versionService, unusedStorage(), unusedWriter())
                        .readStructure(1L, 2L, 1));

        assertEquals(400, exception.getCode());
    }

    @Test
    void replacesParagraphTextAndCreatesVersionWhileKeepingParagraphStyle() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setStyle("Heading1");
            XWPFRun run = paragraph.createRun();
            run.setBold(true);
            run.setText("旧内容");
            document.write(output);
            source = output.toByteArray();
        }

        MemoryStorage storage = new MemoryStorage();
        CapturingVersionWriter writer = new CapturingVersionWriter();
        ReplaceDocxTextDTO request = request("body/p0", "新内容");
        DocumentVersionService versionService = downloadService(
                8L, 12L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        VersionVO result = new DocxDocumentServiceImpl(versionService, storage, writer)
                .replaceParagraphText(8L, 12L, 1, request);

        assertEquals(2, result.getVersionNo());
        assertEquals(ChangeType.MANUAL_EDIT, writer.changeType);
        assertEquals(1, writer.expectVersion);
        assertEquals(Digests.sha256Hex(storage.content), writer.content.sha256());
        try (XWPFDocument edited = new XWPFDocument(new ByteArrayInputStream(storage.content))) {
            assertEquals("新内容", edited.getParagraphs().getFirst().getText());
            assertEquals("Heading1", edited.getParagraphs().getFirst().getStyle());
            assertTrue(edited.getParagraphs().getFirst().getRuns().getFirst().isBold());
        }
    }

    @Test
    void rejectsReplacingParagraphThatContainsImage() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFRun run = document.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(ONE_PIXEL_PNG), Document.PICTURE_TYPE_PNG,
                    "pixel.png", 9525, 9525);
            document.write(output);
            source = output.toByteArray();
        }
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DocxDocumentServiceImpl(versionService, new MemoryStorage(), unusedWriter())
                        .replaceParagraphText(1L, 2L, 1, request("body/p0", "不能覆盖图片")));

        assertEquals(422, exception.getCode());
    }

    @Test
    void insertsBeforeAndAfterThenDeletesParagraphWhileKeepingReferenceStyle() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph first = document.createParagraph();
            first.setStyle("Heading2");
            XWPFRun firstRun = first.createRun();
            firstRun.setItalic(true);
            firstRun.setText("参照段落");
            document.createParagraph().createRun().setText("待删除段落");
            document.write(output);
            source = output.toByteArray();
        }

        EditDocxParagraphsDTO request = paragraphEditRequest(
                paragraphOperation("INSERT_BEFORE", "body/p0", "前置段落"),
                paragraphOperation("INSERT_AFTER", "body/p0", "后置段落"),
                paragraphOperation("DELETE", "body/p1", null));
        MemoryStorage storage = new MemoryStorage();
        CapturingVersionWriter writer = new CapturingVersionWriter();
        DocumentVersionService versionService = downloadService(
                2L, 3L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        new DocxDocumentServiceImpl(versionService, storage, writer)
                .editParagraphs(2L, 3L, 1, request);

        try (XWPFDocument edited = new XWPFDocument(new ByteArrayInputStream(storage.content))) {
            assertEquals(List.of("前置段落", "参照段落", "后置段落"),
                    edited.getParagraphs().stream().map(XWPFParagraph::getText).toList());
            assertEquals("Heading2", edited.getParagraphs().getFirst().getStyle());
            assertTrue(edited.getParagraphs().getFirst().getRuns().getFirst().isItalic());
            assertEquals("Heading2", edited.getParagraphs().get(2).getStyle());
        }
    }

    @Test
    void rejectsDeletingOnlyParagraphFromTableCell() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createTable(1, 1).getRow(0).getCell(0).setText("唯一段落");
            document.write(output);
            source = output.toByteArray();
        }
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DocxDocumentServiceImpl(versionService, new MemoryStorage(), unusedWriter())
                        .editParagraphs(1L, 2L, 1, paragraphEditRequest(
                                paragraphOperation("DELETE", "body/t0/r0/c0/p0", null))));

        assertEquals(422, exception.getCode());
    }

    @Test
    void insertsParagraphInsideTableCell() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createTable(1, 1).getRow(0).getCell(0).setText("第一段");
            document.write(output);
            source = output.toByteArray();
        }
        MemoryStorage storage = new MemoryStorage();
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        new DocxDocumentServiceImpl(versionService, storage, new CapturingVersionWriter())
                .editParagraphs(1L, 2L, 1, paragraphEditRequest(
                        paragraphOperation("INSERT_AFTER", "body/t0/r0/c0/p0", "第二段")));

        try (XWPFDocument edited = new XWPFDocument(new ByteArrayInputStream(storage.content))) {
            assertEquals(List.of("第一段", "第二段"), edited.getTables().getFirst().getRow(0).getCell(0)
                    .getParagraphs().stream().map(XWPFParagraph::getText).toList());
        }
    }

    @Test
    void editsCellInsertsRowAndDeletesOriginalRow() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A1");
            table.getRow(0).getCell(1).setText("A2");
            table.getRow(1).getCell(0).setText("B1");
            table.getRow(1).getCell(1).setText("B2");
            document.write(output);
            source = output.toByteArray();
        }
        MemoryStorage storage = new MemoryStorage();
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));
        EditDocxTableDTO request = tableEditRequest(
                tableOperation("SET_CELL_TEXT", "body/t0/r0/c1", "已修改", null),
                tableOperation("INSERT_ROW_AFTER", "body/t0/r0", null, List.of("新增1", "新增2")),
                tableOperation("DELETE_ROW", "body/t0/r1", null, null));

        new DocxDocumentServiceImpl(versionService, storage, new CapturingVersionWriter())
                .editTable(1L, 2L, 1, request);

        try (XWPFDocument edited = new XWPFDocument(new ByteArrayInputStream(storage.content))) {
            var rows = edited.getTables().getFirst().getRows();
            assertEquals(2, rows.size());
            assertEquals("A1", rows.get(0).getCell(0).getText());
            assertEquals("已修改", rows.get(0).getCell(1).getText());
            assertEquals("新增1", rows.get(1).getCell(0).getText());
            assertEquals("新增2", rows.get(1).getCell(1).getText());
        }
    }

    @Test
    void rejectsDeletingOnlyTableRow() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createTable(1, 1).getRow(0).getCell(0).setText("保留行");
            document.write(output);
            source = output.toByteArray();
        }
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DocxDocumentServiceImpl(versionService, new MemoryStorage(), unusedWriter())
                        .editTable(1L, 2L, 1, tableEditRequest(
                                tableOperation("DELETE_ROW", "body/t0/r0", null, null))));

        assertEquals(422, exception.getCode());
    }

    @Test
    void formatsHeadingsBulletsAndNumberedParagraphs() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("章节");
            document.createParagraph().createRun().setText("要点一");
            document.createParagraph().createRun().setText("要点二");
            document.createParagraph().createRun().setText("步骤一");
            XWPFParagraph clearList = document.createParagraph();
            clearList.createRun().setText("取消列表");
            clearList.setNumID(BigInteger.ONE);
            clearList.setNumILvl(BigInteger.ZERO);
            XWPFParagraph normal = document.createParagraph();
            normal.setStyle("Heading2");
            normal.createRun().setText("普通正文");
            document.write(output);
            source = output.toByteArray();
        }

        FormatDocxParagraphsDTO request = paragraphFormatRequest(
                paragraphFormatOperation("SET_HEADING", "body/p0", 3),
                paragraphFormatOperation("SET_BULLET", "body/p1", null),
                paragraphFormatOperation("SET_BULLET", "body/p2", null),
                paragraphFormatOperation("SET_NUMBERED", "body/p3", null),
                paragraphFormatOperation("CLEAR_LIST", "body/p4", null),
                paragraphFormatOperation("SET_NORMAL", "body/p5", null));
        MemoryStorage storage = new MemoryStorage();
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        new DocxDocumentServiceImpl(versionService, storage, new CapturingVersionWriter())
                .formatParagraphs(1L, 2L, 1, request);

        try (XWPFDocument edited = new XWPFDocument(new ByteArrayInputStream(storage.content))) {
            List<XWPFParagraph> paragraphs = edited.getParagraphs();
            assertEquals("Heading3", paragraphs.get(0).getStyle());
            assertEquals("bullet", paragraphs.get(1).getNumFmt());
            assertEquals(paragraphs.get(1).getNumID(), paragraphs.get(2).getNumID());
            assertEquals("decimal", paragraphs.get(3).getNumFmt());
            assertNotEquals(paragraphs.get(1).getNumID(), paragraphs.get(3).getNumID());
            assertNull(paragraphs.get(4).getNumID());
            assertEquals("取消列表", paragraphs.get(4).getText());
            assertEquals("Normal", paragraphs.get(5).getStyle());
            assertEquals("普通正文", paragraphs.get(5).getText());
        }
    }

    @Test
    void rejectsHeadingFormatWithoutLevel() throws Exception {
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("章节");
            document.write(output);
            source = output.toByteArray();
        }
        DocumentVersionService versionService = downloadService(
                1L, 2L, 1, download(source, DOCX_CONTENT_TYPE, "docx"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DocxDocumentServiceImpl(versionService, new MemoryStorage(), unusedWriter())
                        .formatParagraphs(1L, 2L, 1, paragraphFormatRequest(
                                paragraphFormatOperation("SET_HEADING", "body/p0", null))));

        assertEquals(400, exception.getCode());
    }

    private static FileDownload download(byte[] content, String contentType, String extension) {
        FileAsset asset = new FileAsset();
        asset.setContentType(contentType);
        asset.setExtension(extension);
        asset.setDisplayName("sample." + extension);
        return new FileDownload(asset, new ByteArrayResource(content), content.length,
                contentType, asset.getDisplayName());
    }

    private static ReplaceDocxTextDTO request(String blockId, String text) {
        ReplaceDocxTextDTO.Operation operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId(blockId);
        operation.setText(text);
        ReplaceDocxTextDTO request = new ReplaceDocxTextDTO();
        request.setInstruction("测试替换");
        request.setOperations(List.of(operation));
        return request;
    }

    private static EditDocxParagraphsDTO paragraphEditRequest(EditDocxParagraphsDTO.Operation... operations) {
        EditDocxParagraphsDTO request = new EditDocxParagraphsDTO();
        request.setInstruction("测试段落结构编辑");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditDocxParagraphsDTO.Operation paragraphOperation(String type, String blockId, String text) {
        EditDocxParagraphsDTO.Operation operation = new EditDocxParagraphsDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setText(text);
        return operation;
    }

    private static EditDocxTableDTO tableEditRequest(EditDocxTableDTO.Operation... operations) {
        EditDocxTableDTO request = new EditDocxTableDTO();
        request.setInstruction("测试表格编辑");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditDocxTableDTO.Operation tableOperation(String type, String blockId,
                                                             String text, List<String> values) {
        EditDocxTableDTO.Operation operation = new EditDocxTableDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setText(text);
        operation.setValues(values);
        return operation;
    }

    private static FormatDocxParagraphsDTO paragraphFormatRequest(
            FormatDocxParagraphsDTO.Operation... operations) {
        FormatDocxParagraphsDTO request = new FormatDocxParagraphsDTO();
        request.setInstruction("测试段落格式");
        request.setOperations(List.of(operations));
        return request;
    }

    private static FormatDocxParagraphsDTO.Operation paragraphFormatOperation(
            String type, String blockId, Integer level) {
        FormatDocxParagraphsDTO.Operation operation = new FormatDocxParagraphsDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setLevel(level);
        return operation;
    }

    private static FileStorage unusedStorage() {
        return new MemoryStorage();
    }

    private static VersionWriter unusedWriter() {
        return (fileId, userId, content, changeType, instruction, summary, expectVersion) -> {
            throw new UnsupportedOperationException();
        };
    }

    private static DocumentVersionService downloadService(Long expectedUserId, Long expectedFileId,
                                                          Integer expectedVersion, FileDownload download) {
        return new DocumentVersionService() {
            @Override
            public List<VersionVO> listVersions(Long userId, Long fileId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public VersionVO getVersion(Long userId, Long fileId, Integer versionNo) {
                throw new UnsupportedOperationException();
            }

            @Override
            public VersionVO restore(Long userId, Long fileId, Integer versionNo, Integer expectVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileDownload downloadVersion(Long userId, Long fileId, Integer versionNo) {
                assertEquals(expectedUserId, userId);
                assertEquals(expectedFileId, fileId);
                assertEquals(expectedVersion, versionNo);
                return download;
            }
        };
    }

    private static final class MemoryStorage implements FileStorage {
        private byte[] content;

        @Override
        public String store(byte[] content, String extension) {
            this.content = content;
            return "memory/result." + extension;
        }

        @Override
        public String store(InputStream inputStream, String extension) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream read(String storageKey) {
            return new ByteArrayInputStream(content);
        }

        @Override
        public boolean exists(String storageKey) {
            return content != null;
        }

        @Override
        public boolean delete(String storageKey) {
            boolean existed = content != null;
            content = null;
            return existed;
        }

        @Override
        public Path resolvePath(String storageKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingVersionWriter implements VersionWriter {
        private VersionContent content;
        private ChangeType changeType;
        private Integer expectVersion;

        @Override
        public DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                             ChangeType changeType, String instruction, String summary,
                                             Integer expectVersion) {
            this.content = content;
            this.changeType = changeType;
            this.expectVersion = expectVersion;
            DocumentVersion version = new DocumentVersion();
            version.setId(22L);
            version.setFileId(fileId);
            version.setUserId(userId);
            version.setVersionNo(expectVersion + 1);
            version.setParentVersion(expectVersion);
            version.setStorageKey(content.storageKey());
            version.setSizeBytes(content.sizeBytes());
            version.setSha256(content.sha256());
            version.setInstruction(instruction);
            version.setResultSummary(summary);
            version.setChangeType(changeType.name());
            return version;
        }
    }
}
