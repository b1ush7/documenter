package com.documenter.component;

import com.documenter.exception.BusinessException;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DocxTemplateRendererTest {
    private static final String DEFINITIONS = "[{\"name\":\"title\",\"required\":true},{\"name\":\"optional\"}]";
    private final DocxTemplateRenderer renderer = new DocxTemplateRenderer();

    @Test
    void splitRunsAndTableValuesPreserveSurroundingStylesAndDocumentParts() throws Exception {
        byte[] source = document(doc -> {
            var paragraph = doc.createParagraph();
            var first = paragraph.createRun();
            first.setBold(true);
            first.setText("前缀 {{ti");
            var last = paragraph.createRun();
            last.setItalic(true);
            last.setColor("0055AA");
            last.setText("tle}} 后缀");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("{{title}}/{{optional}}");
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("保留页眉");
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("保留页脚");
        });
        try (var output = open(renderer.render(source, DEFINITIONS, Map.of("title", "标题")))) {
            var paragraph = output.getParagraphs().getFirst();
            assertEquals("前缀 标题 后缀", paragraph.getText());
            assertTrue(paragraph.getRuns().getFirst().isBold());
            assertEquals("前缀 标题", paragraph.getRuns().getFirst().text());
            assertTrue(paragraph.getRuns().get(1).isItalic());
            assertEquals("0055AA", paragraph.getRuns().get(1).getColor());
            assertEquals(" 后缀", paragraph.getRuns().get(1).text());
            assertEquals("标题/", output.getTables().getFirst().getRow(0).getCell(0).getText());
            assertTrue(output.getHeaderList().getFirst().getText().contains("保留页眉"));
            assertTrue(output.getFooterList().getFirst().getText().contains("保留页脚"));
        }
        try (var original = open(source)) { assertEquals("前缀 {{title}} 后缀", original.getParagraphs().getFirst().getText()); }
    }

    @Test
    void repeatedVariablesAndEmptyTextNodesResolveWithoutRecursiveEvaluation() throws Exception {
        String text = "pre{{title}}/{{optional}}/{{title}}post";
        var source = document(doc -> {
            var paragraph = doc.createParagraph();
            for (char character : text.toCharArray()) {
                var run = paragraph.createRun();
                run.getCTR().addNewT().setStringValue("");
                run.getCTR().addNewT().setStringValue(String.valueOf(character));
            }
        });
        try (var output = open(renderer.render(source, DEFINITIONS, Map.of("title", "<tag>&{{literal}}")))) {
            assertEquals("pre<tag>&{{literal}}//<tag>&{{literal}}post", output.getParagraphs().getFirst().getText());
        }
    }

    @Test
    void multipleTokensInOneTextNodeAndLineBreaksRemainLiteralText() throws Exception {
        var source = document(doc -> doc.createParagraph().createRun().setText("{{title}}|{{title}}"));
        try (var output = open(renderer.render(source, DEFINITIONS, Map.of("title", "A\r\nB\tC")))) {
            var paragraph = output.getParagraphs().getFirst();
            assertEquals("A\nB\tC|A\nB\tC", paragraph.getText());
            assertEquals(2, paragraph.getRuns().getFirst().getCTR().sizeOfBrArray());
            assertEquals(2, paragraph.getRuns().getFirst().getCTR().sizeOfTabArray());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{unknown}}", "{{title", "title}}", "{{ title }}", "{{#loop}}", "{{{title}}}"})
    void unknownOrMalformedTemplateTokensAreRejected(String text) throws Exception {
        assertEquals(422, assertThrows(BusinessException.class,
                () -> renderer.render(document(doc -> doc.createParagraph().createRun().setText(text)),
                        DEFINITIONS, Map.of("title", "x"))).getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEADER", "FIELD", "HYPERLINK", "BREAK", "CONTENT_CONTROL"})
    void unsupportedContainersAreRejectedInsteadOfPartiallyFilled(String container) throws Exception {
        var source = document(doc -> {
            switch (container) {
                case "CONTENT_CONTROL" -> doc.getDocument().getBody().addNewSdt().addNewSdtContent()
                        .addNewP().addNewR().addNewT().setStringValue("{{title}}");
                case "HEADER" -> {
                    var paragraph = doc.createHeader(HeaderFooterType.DEFAULT).createParagraph();
                    paragraph.createRun().setText("{{ti");
                    paragraph.createRun().setText("tle}}");
                }
                case "FIELD" -> doc.createParagraph().createRun().getCTR().addNewInstrText().setStringValue("{{title}}");
                case "HYPERLINK" -> doc.createParagraph().createHyperlinkRun("https://example.invalid").setText("{{title}}");
                case "BREAK" -> {
                    var run = doc.createParagraph().createRun();
                    run.setText("{{ti");
                    run.addBreak();
                    run.setText("tle}}");
                }
                default -> fail("unexpected case");
            }
        });
        assertEquals(422, assertThrows(BusinessException.class,
                () -> renderer.render(source, DEFINITIONS, Map.of("title", "x"))).getCode());
    }

    @Test
    void requiredMissingUnknownFieldsAndNonStringValuesFailAsInputErrors() throws Exception {
        var source = document(doc -> doc.createParagraph().createRun().setText("{{title}}"));
        for (var values : List.of(Map.<String, Object>of(), Map.<String, Object>of("title", " "),
                Map.<String, Object>of("title", 123), Map.<String, Object>of("title", List.of("x")),
                Map.<String, Object>of("title", "x", "extra", "value"),
                Map.<String, Object>of("title", "\u0000"), Map.<String, Object>of("title", "x".repeat(10_001)))) {
            assertEquals(400, assertThrows(BusinessException.class, () -> renderer.render(source, DEFINITIONS, values)).getCode());
        }
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("title", "ok");
        nullable.put("optional", null);
        try (var output = open(renderer.render(source, DEFINITIONS, nullable))) {
            assertEquals("ok", output.getParagraphs().getFirst().getText());
        }
    }

    @Test
    void invalidStoredDefinitionsAndDamagedDocxAreTemplateErrors() throws Exception {
        var source = document(doc -> doc.createParagraph().createRun().setText("literal"));
        assertEquals(422, assertThrows(BusinessException.class, () -> renderer.render(source, "{}", Map.of())).getCode());
        assertEquals(422, assertThrows(BusinessException.class, () -> renderer.render(new byte[]{1, 2}, null, Map.of())).getCode());
    }

    @Test
    void expansionAndOccurrenceLimitsAreEnforcedBeforeProducingAnOutput() throws Exception {
        var expanded = document(doc -> doc.createParagraph().createRun().setText("{{title}}".repeat(201)));
        assertEquals(413, assertThrows(BusinessException.class,
                () -> renderer.render(expanded, DEFINITIONS, Map.of("title", "x".repeat(10_000)))).getCode());
        var repeated = document(doc -> doc.createParagraph().createRun().setText("{{title}}".repeat(10_001)));
        assertEquals(413, assertThrows(BusinessException.class,
                () -> renderer.render(repeated, DEFINITIONS, Map.of("title", "x"))).getCode());
    }

    private static byte[] document(Consumer<XWPFDocument> editor) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            editor.accept(document);
            document.write(output);
            return output.toByteArray();
        }
    }
    private static XWPFDocument open(byte[] bytes) throws Exception { return new XWPFDocument(new ByteArrayInputStream(bytes)); }
}
