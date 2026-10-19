package com.documenter.component;

import com.documenter.component.tool.NormalizeDocxFormatTool;
import com.documenter.component.tool.ResizeDocxImagesTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.util.DocxNormalizationSpec;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocxStructureVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocxNormalizationToolTest {
    private final NormalizeDocxFormatTool format = new NormalizeDocxFormatTool();
    private final ResizeDocxImagesTool images = new ResizeDocxImagesTool();
    private final EditPlanValidator validator = new EditPlanValidator(new AiModelProperties());

    @Test
    void plansAreBoundToDocumentBodyAndSerializeWithoutChangingExistingOperationShape() {
        var op = format.execute(Map.of("formatJson", "{\"scope\":\"BODY\",\"fontFamily\":\"宋体\",\"fontSizePoints\":12}"), context()).plan().operations().getFirst();
        var pic = images.execute(Map.of("layoutJson", "{\"widthCm\":12}"), context()).plan().operations().getFirst();
        assertEquals("body", op.target()); assertNull(op.image()); assertNull(op.format());
        assertTrue(DocxNormalizationSpec.images(pic.content()).keepAspectRatio());
        var plan = new DocumentEditPlan("normalize", List.of(op, pic), null);
        validator.validateForContext(plan, context());
        var restored = tools.jackson.databind.json.JsonMapper.builder().build().readValue(DocxNormalizationSpec.json(plan), DocumentEditPlan.class);
        assertEquals(plan, restored);
        assertTrue(format.mutating()); assertTrue(images.mutating());
    }

    @Test
    void unboundAndWrongFormatCannotProducePlans() {
        for (var ctx : List.of(ToolExecutionContext.withoutDocument(7L),
                ToolExecutionContext.forVersion(7L, 10L, 1, "XLSX", null, null))) {
            assertCode(() -> format.execute(Map.of("formatJson", "{\"fontFamily\":\"宋体\"}"), ctx));
            assertCode(() -> images.execute(Map.of("layoutJson", "{\"widthCm\":12}"), ctx));
        }
    }

    @Test
    void unknownFieldsWrongTypesNonfiniteAndOutOfRangeArgumentsAreRejected() {
        for (String json : List.of("[]", "{", "{}", "{\"fontSizePoints\":0}", "{\"fontSizePoints\":12.3}",
                "{\"fontSizePoints\":\"12\"}", "{\"fontFamily\":\"宋体\",\"userId\":8}",
                "{\"fontFamily\":\"宋体\",\"includeTables\":\"false\"}",
                "{\"scope\":\"BODY\",\"headingLevel\":1,\"fontSizePoints\":12}",
                "{\"scope\":\"HEADINGS\",\"headingLevel\":1.5,\"fontSizePoints\":12}",
                "{\"lineSpacing\":1e300}", "{\"alignment\":\"SCRIPT\"}")) {
            assertCode(() -> format.execute(Map.of("formatJson", json), context()));
        }
        for (String json : List.of("{}", "{\"widthCm\":0}", "{\"widthCm\":51}", "{\"widthCm\":\"12\"}",
                "{\"widthCm\":12,\"keepAspectRatio\":false}", "{\"widthCm\":12,\"path\":\"/secret\"}")) {
            assertCode(() -> images.execute(Map.of("layoutJson", json), context()));
        }
    }

    @Test
    void confirmedPayloadIsRevalidatedAndDuplicateScopesAreRejected() {
        var op = format.execute(Map.of("formatJson", "{\"fontSizePoints\":12}"), context()).plan().operations().getFirst();
        assertCode(() -> validator.validate(new DocumentEditPlan("duplicate", List.of(op, op), null), context().blockIds()));
        var forged = new DocumentEditPlan.Operation("NORMALIZE_DOCX_FORMAT", "header0", op.content(), null);
        assertCode(() -> validator.validate(new DocumentEditPlan("forged", List.of(forged), null), context().blockIds()));
        var bad = new DocumentEditPlan.Operation("RESIZE_DOCX_IMAGES", "body", "{\"widthCm\":-1}", null);
        assertCode(() -> validator.validate(new DocumentEditPlan("invalid", List.of(bad), null), context().blockIds()));
    }

    @Test
    void bulkBoundsAreCheckedAgainstWholeStructureEvenWhenModelReadoutWouldBeTruncated() {
        List<DocxStructureVO.Block> blocks = new ArrayList<>();
        for (int i = 0; i <= DocxNormalizationSpec.MAX_PARAGRAPHS; i++) blocks.add(block("body/p" + i, "PARAGRAPH", null, List.of()));
        var ctx = ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX", new DocxStructureVO(10L, 1, blocks), null);
        assertCode(() -> format.execute(Map.of("formatJson", "{\"fontFamily\":\"宋体\"}"), ctx));
        assertCode(() -> format.execute(Map.of("formatJson", "{\"scope\":\"HEADINGS\",\"headingLevel\":9,\"fontSizePoints\":16}"), context()));
    }

    private static void assertCode(org.junit.jupiter.api.function.Executable run) {assertEquals(422, assertThrows(BusinessException.class, run).getCode());}
    private static DocxStructureVO.Block block(String id, String type, Integer heading, List<DocxStructureVO.Block> children) {
        return new DocxStructureVO.Block(id, type, "test", heading == null ? null : "Heading" + heading, heading, null, null, null, children);
    }
    private static ToolExecutionContext context() {
        return ToolExecutionContext.forVersion(7L, 10L, 1, "DOCX", new DocxStructureVO(10L, 1, List.of(
                block("body/p0", "PARAGRAPH", null, List.of(block("body/p0/image0", "IMAGE", null, List.of()))),
                block("body/p1", "HEADING", 1, List.of()),
                block("header0", "HEADER", null, List.of(block("header0/p0", "PARAGRAPH", null, List.of()))))), null);
    }
}
