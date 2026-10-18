package com.documenter.component;

import com.documenter.component.tool.SetFormulaTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ToolExecutionContext;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.XlsxStructureVO;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SetFormulaToolTest {
    private final XlsxDocumentService spreadsheets = mock(XlsxDocumentService.class);
    private final SetFormulaTool tool = new SetFormulaTool(spreadsheets);
    private final EditPlanValidator validator = new EditPlanValidator(new AiModelProperties());

    @Test
    void blankCellInExistingSheetProducesCanonicalPendingPlanAfterValidation() {
        var result = tool.execute(arguments("年度 数据!汇总", "$d$2", "=SUM(A1:A3)"), context());
        var operation = result.plan().operations().getFirst();
        assertEquals("SET_FORMULA", operation.type());
        assertEquals("年度 数据!汇总!D2", operation.target());
        assertEquals("SUM(A1:A3)", operation.content());
        assertEquals("汇总实际数据", operation.reason());
        validator.validateForContext(result.plan(), context());
        verify(spreadsheets).validateFormulaPlan(eq(7L), eq(10L), eq(1), eq(result.plan()));
        verify(spreadsheets, never()).editFormulaPlan(any(), any(), any(), any(), any());
    }

    @Test
    void wrongFormatSheetRangeOrMissingExplanationCannotProduceAPlan() {
        assertThrows(BusinessException.class, () -> tool.execute(arguments("年度 数据!汇总", "A1", "1+1"),
                ToolExecutionContext.withoutDocument(7L)));
        for (String cell : List.of("A1:B2", "XFE1", "A1048577", "A0")) {
            assertThrows(BusinessException.class, () -> tool.execute(arguments("年度 数据!汇总", cell, "1+1"), context()));
        }
        assertThrows(BusinessException.class, () -> tool.execute(arguments("missing", "A1", "1+1"), context()));
        var args = new HashMap<>(arguments("年度 数据!汇总", "A1", "1+1"));
        args.remove("reason");
        assertThrows(BusinessException.class, () -> tool.execute(args, context()));
        verifyNoInteractions(spreadsheets);
    }

    @Test
    void formulaValidationFailureIsReturnedToModelBeforeAnyPlanCanBeCollected() {
        when(spreadsheets.validateFormulaPlan(any(), any(), any(), any())).thenThrow(new BusinessException(400, "公式引用无效"));
        assertThrows(BusinessException.class,
                () -> tool.execute(arguments("年度 数据!汇总", "D2", "Missing!A1"), context()));
        verify(spreadsheets, never()).editFormulaPlan(any(), any(), any(), any(), any());
    }

    @Test
    void validatorRejectsDuplicateAliasesMixedFormatsAndUnboundFormulaTargets() {
        var first = operation("年度 数据!汇总!D2", "SUM(A1:A3)");
        var alias = operation("年度 数据!汇总!$d$2", "1+1");
        assertThrows(BusinessException.class, () -> validator.validateForContext(plan(first, alias), context()));
        assertThrows(BusinessException.class, () -> validator.validate(plan(first,
                new DocumentEditPlan.Operation("DELETE", "body/p0", null, null)), null));
        assertThrows(BusinessException.class, () -> validator.validateForContext(plan(operation("missing!A1", "1+1")), context()));
        assertThrows(BusinessException.class, () -> validator.validateForContext(plan(first), ToolExecutionContext.withoutDocument(7L)));
        assertThrows(BusinessException.class, () -> validator.validate(plan(operation("年度 数据!汇总!D2", "x".repeat(8193))), null));
    }

    private static Map<String, Object> arguments(String sheet, String cell, String formula) {
        return Map.of("sheetName", sheet, "cell", cell, "formula", formula, "reason", "汇总实际数据");
    }
    private static DocumentEditPlan.Operation operation(String target, String formula) {
        return new DocumentEditPlan.Operation("SET_FORMULA", target, formula, "说明");
    }
    private static DocumentEditPlan plan(DocumentEditPlan.Operation... operations) {
        return new DocumentEditPlan("公式", List.of(operations), null);
    }
    private static ToolExecutionContext context() {
        var sheet = new XlsxStructureVO.Sheet(0, "年度 数据!汇总", null, null, List.of(), List.of(), List.of());
        return ToolExecutionContext.forVersion(7L, 10L, 1, "XLSX", null,
                new XlsxStructureVO(10L, 1, 1, 0, false, List.of(sheet)));
    }
}
