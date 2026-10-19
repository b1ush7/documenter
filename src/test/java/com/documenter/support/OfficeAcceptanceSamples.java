package com.documenter.support;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.util.Units;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;

/** 固定的真实办公软件验收样本，特意包含无缓存目录和无缓存公式。 */
public final class OfficeAcceptanceSamples {
    private OfficeAcceptanceSamples() {}

    public static void docx(Path target) throws Exception {
        try (var document = new XWPFDocument()) {
            var styles = document.createStyles();
            CTFonts fonts = CTFonts.Factory.newInstance(); fonts.setAscii("SimSun"); fonts.setHAnsi("SimSun"); fonts.setEastAsia("SimSun");
            styles.setDefaultFonts(fonts);
            var heading = CTStyle.Factory.newInstance(); heading.setStyleId("Heading1"); heading.setType(STStyleType.PARAGRAPH);
            heading.addNewName().setVal("heading 1"); heading.addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            styles.addStyle(new XWPFStyle(heading));
            var section = document.getDocument().getBody().addNewSectPr();
            section.addNewPgSz().setW(BigInteger.valueOf(11906)); section.getPgSz().setH(BigInteger.valueOf(16838));
            var margins = section.addNewPgMar();
            margins.setTop(BigInteger.valueOf(1440)); margins.setBottom(BigInteger.valueOf(1440));
            margins.setLeft(BigInteger.valueOf(1440)); margins.setRight(BigInteger.valueOf(1440));
            margins.setHeader(BigInteger.valueOf(600)); margins.setFooter(BigInteger.valueOf(600));
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Documenter 后端验收");
            var footer = document.createFooter(HeaderFooterType.DEFAULT).createParagraph();
            footer.setAlignment(ParagraphAlignment.CENTER); footer.createRun().setText("页 ");
            footer.getCTP().addNewFldSimple().setInstr("PAGE"); footer.createRun().setText(" / ");
            footer.getCTP().addNewFldSimple().setInstr("NUMPAGES");
            var title = document.createParagraph(); title.createRun().setText("中文排版与目录验收"); title.getRuns().get(0).setBold(true);
            var toc = document.createParagraph().getCTP();
            toc.addNewR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            toc.addNewR().addNewInstrText().setStringValue(" TOC \\o \"1-2\" \\h \\z \\u ");
            toc.addNewR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            toc.addNewR().addNewT().setStringValue("目录尚未更新");
            toc.addNewR().addNewFldChar().setFldCharType(STFldCharType.END);
            var first = document.createParagraph(); first.setPageBreak(true); first.setStyle("Heading1"); first.createRun().setText("一、基本信息");
            var text = document.createParagraph();
            text.createRun().setText("中文字体、表格、图片和脚注都应清晰可读。");
            var note = document.createFootnote();
            note.createParagraph().createRun().setText("脚注说明：保留原始数据来源。");
            note.createParagraph().createRun().setText("第二段脚注用于验证复杂脚注的排版保留。");
            text.getCTP().addNewR().addNewFootnoteReference().setId(note.getId());
            var table = document.createTable(4, 3); table.setWidth("100%");
            var grid = table.getCTTbl().getTblGrid() != null ? table.getCTTbl().getTblGrid() : table.getCTTbl().addNewTblGrid();
            while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
            for (int column = 0; column < 3; column++) grid.addNewGridCol().setW(BigInteger.valueOf(3000));
            for (var row : table.getRows()) for (var cell : row.getTableCells()) cell.setWidth("3000");
            table.getRow(0).setRepeatHeader(true);
            for (int column = 0; column < 3; column++) table.getRow(0).getCell(column).setText("标题 " + (column + 1));
            table.getRow(1).getCell(0).setText("水平合并 A-B");
            table.getRow(1).getCell(0).getCTTc().getTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            table.getRow(1).getCell(0).setWidth("6000");
            table.getRow(1).removeCell(1); table.getRow(1).getCell(1).setText("独立 C");
            table.getRow(2).getCell(0).setText("纵向合并");
            table.getRow(2).getCell(0).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(3).getCell(0).getCTTc().getTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            table.getRow(2).getCell(1).setText("拆分后 B1"); table.getRow(2).getCell(2).setText("拆分后 C1");
            table.getRow(3).getCell(1).setText("拆分后 B2"); table.getRow(3).getCell(2).setText("拆分后 C2");
            var image = new java.awt.image.BufferedImage(900, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.scale(3, 3);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 300, 100);
            graphics.setColor(new java.awt.Color(25, 95, 145)); graphics.fillRoundRect(4, 4, 292, 92, 12, 12);
            graphics.setColor(java.awt.Color.WHITE); graphics.setFont(new java.awt.Font("SimSun", java.awt.Font.PLAIN, 26)); graphics.drawString("图片样本 ABC", 55, 60); graphics.dispose();
            try (var encoded = new ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(image, "png", encoded);
                document.createParagraph().createRun().addPicture(new ByteArrayInputStream(encoded.toByteArray()), Document.PICTURE_TYPE_PNG,
                        "image.png", Units.toEMU(225), Units.toEMU(75));
            } finally { image.flush(); }
            var second = document.createParagraph(); second.setPageBreak(true); second.setStyle("Heading1"); second.createRun().setText("二、补充说明");
            document.createParagraph().createRun().setText("最终页码应与目录一致，源文件保持不变。");
            document.enforceUpdateFields();
            try (var output = Files.newOutputStream(target)) { document.write(output); }
        }
    }

