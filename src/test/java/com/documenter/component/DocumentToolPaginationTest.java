package com.documenter.component;

import com.documenter.component.tool.EditParagraphTool;
import com.documenter.component.tool.EditParagraphsTool;
import com.documenter.component.tool.ReadDocumentTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocxStructureVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DocumentToolPaginationTest {
    private ToolExecutionContext context() {
        return ToolExecutionContext.forVersion(1L, 2L, 1, "DOCX", new DocxStructureVO(2L, 1,
                IntStream.range(0, 240).mapToObj(i -> new DocxStructureVO.Block("body/p" + i,
                        "PARAGRAPH", i == 220 ? "3.2 未标记的章节" : "段落" + i + "x".repeat(100),
                        null, null, null, null, null, List.of())).toList()), null);
    }

    @Test
    void paginationFitsOuterBudgetAndReachesEveryBlockWithoutSplittingLines() {
        var properties = new AiModelProperties();
        properties.setMaxToolResultChars(1300);
        var tool = new ReadDocumentTool(properties);
        int offset = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (true) {
            String text = tool.execute(Map.of("offset", offset), context()).text();
            assertTrue(text.length() <= 1300);
            var ids = java.util.regex.Pattern.compile("\\[(body/p[0-9]+)]").matcher(text);
            while (ids.find()) assertTrue(seen.add(ids.group(1)), "分页不能重复块");
            if (text.contains("hasMore=false")) break;
            var next = java.util.regex.Pattern.compile("nextOffset=([0-9]+)").matcher(text);
            assertTrue(next.find());
            int value = Integer.parseInt(next.group(1));
            assertTrue(value > offset);
            offset = value;
        }
        assertEquals(240, seen.size());
    }

    @Test
    void outlineFindsUnstyledHeadingAfterLongCodeAndSearchCanReachDocumentTail() {
        var tool = new ReadDocumentTool(new AiModelProperties());
        assertTrue(tool.execute(Map.of("mode", "outline"), context()).text().contains("body/p220"));
        assertTrue(tool.execute(Map.of("query", "body/p239]"), context()).text().contains("段落239"));
        assertThrows(BusinessException.class, () -> tool.execute(Map.of("offset", -1), context()));
    }

    @Test
    void batchFormatsMoreThanFourTargetsAndRejectsInvalidOrDuplicateTargetsAtomically() {
        var properties = new AiModelProperties();
        var tool = new EditParagraphsTool(new EditParagraphTool(), new EditPlanValidator(properties));
        String json = IntStream.range(0, 8).mapToObj(i -> "{\"operation\":\"FORMAT_PARAGRAPH\",\"target\":\"body/p"
                + i + "\",\"formatType\":\"SET_HEADING\",\"level\":2}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var context = context();
        assertEquals(8, tool.execute(Map.of("operationsJson", json), context).plan().operations().size());
        assertThrows(BusinessException.class, () -> tool.execute(Map.of("operationsJson", json.replace("body/p7", "body/p999")), context));
        assertThrows(BusinessException.class, () -> tool.execute(Map.of("operationsJson", json.replace("body/p7", "body/p0")), context));
        assertTrue(context.collectedPlans().isEmpty());
    }
}
