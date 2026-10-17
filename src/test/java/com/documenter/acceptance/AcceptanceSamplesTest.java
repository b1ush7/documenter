package com.documenter.acceptance;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditImageDTO;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.VersionWriter;
import com.documenter.service.impl.DocxDocumentServiceImpl;
import com.documenter.service.impl.ImageDocumentServiceImpl;
import com.documenter.service.impl.LocalFileStorage;
import com.documenter.service.impl.PdfDocumentServiceImpl;
import com.documenter.service.impl.XlsxDocumentServiceImpl;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local service acceptance bundle. Database, authorization, real OCR and Office layout are not exercised. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AcceptanceSamplesTest {
    private static final long USER = 7L;
    private static final Path OUTPUT = Path.of("target", "acceptance-samples").toAbsolutePath().normalize();
    private static final Map<Long, String> SOURCES = Map.of(101L, "docx-input.docx", 102L, "native.pdf",
            103L, "scanned.pdf", 104L, "mixed.pdf", 105L, "formulas-input.xlsx", 106L, "image-input.png");
    private static final Map<String, String> TYPES = Map.of("pdf", "application/pdf", "png", "image/png",
            "jpg", "image/jpeg", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final Map<String, Map<String, Object>> artifacts = new LinkedHashMap<>();
    private final Map<String, String> checks = new LinkedHashMap<>();
    @TempDir Path directory;
    private DocumentVersionService versions;
    private VersionWriter writer;
    private LocalFileStorage storage;
    private StorageProperties properties;
    private byte[] savedBytes;
    private VersionWriter.VersionContent savedContent;

    @BeforeAll
    void generateInputs() throws Exception {
        Files.createDirectories(OUTPUT);
        // A terminated/failed run must not leave a previous manifest appearing to certify new outputs.
        Files.deleteIfExists(OUTPUT.resolve("manifest.json"));
        for (String scenario : List.of("DOCX", "NATIVE_PDF", "SCANNED_MIXED_PDF", "XLSX", "LOCAL_IMAGE", "JPEG")) {
            checks.put(scenario, "NOT_COMPLETED");
        }
        var colors = AcceptanceSampleFactory.png(AcceptanceSampleFactory.colorImage());
        var ocr = AcceptanceSampleFactory.ocrImage();
        write("image-input.png", colors, "320x200; transparent (0,0); red x20..99/y20..79; blue x20..99/y110..169");
        write("ocr-input.png", AcceptanceSampleFactory.png(ocr), "640x240 English/digits OCR input; recognition NOT executed");
        write("ocr-expected.txt", AcceptanceSampleFactory.OCR_TEXT.getBytes(StandardCharsets.UTF_8), "Manual OCR reference; not an OCR result");
        write("docx-input.docx", AcceptanceSampleFactory.docx(colors), "Chinese/English, one table, one PNG, header/footer, styled runs");
        write("native.pdf", AcceptanceSampleFactory.pdf(true, false, ocr), "One TEXT page: Native PDF sample / Amount: 123.45");
        write("scanned.pdf", AcceptanceSampleFactory.pdf(false, true, ocr), "One SCANNED page, no native text, OCR required");
        write("mixed.pdf", AcceptanceSampleFactory.pdf(true, true, ocr), "MIXED document: page 1 TEXT, page 2 SCANNED");
        write("formulas-input.xlsx", AcceptanceSampleFactory.xlsx(), "数据!D2:D7 = 60,2,yes,20,14,#DIV/0!; 参照 表!A1=7; F2 date 2026-10-17");
    }

    @BeforeEach
    void setUpServiceBoundaries() {
        properties = new StorageProperties();
        properties.getLocal().setRoot(directory.resolve("storage").toString());
        properties.getLocal().setTemp(directory.resolve("temp").toString());
        storage = new LocalFileStorage(properties);
        versions = mock(DocumentVersionService.class);
        writer = mock(VersionWriter.class);
        savedBytes = null;
        savedContent = null;
        when(versions.downloadVersion(eq(USER), anyLong(), eq(1))).thenAnswer(call -> {
            long fileId = call.getArgument(1);
            String filename = SOURCES.get(fileId);
            if (filename == null) throw new IllegalArgumentException("unknown sample id");
            byte[] bytes = Files.readAllBytes(OUTPUT.resolve(filename));
            var asset = new FileAsset();
            asset.setId(fileId);
            asset.setUserId(USER);
            asset.setDisplayName(filename);
            asset.setExtension(filename.substring(filename.lastIndexOf('.') + 1));
            asset.setContentType(TYPES.get(asset.getExtension()));
            return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length, asset.getContentType(), filename);
        });
        when(writer.appendVersion(anyLong(), eq(USER), any(), any(), any(), any(), eq(1))).thenAnswer(call -> {
            savedContent = call.getArgument(2);
            savedBytes = Files.readAllBytes(storage.resolvePath(savedContent.storageKey()));
            assertEquals(savedBytes.length, savedContent.sizeBytes());
            assertEquals(Digests.sha256Hex(savedBytes), savedContent.sha256());
            var version = new DocumentVersion();
            version.setId(1000L);
            version.setFileId(call.getArgument(0));
            version.setVersionNo(2);
            version.setParentVersion(1);
            version.setStorageKey(savedContent.storageKey());
            version.setSizeBytes(savedContent.sizeBytes());
            version.setSha256(savedContent.sha256());
            version.setChangeType(call.getArgument(3).toString());
            version.setContentType(savedContent.contentType());
            version.setExtension(savedContent.extension());
            return version;
        });
    }

    @Test
    void docxTextEditRetainsTablePictureStylesAndHeaderFooter() throws Exception {
        var service = new DocxDocumentServiceImpl(versions, storage, writer, mock(FileService.class),
                mock(com.documenter.service.impl.DocxTemplateGenerationService.class));
        var structure = service.readStructure(USER, 101L, 1);
        var target = structure.blocks().stream().filter(block -> AcceptanceSampleFactory.ORIGINAL_PARAGRAPH.equals(block.text()))
                .findFirst().orElseThrow();
        var operation = new ReplaceDocxTextDTO.Operation();
        operation.setBlockId(target.blockId());
        operation.setText(AcceptanceSampleFactory.EDITED_PARAGRAPH);
        var request = new ReplaceDocxTextDTO();
        request.setOperations(List.of(operation));
        assertEquals(2, service.replaceParagraphText(USER, 101L, 1, request).getVersionNo());
        write("docx-edited.docx", savedBytes, "Only Chinese body paragraph changes; table/image/styled runs/header/footer retained");
        byte[] originalBytes = Files.readAllBytes(OUTPUT.resolve("docx-input.docx"));
        try (var original = new XWPFDocument(new ByteArrayInputStream(originalBytes));
             var edited = new XWPFDocument(new ByteArrayInputStream(savedBytes))) {
            assertEquals(AcceptanceSampleFactory.EDITED_PARAGRAPH, edited.getParagraphs().get(1).getText());
            assertEquals("Heading1", edited.getParagraphs().getFirst().getStyle());
            assertEquals("待验收", edited.getTables().getFirst().getRow(1).getCell(1).getText());
            assertArrayEquals(original.getAllPictures().getFirst().getData(), edited.getAllPictures().getFirst().getData());
            assertTrue(edited.getParagraphs().get(2).getRuns().getFirst().isBold());
            assertEquals("0055AA", edited.getParagraphs().get(2).getRuns().getFirst().getColor());
            assertEquals(original.getHeaderList().getFirst().getText(), edited.getHeaderList().getFirst().getText());
            assertEquals(original.getFooterList().getFirst().getText(), edited.getFooterList().getFirst().getText());
        }
        verify(writer, times(1)).appendVersion(eq(101L), eq(USER), any(), any(), any(), any(), eq(1));
        checks.put("DOCX", "PASSED");
    }

    @Test
    void nativePdfTextAndPreviewUseActualPdfService() throws Exception {
        var service = new PdfDocumentServiceImpl(versions, properties);
        var result = service.readContent(USER, 102L, 1);
        assertEquals("TEXT", result.documentType());
        assertEquals(1, result.totalPages());
        assertTrue(result.pages().getFirst().text().contains("Amount: 123.45"));
        assertFalse(result.textTruncated());
        var preview = service.renderPage(USER, 102L, 1, 1, 72);
        assertPreview(preview.content(), preview.widthPixels(), preview.heightPixels());
        write("native-preview.png", preview.content(), "72 DPI first native PDF page; visual layout still requires review");
        checks.put("NATIVE_PDF", "PASSED");
    }

    @Test
    void scannedAndMixedPdfDoNotPretendImageTextWasRecognized() throws Exception {
        var service = new PdfDocumentServiceImpl(versions, properties);
        var scan = service.readContent(USER, 103L, 1);
        assertEquals("SCANNED", scan.documentType());
        assertEquals(1, scan.scannedPages());
        assertEquals("", scan.pages().getFirst().text());
        var mixed = service.readContent(USER, 104L, 1);
        assertEquals("MIXED", mixed.documentType());
        assertEquals(List.of("TEXT", "SCANNED"), mixed.pages().stream().map(page -> page.type()).toList());
        assertEquals("", mixed.pages().get(1).text());
        var preview = service.renderPage(USER, 103L, 1, 1, 72);
        assertPreview(preview.content(), preview.widthPixels(), preview.heightPixels());
        write("scanned-preview.png", preview.content(), "Image-only PDF preview, OCR NOT executed");
        checks.put("SCANNED_MIXED_PDF", "PASSED");
    }

    @Test
    void xlsxFormulaValuesAndEditedCachesMatchFixedExpectations() throws Exception {
        var service = new XlsxDocumentServiceImpl(versions, storage, writer, properties);
        var formulas = service.calculateFormulas(USER, 105L, 1, "数据", "D2:D7");
        assertEquals(List.of("60", "2", "yes", "20", "14", "#DIV/0!"),
                formulas.results().stream().map(result -> result.value()).toList());
        assertEquals(List.of("OK", "OK", "OK", "OK", "OK", "ERROR"),
                formulas.results().stream().map(result -> result.status()).toList());
        var operation = new EditXlsxCellsDTO.Operation();
        operation.setType("SET_VALUE");
        operation.setSheetName("数据");
        operation.setCell("B2");
        operation.setValueType("NUMBER");
        operation.setValue("15");
        var request = new EditXlsxCellsDTO();
        request.setOperations(List.of(operation));
        request.setRecalculateFormulas(true);
        service.editCells(USER, 105L, 1, request);
        write("formulas-edited.xlsx", savedBytes, "B2=15; D2 SUM cache=65; formula text/styles/date/cross-sheet value and Excel error retained");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(savedBytes))) {
            var data = workbook.getSheet("数据");
            assertEquals(15, data.getRow(1).getCell(1).getNumericCellValue());
            assertEquals("#,##0.00", data.getRow(1).getCell(1).getCellStyle().getDataFormatString());
            var sum = data.getRow(1).getCell(3);
            assertEquals("SUM(B2:B4)", sum.getCellFormula());
            assertEquals(65, sum.getNumericCellValue());
            assertEquals("VLOOKUP(\"B\",A2:B4,2,FALSE)", data.getRow(4).getCell(3).getCellFormula());
            assertEquals(20, data.getRow(4).getCell(3).getNumericCellValue());
            assertEquals(14, data.getRow(5).getCell(3).getNumericCellValue());
            assertEquals(CellType.ERROR, data.getRow(6).getCell(3).getCachedFormulaResultType());
            assertEquals("2026-10-17", data.getRow(1).getCell(5).getLocalDateTimeCellValue().toLocalDate().toString());
        }
        checks.put("XLSX", "PASSED");
    }

    @Test
    void positionedTextOnlyChangesBoundedAreaAndCropMatchesSourcePixels() throws Exception {
        var service = new ImageDocumentServiceImpl(versions, storage, writer, properties);
        var text = new EditImageDTO.Operation();
        text.setType("ADD_TEXT");
        text.setText("A");
        text.setFontSize(24);
        text.setColor("#000000");
        text.setX(180);
        text.setY(30);
        service.edit(USER, 106L, 1, imageRequest(text));
        write("image-local-text.png", savedBytes, "Black A near (180,30); pixels outside x180..219/y30..69 unchanged, alpha retained");
        var original = AcceptanceSampleFactory.colorImage();
        var edited = ImageIO.read(new ByteArrayInputStream(savedBytes));
        int changed = 0;
        for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) {
            if (original.getRGB(x, y) == edited.getRGB(x, y)) continue;
            changed++;
            assertTrue(x >= 180 && x < 220 && y >= 30 && y < 70);
        }
        assertTrue(changed > 0);
        assertEquals(0, edited.getRGB(0, 0));
        assertEquals(0x8044aa66, edited.getRGB(319, 199));

        var crop = new EditImageDTO.Operation();
        crop.setType("CROP");
        crop.setX(20);
        crop.setY(20);
        crop.setWidth(80);
        crop.setHeight(60);
        service.edit(USER, 106L, 1, imageRequest(crop));
        write("image-cropped.png", savedBytes, "80x60 solid red crop of original; independent edit from v1");
        var cropped = ImageIO.read(new ByteArrayInputStream(savedBytes));
        assertEquals(80, cropped.getWidth());
        assertEquals(60, cropped.getHeight());
        for (int y = 0; y < 60; y++) for (int x = 0; x < 80; x++) {
            assertEquals(original.getRGB(x + 20, y + 20), cropped.getRGB(x, y));
        }
        checks.put("LOCAL_IMAGE", "PASSED");
    }

    @Test
    void jpegConversionCompositesTransparencyOnWhite() throws Exception {
        var service = new ImageDocumentServiceImpl(versions, storage, writer, properties);
        var request = new EditImageDTO();
        request.setOutputFormat("JPEG");
        request.setJpegQuality(1.0);
        service.edit(USER, 106L, 1, request);
        write("image-converted.jpg", savedBytes, "320x200 JPEG, no alpha; transparent corner becomes white");
        var image = ImageIO.read(new ByteArrayInputStream(savedBytes));
        assertEquals(320, image.getWidth());
        assertEquals(200, image.getHeight());
        assertFalse(image.getColorModel().hasAlpha());
        assertEquals("image/jpeg", savedContent.contentType());
        int corner = image.getRGB(0, 0);
        assertTrue(((corner >> 16) & 255) > 245 && ((corner >> 8) & 255) > 245 && (corner & 255) > 245);
        checks.put("JPEG", "PASSED");
    }

    @AfterAll
    void writeManifest() throws Exception {
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", 1);
        manifest.put("sampleVersion", "basic-v1");
        manifest.put("generatedAtUtc", java.time.Instant.now().toString());
        manifest.put("scope", "Local real document services/storage; identity and version repositories use substitutes");
        manifest.put("automaticChecks", checks);
        manifest.put("allAutomaticChecksPassed", checks.values().stream().allMatch("PASSED"::equals));
        manifest.put("notExecuted", List.of("OCR recognition", "Chinese OCR", "Word/LibreOffice layout",
                "Real database and HTTP end-to-end flow"));
        var ordered = artifacts.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            var record = new LinkedHashMap<String, Object>();
            record.put("file", entry.getKey());
            record.putAll(entry.getValue());
            return record;
        }).collect(Collectors.toCollection(ArrayList::new));
        manifest.put("artifacts", ordered);
        Files.writeString(OUTPUT.resolve("manifest.json"), new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest), StandardCharsets.UTF_8);
    }

    private void write(String name, byte[] content, String expected) throws Exception {
        assertNotNull(content);
        assertTrue(content.length > 0);
        Files.write(OUTPUT.resolve(name), content);
        artifacts.put(name, Map.of("sizeBytes", content.length, "sha256", Digests.sha256Hex(content), "expected", expected));
    }

    private static EditImageDTO imageRequest(EditImageDTO.Operation operation) {
        var request = new EditImageDTO();
        request.setOperations(List.of(operation));
        return request;
    }

    private static void assertPreview(byte[] png, int width, int height) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }
}
