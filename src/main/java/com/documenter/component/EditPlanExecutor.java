package com.documenter.component;

import com.documenter.service.DocxDocumentService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.exception.BusinessException;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.VersionVO;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按格式执行已确认的 DOCX 或 XLSX 公式计划，一次计划只写入一个版本。 */
@Component
public class EditPlanExecutor {

    private final DocxDocumentService docxDocumentService;
    private final XlsxDocumentService xlsxDocumentService;

    public EditPlanExecutor(DocxDocumentService docxDocumentService, XlsxDocumentService xlsxDocumentService) {
        this.docxDocumentService = docxDocumentService;
        this.xlsxDocumentService = xlsxDocumentService;
    }

    public ExecutionResult execute(Long userId, Long fileId, Integer expectVersion,
                                   DocumentEditPlan plan, String instruction) {
        if (plan == null || plan.isEmpty()) {
            throw new BusinessException(422, "没有可执行的编辑操作");
        }
        boolean xlsx = plan.operations().stream().anyMatch(op -> isXlsxOperation(op.operationType()));
        if (xlsx && plan.operations().stream().anyMatch(op -> !isXlsxOperation(op.operationType()))) {
            throw new BusinessException(422, "同一计划不能混合 DOCX 与 XLSX 操作");
        }
        VersionVO version = xlsx ? xlsxDocumentService.editAiPlan(userId, fileId, expectVersion, plan, instruction)
                : docxDocumentService.editPlan(userId, fileId, expectVersion, plan, instruction);
        return new ExecutionResult(version, List.of("编辑 " + plan.operations().size() + " 处"));
    }

    private static boolean isXlsxOperation(DocumentEditPlan.OperationType type) {
        return type == DocumentEditPlan.OperationType.SET_FORMULA
                || type == DocumentEditPlan.OperationType.CREATE_TABLE
                || type == DocumentEditPlan.OperationType.CREATE_CHART;
    }

    /** 保留原有服务层结果结构；一次计划现在只产生一个原子批次。 */
    public record ExecutionResult(VersionVO version, List<String> appliedBatches) {
    }
}