    public static void xlsx(Path target) throws Exception { xlsx(target, "BAR"); }

    public static void xlsx(Path target, String type) throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("基础统计");
            var font = workbook.createFont(); font.setFontName("SimSun"); font.setFontHeightInPoints((short) 11);
            var normal = workbook.createCellStyle(); normal.setFont(font); normal.setBorderBottom(BorderStyle.THIN);
            var amount = workbook.createCellStyle(); amount.cloneStyleFrom(normal); amount.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            sheet.createRow(0).createCell(0).setCellValue("中文基础统计与图表验收"); sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
            String[] labels = {"项目", "数量", "单价", "金额"};
            var header = sheet.createRow(1);
            for (int column = 0; column < 4; column++) { sheet.setColumnWidth(column, 5000); header.createCell(column).setCellValue(labels[column]); }
            var row = sheet.createRow(2); row.createCell(0).setCellValue("项目甲"); row.createCell(1).setCellValue(12); row.createCell(2).setCellValue(6.25); row.createCell(3).setCellFormula("B3*C3");
            row = sheet.createRow(3); row.createCell(0).setCellValue("项目乙"); row.createCell(1).setCellValue(3); row.createCell(2).setCellValue(15.5); row.createCell(3).setCellFormula("B4*C4");
            row = sheet.createRow(4); row.createCell(0).setCellValue("合计"); row.createCell(3).setCellFormula("SUM(D3:D4)");
            for (var cells : sheet) for (var cell : cells) cell.setCellStyle(cell.getColumnIndex() >= 2 ? amount : normal);
            var anchor = workbook.getCreationHelper().createClientAnchor(); anchor.setCol1(0); anchor.setRow1(7); anchor.setCol2(4); anchor.setRow2(22);
            var chart = sheet.createDrawingPatriarch().createChart(anchor); chart.setTitleText("项目金额");
            var categories = chart.createCategoryAxis(AxisPosition.BOTTOM); var values = chart.createValueAxis(AxisPosition.LEFT);
            values.setCrosses(AxisCrosses.AUTO_ZERO);
            values.setCrossBetween(AxisCrossBetween.BETWEEN);
            XDDFChartData data;
            if ("PIE".equals(type)) data = chart.createData(ChartTypes.PIE, null, null);
            else if ("LINE".equals(type)) data = chart.createData(ChartTypes.LINE, categories, values);
            else {
                var bar = (XDDFBarChartData) chart.createData(ChartTypes.BAR, categories, values);
                bar.setBarDirection(BarDirection.COL); bar.setBarGrouping(BarGrouping.CLUSTERED); data = bar;
            }
            var series = data.addSeries(XDDFDataSourcesFactory.fromStringCellRange(sheet, new CellRangeAddress(2, 3, 0, 0)),
                    XDDFDataSourcesFactory.fromNumericCellRange(sheet, new CellRangeAddress(2, 3, 3, 3)));
            series.setTitle("金额", null);
            com.documenter.util.BasicChartStyle.apply(data, series, type);
            chart.plot(data);
            sheet.getHeader().setCenter("Documenter 基础表格"); sheet.getFooter().setCenter("Page &P of &N");
            sheet.setFitToPage(true); sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE); sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 1); workbook.setPrintArea(0, 0, 3, 0, 23);
            workbook.setForceFormulaRecalculation(true);
            try (var output = Files.newOutputStream(target)) { workbook.write(output); }
        }
    }
}
