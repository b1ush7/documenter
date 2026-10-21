package com.documenter.service.impl;

import com.documenter.component.EditPlanValidator;
import com.documenter.component.tool.NormalizeDocxFormatTool;
import com.documenter.component.tool.ResizeDocxImagesTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.*;
import com.documenter.util.DocxNormalizationSpec;
import com.documenter.util.DocxNormalizer;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileDownload;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.springframework.core.io.ByteArrayResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocxNormalizationTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(versions, storage, writer,
            mock(FileService.class), mock(DocxTemplateGenerationService.class));

    @Test
    void twoToolsPlanLargeDocumentAndOneConfirmationWritesExactlyOneVersion() throws Exception {
        byte[] png = png();
        byte[] source;
        try (var doc = new XWPFDocument()) {
            for (int i = 0; i < 80; i++) doc.createParagraph().createRun().setText("paragraph " + i);
            var picture = doc.createParagraph();
            picture.createRun().addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG,
                    "test.png", Units.pixelToEMU(100), Units.pixelToEMU(100));
            source = bytes(doc);
        }
        prepare(source);
        var context = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX", service.readStructure(7L, 10L, 1), null);
        var formatting = new NormalizeDocxFormatTool().execute(Map.of("formatJson",
                "{\"scope\":\"ALL\",\"fontFamily\":\"宋体\",\"fontSizePoints\":12,\"lineSpacing\":1.5}"), context).plan();
        var images = new ResizeDocxImagesTool().execute(Map.of("layoutJson", "{\"widthCm\":6}"), context).plan();
        assertTrue(formatting.summary().contains("81"));
        assertTrue(images.summary().contains("1 张"));
        var plan = new DocumentEditPlan("统一文档", List.of(formatting.operations().getFirst(), images.operations().getFirst()), null);
        new EditPlanValidator(new AiModelProperties()).validateForContext(plan, context);
        verifyNoInteractions(storage, writer); // Tool calls/validation do not write.
        var result = service.editPlan(7L, 10L, 1, plan, "统一格式与图片");
        assertEquals(2, result.getVersionNo());
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), eq("统一格式与图片"), any(), eq(1));
        try (var edited = new XWPFDocument(new ByteArrayInputStream(saved()))) {
            assertEquals(81, edited.getParagraphs().size());
            for (var paragraph : edited.getParagraphs()) {
                assertEquals(1.5, paragraph.getSpacingBetween(), .001);
                for (var run : paragraph.getRuns()) {
                    assertEquals("宋体", run.getFontFamily());
                    assertEquals(12.0, run.getFontSizeAsDouble());
                }
            }
            assertArrayEquals(png, edited.getAllPictures().getFirst().getData());
        }
    }

    @Test
    void scopedFormattingPreservesTextFieldsLinksPicturesAndHeaderFooter() throws Exception {
        try (var doc = new XWPFDocument()) {
            var heading = doc.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("Heading");
            var paragraph = doc.createParagraph(); var text = paragraph.createRun(); text.setText("正文"); text.setBold(true);
            text.getCTR().getRPr().addNewRFonts().setAsciiTheme(
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.STTheme.MINOR_H_ANSI);
            var link = paragraph.createHyperlinkRun("https://example.test"); link.setText("link");
            var field = paragraph.createRun(); field.getCTR().addNewFldChar().setFldCharType(
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.BEGIN);
            var table = doc.createTable(1, 1); table.getRow(0).getCell(0).setText("table text");
            var header = doc.createHeader(HeaderFooterType.DEFAULT); header.createParagraph().createRun().setText("header");
            String headerXml = header._getHdrFtr().xmlText();
            String relation = link.getHyperlinkId();
            DocxNormalizer.prepareFormat(doc, DocxNormalizationSpec.format("""
                    {"scope":"BODY","fontFamily":"宋体","fontSizePoints":12,"lineSpacing":1.5,
                    "spaceBeforePoints":0,"spaceAfterPoints":6,"alignment":"JUSTIFY"}
                    """)).run();
            DocxNormalizer.prepareFormat(doc, DocxNormalizationSpec.format("""
                    {"scope":"HEADINGS","headingLevel":1,"fontFamily":"黑体","fontSizePoints":16,"bold":true}
                    """)).run();
            assertEquals("Heading", heading.getText()); assertEquals("黑体", heading.getRuns().getFirst().getFontFamily());
            assertEquals("正文link", paragraph.getText()); assertTrue(text.isBold());
            assertEquals("宋体", text.getFontFamily(XWPFRun.FontCharRange.eastAsia));
            assertFalse(text.getCTR().xmlText().contains("asciiTheme"));
            assertTrue(text.getCTR().xmlText().contains("szCs"));
            assertEquals("宋体", link.getFontFamily()); assertEquals(relation, link.getHyperlinkId());
            assertEquals(ParagraphAlignment.BOTH, paragraph.getAlignment()); assertEquals(120, paragraph.getSpacingAfter());
            assertEquals(1.5, paragraph.getSpacingBetween(), .001);
            assertEquals("宋体", table.getRow(0).getCell(0).getParagraphs().getFirst().getRuns().getFirst().getFontFamily());
            assertEquals(headerXml, header._getHdrFtr().xmlText());
            assertTrue(field.getCTR().sizeOfFldCharArray() > 0);
            try (var reread = new XWPFDocument(new ByteArrayInputStream(bytes(doc)))) {
                assertEquals("正文link", reread.getParagraphs().get(1).getText());
                assertTrue(reread.getParagraphs().get(1).getRuns().stream().anyMatch(r -> r.getCTR().sizeOfFldCharArray() > 0));
            }
        }
    }

    @Test
    void resizingUsesNaturalRatioUpdatesBothExtentsAndPreservesImageBytes() throws Exception {
        byte[] png = png();
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();
            p.createRun().addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG, "test.png",
                    Units.pixelToEMU(100), Units.pixelToEMU(100));
            DocxNormalizer.prepareImages(doc, DocxNormalizationSpec.images("{\"widthCm\":6,\"alignment\":\"CENTER\"}")).run();
            try (var result = new XWPFDocument(new ByteArrayInputStream(bytes(doc)))) {
                var run = result.getParagraphs().getFirst().getRuns().getFirst();
                var outer = run.getCTR().getDrawingArray(0).getInlineArray(0).getExtent();
                var inner = run.getEmbeddedPictures().getFirst().getCTPicture().getSpPr().getXfrm().getExt();
                assertEquals(2160000, outer.getCx()); assertEquals(1080000, outer.getCy());
                assertEquals(outer.getCx(), inner.getCx()); assertEquals(outer.getCy(), inner.getCy());
                assertArrayEquals(png, result.getAllPictures().getFirst().getData());
                assertEquals(ParagraphAlignment.CENTER, result.getParagraphs().getFirst().getAlignment());
            }
        }
    }

    @Test
    void dimensionsAreClampedToSectionAndCellBoundsAndHeadersAreUntouched() throws Exception {
        try (var doc = new XWPFDocument()) {
            var section = doc.getDocument().getBody().addNewSectPr();
            var page = section.addNewPgSz();page.setW(BigInteger.valueOf(9000));page.setH(BigInteger.valueOf(14000));
            var margins = section.addNewPgMar();margins.setLeft(BigInteger.valueOf(1000));margins.setRight(BigInteger.valueOf(1000));
            margins.setTop(BigInteger.valueOf(1000));margins.setBottom(BigInteger.valueOf(1000));
            addPicture(doc.createParagraph());
            var cell = doc.createTable(1, 1).getRow(0).getCell(0);cell.setWidth("1440");addPicture(cell.getParagraphs().getFirst());
            var header = doc.createHeader(HeaderFooterType.DEFAULT);addPicture(header.createParagraph());
            String headerXml = header._getHdrFtr().xmlText();
            DocxNormalizer.prepareImages(doc, DocxNormalizationSpec.images("{\"widthCm\":50}")).run();
            assertEquals(7000L * 635, doc.getParagraphs().getFirst().getRuns().getFirst().getCTR().getDrawingArray(0).getInlineArray(0).getExtent().getCx());
            assertTrue(cell.getParagraphs().getFirst().getRuns().getFirst().getCTR().getDrawingArray(0).getInlineArray(0).getExtent().getCx() <= 1440L * 635);
            assertEquals(headerXml, header._getHdrFtr().xmlText());
        }
    }

    @Test
    void floatingDrawingExtentsChangeWithoutConvertingItToInline() throws Exception {
        try (var doc = new XWPFDocument()) {
            var p = doc.createParagraph();addPicture(p);
            var drawing = p.getRuns().getFirst().getCTR().getDrawingArray(0);
            var anchor = CTAnchor.Factory.parse(drawing.getInlineArray(0).xmlText());
            drawing.setAnchorArray(new CTAnchor[]{anchor});drawing.removeInline(0);
            DocxNormalizer.prepareImages(doc, DocxNormalizationSpec.images("{\"widthCm\":5}")).run();
            assertEquals(0, drawing.sizeOfInlineArray());assertEquals(1, drawing.sizeOfAnchorArray());
            assertEquals(1800000, drawing.getAnchorArray(0).getExtent().getCx());
        }
    }

    @Test
    void unsupportedMixedParagraphAlignmentFailsBeforeWritingAnyVersion() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument()) {var p = doc.createParagraph();p.createRun().setText("keep text");addPicture(p);source = bytes(doc);}
        prepare(source);
        var plan = new DocumentEditPlan("atomic", List.of(
                new DocumentEditPlan.Operation("NORMALIZE_DOCX_FORMAT", "body", "{\"fontFamily\":\"宋体\"}", null),
                new DocumentEditPlan.Operation("RESIZE_DOCX_IMAGES", "body", "{\"widthCm\":6,\"alignment\":\"CENTER\"}", null)), null);
        assertThrows(BusinessException.class, () -> service.editPlan(7L, 10L, 1, plan, "atomic"));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void inheritedAndOutlineHeadingLevelsMatchStructureAndExecution() throws Exception {
        byte[] source;
        try (var doc = new XWPFDocument()) {
            var styles = doc.createStyles();var base = CTStyle.Factory.newInstance();base.setStyleId("base");
            base.addNewPPr().addNewOutlineLvl().setVal(BigInteger.ONE);styles.addStyle(new XWPFStyle(base));
            var derived = CTStyle.Factory.newInstance();derived.setStyleId("derived");derived.addNewBasedOn().setVal("base");styles.addStyle(new XWPFStyle(derived));
            var p = doc.createParagraph();p.setStyle("derived");p.createRun().setText("second level");source=bytes(doc);
        }
        prepare(source);
        var structure=service.readStructure(7L,10L,1);assertEquals(2,structure.blocks().getFirst().headingLevel());
        var context=ToolExecutionContext.forVersion(7L,10L,1,"DOCX",structure,null);
        assertNotNull(new NormalizeDocxFormatTool().execute(Map.of("formatJson","{\"scope\":\"HEADINGS\",\"headingLevel\":2,\"fontSizePoints\":16}"),context).plan());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {2, 0, 1.5})
    void firstLineIndentUsesCharacterUnitsAndPreservesUnselectedContent(double characters) throws Exception {
        byte[] source;
        try (var document = new XWPFDocument()) {
            var body = document.createParagraph();
            body.createRun().setText("正文 text");
            body.getRuns().getFirst().setFontSize(11);
            body.setIndentationLeft(720);
            body.setIndentationRight(360);
            var indent = body.getCTP().getPPr().getInd();
            indent.setFirstLine(BigInteger.valueOf(480));
            indent.setHanging(BigInteger.valueOf(240));
            indent.setHangingChars(BigInteger.valueOf(100));
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("标题");
            heading.setIndentationFirstLine(120);
            var cell = document.createTable(1, 1).getRow(0).getCell(0).getParagraphs().getFirst();
            cell.createRun().setText("表格文字");
            cell.setIndentationFirstLine(240);
            var header = document.createHeader(HeaderFooterType.DEFAULT).createParagraph();
            header.createRun().setText("页眉");
            header.setIndentationFirstLine(360);
            source = bytes(document);
        }
        prepare(source);
        var context = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX", service.readStructure(7L, 10L, 1), null);
        var plan = new NormalizeDocxFormatTool().execute(Map.of("formatJson",
                "{\"scope\":\"BODY\",\"includeTables\":false,\"firstLineIndentChars\":" + characters + "}"), context).plan();
        new EditPlanValidator(new AiModelProperties()).validateForContext(plan, context);
        verifyNoInteractions(storage, writer);
        service.editPlan(7L, 10L, 1, plan, "修改首行缩进");
        try (var document = new XWPFDocument(new ByteArrayInputStream(saved()))) {
            var body = document.getParagraphs().getFirst();
            var indent = body.getCTP().getPPr().getInd();
            assertEquals(BigInteger.valueOf(Math.round(characters * 100)), indent.getFirstLineChars());
            assertFalse(indent.isSetFirstLine());
            assertFalse(indent.isSetHanging());
            assertFalse(indent.isSetHangingChars());
            assertEquals(720, body.getIndentationLeft());
            assertEquals(360, body.getIndentationRight());
            assertEquals("正文 text", body.getText());
            assertEquals(11.0, body.getRuns().getFirst().getFontSizeAsDouble());
            assertEquals(120, document.getParagraphs().get(1).getIndentationFirstLine());
            assertEquals(240, document.getTables().getFirst().getRow(0).getCell(0).getParagraphs().getFirst().getIndentationFirstLine());
            assertEquals(360, document.getHeaderList().getFirst().getParagraphs().getLast().getIndentationFirstLine());
        }
    }

    @Test
    void omittedFirstLineIndentPreservesExistingIndentSettings() throws Exception {
        byte[] source;
        try (var document = new XWPFDocument()) {
            var paragraph = document.createParagraph();
            paragraph.createRun().setText("保留缩进");
            paragraph.setIndentationHanging(480);
            paragraph.getCTP().getPPr().getInd().setHangingChars(BigInteger.valueOf(200));
            source = bytes(document);
        }
        prepare(source);
        var plan = new DocumentEditPlan("只改字号", List.of(new DocumentEditPlan.Operation(
                "NORMALIZE_DOCX_FORMAT", "body", "{\"fontSizePoints\":12}", null)), null);
        service.editPlan(7L, 10L, 1, plan, "只改字号");
        try (var document = new XWPFDocument(new ByteArrayInputStream(saved()))) {
            var indent = document.getParagraphs().getFirst().getCTP().getPPr().getInd();
            assertEquals(BigInteger.valueOf(200), indent.getHangingChars());
            assertEquals(480, document.getParagraphs().getFirst().getIndentationHanging());
            assertFalse(indent.isSetFirstLineChars());
        }
    }

    private void prepare(byte[] source) {
        var asset=new FileAsset();asset.setId(10L);asset.setUserId(7L);asset.setExtension("docx");
        when(versions.downloadVersion(7L,10L,1)).thenReturn(new FileDownload(asset,new ByteArrayResource(source),source.length,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document","source.docx"));
        when(storage.store(any(byte[].class),eq("docx"))).thenReturn("normalized.docx");
        var version=new DocumentVersion();version.setId(20L);version.setFileId(10L);version.setVersionNo(2);
        when(writer.appendVersion(eq(10L),eq(7L),any(),any(),any(),any(),eq(1))).thenReturn(version);
    }
    private byte[] saved() {var capture=ArgumentCaptor.forClass(byte[].class);verify(storage).store(capture.capture(),eq("docx"));return capture.getValue();}
    private static byte[] bytes(XWPFDocument doc) throws Exception {var out=new ByteArrayOutputStream();doc.write(out);return out.toByteArray();}
    private static byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,10,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    private static void addPicture(XWPFParagraph paragraph) throws Exception {paragraph.createRun().addPicture(new ByteArrayInputStream(png()),Document.PICTURE_TYPE_PNG,"test.png",Units.pixelToEMU(100),Units.pixelToEMU(100));}
}
