package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        DocxStructureVO structure = new DocxDocumentServiceImpl(versionService)
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
                () -> new DocxDocumentServiceImpl(versionService).readStructure(1L, 2L, 1));

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
}
