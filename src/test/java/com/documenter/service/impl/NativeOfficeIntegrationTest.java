package com.documenter.service.impl;

import com.documenter.component.LibreOfficeRenderService;
import com.documenter.configuration.*;
import com.documenter.service.*;
import com.documenter.support.OfficeAcceptanceSamples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="DOCUMENTER_OFFICE_TEST_ENABLED", matches="true")
class NativeOfficeIntegrationTest {
    private final Path root = Path.of("target/office-acceptance").toAbsolutePath();
    private LibreOfficeRenderService office;
    @BeforeEach void prepare() throws Exception {
        Files.createDirectories(root);
        var properties = new PreviewProperties(); properties.setOfficeBackend("libreoffice");
        properties.setOfficeExecutable(System.getenv("DOCUMENTER_OFFICE_EXECUTABLE"));
        properties.setOfficePython(System.getenv("DOCUMENTER_OFFICE_PYTHON"));
        office = new LibreOfficeRenderService(properties); assertTrue(office.available());
    }

    @Test void docxHasUpdatedTocChineseTablesImagesFootnotesAndFinalPageNumbers() throws Exception {
        Path source = root.resolve("中文排版样本.docx"); OfficeAcceptanceSamples.docx(source);
        byte[] original = Files.readAllBytes(source);
        Path pdf = office.toPdf(source, "docx", root.resolve("docx-conversion"));
        Files.copy(pdf, root.resolve("docx-layout.pdf"), StandardCopyOption.REPLACE_EXISTING);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertEquals(3, document.getNumberOfPages());
            var text = new PDFTextStripper(); text.setStartPage(1); text.setEndPage(1);
            String contents = text.getText(document);
            assertTrue(contents.contains("一、基本信息"), contents); assertTrue(contents.contains("二、补充说明"), contents);
            assertFalse(contents.contains("目录尚未更新"));
            text.setStartPage(2); text.setEndPage(2); String second = text.getText(document);
            assertTrue(second.contains("脚注说明"), second); assertTrue(second.contains("第二段脚注"), second);
            assertTrue(second.contains("水平合并"), second); assertTrue(second.contains("纵向合并"), second);
            assertTrue(second.contains("拆分后 B2"), second);
            for (int page=1; page<=3; page++) {
                text.setStartPage(page); text.setEndPage(page);
                String actual = text.getText(document).replaceAll("\\s+", "");
                assertTrue(actual.contains("Documenter后端验收"), actual); assertTrue(actual.contains(page+"/3"), actual);
            }
        }
        var manifest = office.render(source,"docx",root.resolve("docx-pages"),List.of(1,2,3),110);
        assertEquals(3,manifest.pages().size()); assertEquals(3,manifest.pageCount());
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void xlsxBasicFormulasChartAndPrintLayoutAreRenderedByActualCalc() throws Exception {
        Path source = root.resolve("基础统计样本.xlsx"); OfficeAcceptanceSamples.xlsx(source);
        byte[] original = Files.readAllBytes(source);
        Path pdf = office.toPdf(source,"xlsx",root.resolve("xlsx-conversion"));
        Files.copy(pdf,root.resolve("xlsx-layout.pdf"),StandardCopyOption.REPLACE_EXISTING);
        try(var document=Loader.loadPDF(pdf.toFile())) {
            assertEquals(1,document.getNumberOfPages()); String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("项目甲"),text); assertTrue(text.contains("项目乙"),text);
            assertTrue(text.contains("75.00"),text); assertTrue(text.contains("46.50"),text); assertTrue(text.contains("121.50"),text);
            assertTrue(text.contains("项目金额"),text);
        }
        var pages = office.render(source,"xlsx",root.resolve("xlsx-pages"),List.of(1),110).pages();
        assertEquals(1,pages.size());
        var image = javax.imageio.ImageIO.read(Path.of(pages.get(0).path()).toFile());
        int colored = 0;
        try {
            for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                int pixel=image.getRGB(x,y); int red=(pixel>>16)&255, green=(pixel>>8)&255, blue=pixel&255;
                if(blue-red>40 && green-red>20) colored++;
            }
        } finally { image.flush(); }
        assertTrue(colored>1000,"图表必须实际显示系列，不能只有坐标轴和标题");
        assertArrayEquals(original,Files.readAllBytes(source));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"LINE", "PIE"})
    void additionalBasicChartsHaveVisibleSeriesInActualCalc(String type) throws Exception {
        Path source=root.resolve(type+"-chart.xlsx"); OfficeAcceptanceSamples.xlsx(source,type);
        Path pdf=office.toPdf(source,"xlsx",root.resolve(type+"-conversion"));
        try(var document=Loader.loadPDF(pdf.toFile())) {
            assertEquals(1,document.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(document).contains("121.50"));
            var image=new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0,110);
            int colored=0;
            try {
                // 图表锚点位于表格下方；排除标题和单元格，只计系列的蓝色像素。
                for(int y=image.getHeight()/4;y<image.getHeight()-50;y++) for(int x=0;x<image.getWidth();x++) {
                    int pixel=image.getRGB(x,y); int red=(pixel>>16)&255,green=(pixel>>8)&255,blue=pixel&255;
                    if(blue-red>40 && green-red>20) colored++;
                }
                javax.imageio.ImageIO.write(image,"PNG",root.resolve(type+"-chart.png").toFile());
            } finally { image.flush(); }
            assertTrue(colored>100,"实际 Office 图表必须显示系列: "+type);
        }
    }

    @Test void externalCalculationIsRejectedWithoutMakingHttpRequest() throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(200,1);
            try(var output=exchange.getResponseBody()) { output.write('1'); }
        });
        server.start();
        try {
            Path source=root.resolve("external-formula.xlsx");
            try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
                workbook.createSheet("data").createRow(0).createCell(0).getCTCell().addNewF()
                        .setStringValue("WEBSERVICE(\"http://127.0.0.1:"+server.getAddress().getPort()+"/\")");
                try(var output=Files.newOutputStream(source)) { workbook.write(output); }
            }
            var failure=assertThrows(com.documenter.exception.BusinessException.class,
                    () -> office.toPdf(source,"xlsx",root.resolve("external-conversion")));
            assertEquals(422,failure.getCode()); assertEquals(0,requests.get());
        } finally { server.stop(0); }
    }

    @Test void nativeChinesePdfCanBeRebuiltAsEditableDocxAndRenderedAgain() throws Exception {
        Path source=root.resolve("转换原样本.docx"); OfficeAcceptanceSamples.docx(source);
        Path pdf=office.toPdf(source,"docx",root.resolve("rebuild-source"));
        var converter=new PdfToDocxServiceImpl(mock(DocumentVersionService.class),mock(FileStorage.class),mock(VersionWriter.class),
                new StorageProperties(),mock(OcrEngine.class),new OcrProperties());
        byte[] rebuilt=converter.rebuild(Files.readAllBytes(pdf),com.documenter.enums.PdfConversionMode.EDITABLE);
        Path docx=root.resolve("pdf-rebuilt.docx"); Files.write(docx,rebuilt);
        Path result=office.toPdf(docx,"docx",root.resolve("rebuild-result"));
        Files.copy(result,root.resolve("pdf-rebuilt-layout.pdf"),StandardCopyOption.REPLACE_EXISTING);
        try(var document=Loader.loadPDF(result.toFile())) {
            String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("中文字体"),text); assertTrue(text.contains("基本信息"),text);
            assertTrue(document.getNumberOfPages()>=3 && document.getNumberOfPages()<=10);
        }
        var manifest=office.render(docx,"docx",root.resolve("rebuilt-pages"),null,110);
        assertFalse(manifest.pages().isEmpty());
    }
}
