package com.documenter.component;

import com.documenter.component.tool.EditParagraphTool;
import com.documenter.component.tool.ReadDocumentTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocxStructureVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditParagraphToolTest {
    private final EditParagraphTool tool = new EditParagraphTool();

    @Test
    void paginationIsVisibleAndOnlyGeneratesTypedPlansForTopLevelParagraphs() {
        var paragraph = new DocxStructureVO.Block("body/p0", "PARAGRAPH", "chapter", null,
                null, null, null, null, List.of(), null, true);
        var tableParagraph = new DocxStructureVO.Block("body/t0/r0/c0/p0", "PARAGRAPH", "cell", null,
                null, null, null, null, List.of());
        var context = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX",
                new DocxStructureVO(10L, 1, List.of(paragraph, tableParagraph)), null);
        assertTrue(new ReadDocumentTool(new AiModelProperties()).execute(Map.of(), context).text().contains("pageBreakBefore=true"));
        for (String type : List.of("SET_PAGE_BREAK_BEFORE", "CLEAR_PAGE_BREAK_BEFORE")) {
            var result = tool.execute(Map.of("operation", "FORMAT_PARAGRAPH", "target", "body/p0", "formatType", type), context);
            assertEquals(DocumentEditPlan.FormatType.valueOf(type), result.plan().operations().getFirst().format().type());
            assertNull(result.plan().operations().getFirst().format().level());
            assertEquals(422, assertThrows(BusinessException.class, () -> tool.execute(Map.of(
                    "operation", "FORMAT_PARAGRAPH", "target", "body/t0/r0/c0/p0", "formatType", type), context)).getCode());
            assertEquals(422, assertThrows(BusinessException.class, () -> tool.execute(Map.of(
                    "operation", "FORMAT_PARAGRAPH", "target", "body/p0", "formatType", type, "level", 2), context)).getCode());
        }
    }

    @Test
    void manualHeaderAndFooterTargetsAreNotAdvertisedOrAcceptedByAiTools() {
        var body = new DocxStructureVO.Block("body/p0", "PARAGRAPH", "body content", null,
                null, null, null, null, List.of());
        var headerParagraph = new DocxStructureVO.Block("header0/p0", "PARAGRAPH", "header content", null,
                null, null, null, null, List.of());
        var footerParagraph = new DocxStructureVO.Block("footer0/p0", "PARAGRAPH", "footer content", null,
                null, null, null, null, List.of());
        var header = new DocxStructureVO.Block("header0", "HEADER", null, null, null, null, null, null,
                List.of(headerParagraph));
        var footer = new DocxStructureVO.Block("footer0", "FOOTER", null, null, null, null, null, null,
                List.of(footerParagraph));
        var context = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX",
                new DocxStructureVO(10L, 1, List.of(body, header, footer)), null);
        assertEquals(java.util.Set.of("body/p0"), context.blockIds());
        var outline = new ReadDocumentTool(new AiModelProperties()).execute(Map.of(), context).text();
        assertTrue(outline.contains("body content"));
        assertFalse(outline.contains("header content"));
        assertFalse(outline.contains("footer content"));
        for (String target : List.of("header0/p0", "footer0/p0")) {
            assertEquals(422, assertThrows(BusinessException.class, () -> tool.execute(Map.of(
                    "operation", "REPLACE_TEXT", "target", target, "content", "unapproved"), context)).getCode());
        }
    }

    @Test
    void modelCanReadHeadingStyleAndNumberingBeforePlanningFormatting() {
        var block = new DocxStructureVO.Block("body/p0", "HEADING", "list item", "Heading2",
                2, null, null, null, List.of(), "decimal");
        var context = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX",
                new DocxStructureVO(10L, 1, List.of(block)), null);

        var result = new ReadDocumentTool(new AiModelProperties()).execute(Map.of(), context);

        assertTrue(result.text().contains("[body/p0]"));
        assertTrue(result.text().contains("H2"));
        assertTrue(result.text().contains("style=Heading2"));
        assertTrue(result.text().contains("list=decimal"));
        assertTrue(result.text().contains("list item"));
    }

    @Test
    void headingAndListRequestsGenerateTypedPlans() {
        var heading = tool.execute(arguments("SET_HEADING", 2), context()).plan().operations().getFirst();
        assertEquals("FORMAT_PARAGRAPH", heading.type());
        assertEquals(new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, 2), heading.format());
        assertNull(heading.content());
        for (String type : List.of("SET_NORMAL", "SET_BULLET", "SET_NUMBERED", "CLEAR_LIST")) {
            var operation = tool.execute(arguments(type, null), context()).plan().operations().getFirst();
            assertEquals(DocumentEditPlan.FormatType.valueOf(type), operation.format().type());
            assertNull(operation.format().level());
        }
    }

    @Test
    void unknownFormattingAndInvalidHeadingLevelsAreRejected() {
        for (String type : List.of("FREE_STYLE", "Heading2", "execute_script")) {
            assertThrows(BusinessException.class, () -> tool.execute(arguments(type, null), context()));
        }
        for (Integer level : new Integer[]{null, 0, 10}) {
            assertThrows(BusinessException.class, () -> tool.execute(arguments("SET_HEADING", level), context()));
        }
        assertThrows(BusinessException.class, () -> tool.execute(arguments("SET_BULLET", 1), context()));
    }

    @Test
    void formattingCannotBeDisguisedAsTextReplacementOrViceVersa() {
        var text = new HashMap<String, Object>(arguments("SET_HEADING", 2));
        text.put("operation", "REPLACE_TEXT");
        text.put("content", "text");
        assertThrows(BusinessException.class, () -> tool.execute(text, context()));
        text.put("operation", "FORMAT_PARAGRAPH");
        assertThrows(BusinessException.class, () -> tool.execute(text, context()));
    }

    private static Map<String, Object> arguments(String formatType, Integer level) {
        Map<String, Object> args = new HashMap<>();
        args.put("operation", "FORMAT_PARAGRAPH");
        args.put("target", "body/p0");
        args.put("formatType", formatType);
        if (level != null) args.put("level", level);
        return args;
    }

    private static ToolExecutionContext context() {
        var block = new DocxStructureVO.Block("body/p0", "PARAGRAPH", "text", null,
                null, null, null, null, List.of());
        return ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX",
                new DocxStructureVO(10L, 1, List.of(block)), null);
    }
}
