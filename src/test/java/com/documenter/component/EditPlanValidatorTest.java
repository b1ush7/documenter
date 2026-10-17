package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocumentEditPlan.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditPlanValidatorTest {

    private final EditPlanValidator validator = new EditPlanValidator(new AiModelProperties());

    @Test
    void supportedParagraphAndCellTargetsAreAccepted() {
        assertDoesNotThrow(() -> validate(
                new Operation("REPLACE_TEXT", "body/p0", "new", null),
                new Operation("SET_CELL_TEXT", "body/t0/r0/c0", "cell", null)));
    }

    @Test
    void paragraphOperationCannotTargetCell() {
        assertRejected(new Operation("DELETE", "body/t0/r0/c0", null, null));
    }

    @Test
    void cellOperationCannotTargetParagraph() {
        assertRejected(new Operation("SET_CELL_TEXT", "body/p0", "new", null));
    }

    @Test
    void unsupportedXlsxPlanIsRejectedBeforeConfirmation() {
        assertRejected(new Operation("SET_CELL_TEXT", "Sheet1!A1", "new", null));
    }

    @Test
    void imagePlanRequiresAnExplicitVersionAndBoundedDimensions() {
        var image = new DocumentEditPlan.ImageReference(20L, 2, 400, 300);
        assertDoesNotThrow(() -> validate(new Operation("INSERT_IMAGE", "body/p0", null, "logo", image)));
        for (var invalid : List.of(
                new DocumentEditPlan.ImageReference(0L, 2, 400, 300),
                new DocumentEditPlan.ImageReference(20L, null, 400, 300),
                new DocumentEditPlan.ImageReference(20L, 0, 400, 300),
                new DocumentEditPlan.ImageReference(20L, 2, 0, 300),
                new DocumentEditPlan.ImageReference(20L, 2, 400, 10001))) {
            assertRejected(new Operation("INSERT_IMAGE", "body/p0", null, null, invalid));
        }
        assertRejected(new Operation("INSERT_IMAGE", "body/p0", null, null));
    }

    @Test
    void imageParametersCannotBeSmuggledIntoTextOrCellOperations() {
        var image = new DocumentEditPlan.ImageReference(20L, 2, 400, 300);
        assertRejected(new Operation("REPLACE_TEXT", "body/p0", "text", null, image));
        assertRejected(new Operation("SET_CELL_TEXT", "body/t0/r0/c0", "text", null, image));
        assertRejected(new Operation("INSERT_IMAGE", "body/p0", "unexpected", null, image));
        assertRejected(new Operation("INSERT_IMAGE", "body/t0/r0/c0", null, null, image));
    }

    @Test
    void cellReplacementCannotInvalidateAnImageTargetInsideIt() {
        var image = new DocumentEditPlan.ImageReference(20L, 2, 400, 300);
        assertThrows(BusinessException.class, () -> validate(
                new Operation("INSERT_IMAGE", "body/t0/r0/c0/p0", null, null, image),
                new Operation("SET_CELL_TEXT", "body/t0/r0/c0", "new", null)));
    }

    @Test
    void imageCountCannotExceedExistingBatchLimit() {
        var image = new DocumentEditPlan.ImageReference(20L, 2, 400, 300);
        var operations = java.util.stream.IntStream.range(0, 21).mapToObj(index ->
                new Operation("INSERT_IMAGE", "body/p" + index, null, null, image)).toList();
        assertEquals(422, assertThrows(BusinessException.class, () ->
                validator.validate(new DocumentEditPlan("too many images", operations, null), null)).getCode());
    }

    @Test
    void oldTextPlansAndNewImagePlansRoundTripThroughStoredJson() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var old = mapper.readValue("{\"summary\":\"old\",\"operations\":[{\"type\":\"REPLACE_TEXT\","
                + "\"target\":\"body/p0\",\"content\":\"text\",\"reason\":null}],\"expected\":null}", DocumentEditPlan.class);
        assertNull(old.operations().getFirst().image());
        assertDoesNotThrow(() -> validator.validate(old, Set.of("body/p0")));
        var image = new DocumentEditPlan.ImageReference(20L, 2, 400, 300);
        var current = new DocumentEditPlan("image", List.of(
                new Operation("INSERT_IMAGE", "body/p0", null, "logo", image)), null);
        assertEquals(current, mapper.readValue(mapper.writeValueAsString(current), DocumentEditPlan.class));
    }

    @Test
    void typedFormattingAcceptsOnlySupportedParameters() {
        var heading = new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, 2);
        assertDoesNotThrow(() -> validate(format(heading)));
        for (var invalid : List.of(
                new DocumentEditPlan.ParagraphFormat(null, null),
                new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, null),
                new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, 10),
                new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_NORMAL, 2))) {
            assertRejected(format(invalid));
        }
        assertRejected(new Operation("REPLACE_TEXT", "body/p0", "text", null, null, heading));
        assertRejected(new Operation("FORMAT_PARAGRAPH", "body/p0", "Heading2", null, null, heading));
    }

    @Test
    void formattingCanCombineWithOneTextOrImageChangeOnSameParagraph() {
        var format = format(new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, 2));
        var text = new Operation("REPLACE_TEXT", "body/p0", "new", null);
        var image = new Operation("INSERT_IMAGE", "body/p0", null, null,
                new DocumentEditPlan.ImageReference(20L, 1, 80, 40));
        assertDoesNotThrow(() -> validate(text, format));
        assertDoesNotThrow(() -> validate(format, text));
        assertDoesNotThrow(() -> validate(image, format));
        assertDoesNotThrow(() -> validate(format, image));
        assertThrows(BusinessException.class, () -> validate(format, text, image));
        assertThrows(BusinessException.class, () -> validate(format, format));
        assertThrows(BusinessException.class, () -> validate(new Operation("DELETE", "body/p0", null, null), format));
    }

    @Test
    void formattingPlanRoundTripsWithoutBreakingOldPlans() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var plan = new DocumentEditPlan("style", List.of(format(
                new DocumentEditPlan.ParagraphFormat(DocumentEditPlan.FormatType.SET_HEADING, 3))), null);
        assertEquals(plan, mapper.readValue(mapper.writeValueAsString(plan), DocumentEditPlan.class));
    }

    private static Operation format(DocumentEditPlan.ParagraphFormat format) {
        return new Operation("FORMAT_PARAGRAPH", "body/p0", null, "style", null, format);
    }

    @Test
    void unstructuredLegacyFormattingIsRejectedButTypedFormattingIsAdvertised() {
        assertRejected(new Operation("FORMAT_PARAGRAPH", "body/p0", "Heading1", null));
        assertTrue(EditPlanValidator.supportedTypeNames().contains("FORMAT_PARAGRAPH"));
    }

    @Test
    void cellAndItsDescendantCannotBeReplacedTogetherInEitherOrder() {
        Operation cell = new Operation("SET_CELL_TEXT", "body/t0/r0/c0", "cell", null);
        Operation paragraph = new Operation("REPLACE_TEXT", "body/t0/r0/c0/p0", "text", null);
        assertThrows(BusinessException.class, () -> validate(cell, paragraph));
        assertThrows(BusinessException.class, () -> validate(paragraph, cell));
    }

    @Test
    void nestedTableTargetsAreAccepted() {
        assertDoesNotThrow(() -> validate(
                new Operation("SET_CELL_TEXT", "body/t0/r0/c0/t0/r0/c0", "cell", null),
                new Operation("INSERT_AFTER", "body/t0/r0/c0/p0", "text", null)));
    }

    private void assertRejected(Operation operation) {
        BusinessException error = assertThrows(BusinessException.class, () -> validate(operation));
        assertEquals(422, error.getCode());
    }

    private void validate(Operation... operations) {
        validator.validate(new DocumentEditPlan("test", List.of(operations), null),
                Set.of("body/p0", "body/t0/r0/c0", "body/t0/r0/c0/p0",
                        "body/t0/r0/c0/t0/r0/c0", "Sheet1!A1"));
    }
}
