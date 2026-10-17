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
