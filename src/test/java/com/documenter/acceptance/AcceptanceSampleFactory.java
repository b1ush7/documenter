package com.documenter.acceptance;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDate;

/** Fixed semantic inputs for reproducible acceptance scenarios; not production document generation. */
final class AcceptanceSampleFactory {
    static final String ORIGINAL_PARAGRAPH = "正文：保留表格和图片。";
    static final String EDITED_PARAGRAPH = "正文：已修改，表格和图片保持不变。";
    static final String OCR_TEXT = "OCR SAMPLE\nInvoice 2026-001\nTotal: 123.45";

    private AcceptanceSampleFactory() {}

    static BufferedImage colorImage() {
        var image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 320, 200);
            graphics.setColor(Color.RED);
            graphics.fillRect(20, 20, 80, 60);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(20, 110, 80, 60);
        } finally { graphics.dispose(); }
        image.setRGB(0, 0, 0);
        image.setRGB(319, 199, 0x8044aa66);
        return image;
    }

    static BufferedImage ocrImage() {
        var image = new BufferedImage(640, 240, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 640, 240);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
            int baseline = 55;
            for (String line : OCR_TEXT.split("\n")) {
                graphics.drawString(line, 30, baseline);
                baseline += 65;
            }
        } finally { graphics.dispose(); }
        return image;
    }

    static byte[] png(BufferedImage image) throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable");
            return output.toByteArray();
        }
    }

    static byte[] docx(byte[] picture) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var heading = CTStyle.Factory.newInstance();
            heading.setStyleId("Heading1");
            heading.addNewName().setVal("heading 1");
            heading.addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            document.createStyles().addStyle(new XWPFStyle(heading));
            var title = document.createParagraph();
            title.setStyle("Heading1");
            var titleRun = title.createRun();
            titleRun.setText("固定验收报告 / Acceptance Report");
            titleRun.setBold(true);
            titleRun.setFontSize(20);
            document.createParagraph().createRun().setText(ORIGINAL_PARAGRAPH);
            var styled = document.createParagraph();
            var emphasis = styled.createRun();
            emphasis.setText("Keep ");
            emphasis.setBold(true);
            emphasis.setColor("0055AA");
            styled.createRun().setText("format");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("事项");
            table.getRow(0).getCell(1).setText("状态");
            table.getRow(1).getCell(0).setText("固定样本");
            table.getRow(1).getCell(1).setText("待验收");
            try (var image = new ByteArrayInputStream(picture)) {
                document.createParagraph().createRun().addPicture(image, Document.PICTURE_TYPE_PNG,
                        "colors.png", Units.pixelToEMU(160), Units.pixelToEMU(100));
            }
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Acceptance header");
            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Acceptance footer");
            document.write(output);
            return output.toByteArray();
        }
    }

    static byte[] pdf(boolean textPage, boolean scanPage, BufferedImage scan) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            if (textPage) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
                    content.newLineAtOffset(72, 720);
                    content.showText("Native PDF sample");
                    content.newLineAtOffset(0, -30);
                    content.showText("Amount: 123.45");
                    content.endText();
                }
            }
            if (scanPage) {
                var page = new PDPage();
                document.addPage(page);
                var image = LosslessFactory.createFromImage(document, scan);
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, 50, 450, 512, 192);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] xlsx() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var data = workbook.createSheet("数据");
            var reference = workbook.createSheet("参照 表");
            reference.createRow(0).createCell(0).setCellValue(7);
            var header = data.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("金额");
            for (int index = 1; index <= 3; index++) {
                var row = data.createRow(index);
                row.createCell(0).setCellValue(String.valueOf((char) ('A' + index - 1)));
                row.createCell(1).setCellValue(index * 10);
            }
            String[] formulas = {"SUM(B2:B4)", "COUNTIF(B2:B4,\">15\")", "IF(B2>5,\"yes\",\"no\")",
                    "VLOOKUP(\"B\",A2:B4,2,FALSE)", "'参照 表'!A1*2", "1/0"};
            for (int index = 0; index < formulas.length; index++) {
                var row = data.getRow(index + 1);
                if (row == null) row = data.createRow(index + 1);
                row.createCell(3).setCellFormula(formulas[index]);
            }
            var money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            money.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            money.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            data.getRow(1).getCell(1).setCellStyle(money);
            var date = data.getRow(1).createCell(5);
            date.setCellValue(LocalDate.of(2026, 10, 17));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);
            data.setColumnWidth(0, 18 * 256);
            data.setColumnWidth(1, 16 * 256);
            data.setColumnWidth(3, 30 * 256);
            data.setColumnWidth(5, 16 * 256);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
