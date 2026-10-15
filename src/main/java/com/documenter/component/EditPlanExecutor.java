package com.documenter.component;

import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocumentEditPlan.Operation;
import com.documenter.vo.DocumentEditPlan.OperationType;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑计划执行器（TODO 4.2）。
 *
 * <p>把 {@link DocumentEditPlan} 翻译成后端既有的编辑调用。
 *
 * <p><b>为什么按「批次」而不是「操作」组织</b>：后端的编辑接口一次调用只处理
 * 一类目标（段落 / 表格），而一个计划里可能同时包含两者的修改。
 * 如果按操作逐个调用，第一次成功、第二次失败就会留下半成品。
 * 因此这里按接口能力分组，每组一次调用：
 * <ul>
 *   <li>全部段落操作（替换、前插、后插、删除）→ 一次 {@code editParagraphs}</li>
 *   <li>全部单元格操作 → 一次 {@code editTable}</li>
 * </ul>
 * 每组内部由后端在同一份文档对象上完成全部修改后只写一次版本，
 * 因此组内是原子的。跨组的两次调用目前无法做到真正原子 ——
 * 这是已知限制，遇到时会把「已生效的批次」如实告知调用方，
 * 而不是假装全部失败。
 *
 * <p>版本一致性由调用方保证：{@code expectVersion} 会传给后端作为乐观锁，
 * 因此「基于 v1 生成的计划被应用到 v3」会以 409 失败而不是静默覆盖。
 */
@Slf4j
@Component
public class EditPlanExecutor {

    private final DocxDocumentService docxDocumentService;

    public EditPlanExecutor(DocxDocumentService docxDocumentService) {
        this.docxDocumentService = docxDocumentService;
    }

    /**
     * 执行计划。
     *
     * @param userId        已认证用户
     * @param fileId        文件 ID
     * @param expectVersion 计划所依据的版本，作为乐观锁期望值
     * @param plan          编辑计划
     * @param instruction   自然语言指令，写入版本记录便于追溯
     * @return 执行结果：产出或复用的版本，以及各批次的实际结果
     */
    public ExecutionResult execute(Long userId, Long fileId, Integer expectVersion,
                                   DocumentEditPlan plan, String instruction) {
        if (plan == null || plan.isEmpty()) {
            throw new BusinessException(422, "没有可执行的编辑操作");
        }

        List<Operation> paragraphOps = new ArrayList<>();
        List<Operation> tableOps = new ArrayList<>();
        for (Operation operation : plan.operations()) {
            OperationType type = operation.operationType();
            if (type == null) {
                throw new BusinessException(422, "不支持的操作类型：" + operation.type());
            }
            switch (type) {
                case REPLACE_TEXT, INSERT_BEFORE, INSERT_AFTER, DELETE -> paragraphOps.add(operation);
                case SET_CELL_TEXT -> tableOps.add(operation);
                case FORMAT_PARAGRAPH -> throw new BusinessException(422,
                        "FORMAT_PARAGRAPH 尚未接入执行器；请改用其他操作类型或通过编辑接口直接设置格式");
            }
        }

        Integer version = expectVersion;
        VersionVO lastVersion = null;
        List<String> appliedBatches = new ArrayList<>();

        if (!paragraphOps.isEmpty()) {
            EditDocxParagraphsDTO request = new EditDocxParagraphsDTO();
            request.setInstruction(instruction);
            request.setOperations(paragraphOps.stream().map(EditPlanExecutor::toParagraphOperation).toList());
            lastVersion = docxDocumentService.editParagraphs(userId, fileId, version, request);
            appliedBatches.add("段落 " + paragraphOps.size() + " 处");
            // 后续批次必须基于刚产生的版本，否则会被乐观锁拒绝
            version = lastVersion.getVersionNo();
        }

        if (!tableOps.isEmpty()) {
            EditDocxTableDTO request = new EditDocxTableDTO();
            request.setInstruction(instruction);
            request.setOperations(tableOps.stream().map(EditPlanExecutor::toTableCellOperation).toList());
            try {
                lastVersion = docxDocumentService.editTable(userId, fileId, version, request);
                appliedBatches.add("表格 " + tableOps.size() + " 处");
            } catch (RuntimeException e) {
                // 跨批次的原子性无法保证，因此失败时必须如实告知
                // 「前一批已经生效」，否则调用方会以为文档没变
                if (!appliedBatches.isEmpty()) {
                    log.warn("表格批次执行失败，但以下批次已生效: {}, fileId={}",
                            appliedBatches, fileId, e);
                    throw new BusinessException(500,
                            "部分修改已生效（" + String.join("、", appliedBatches)
                                    + "），但表格修改失败：" + e.getMessage()
                                    + "。请刷新查看当前版本后再重试剩余的修改");
                }
                throw e;
            }
        }

        if (lastVersion == null) {
            throw new BusinessException(422, "计划中没有可执行的操作");
        }

        return new ExecutionResult(lastVersion, appliedBatches);
    }

    private static EditDocxParagraphsDTO.Operation toParagraphOperation(Operation operation) {
        EditDocxParagraphsDTO.Operation target = new EditDocxParagraphsDTO.Operation();
        target.setType(operation.type());
        target.setBlockId(operation.target());
        target.setText(operation.content());
        return target;
    }

    private static EditDocxTableDTO.Operation toTableCellOperation(Operation operation) {
        EditDocxTableDTO.Operation target = new EditDocxTableDTO.Operation();
        target.setType(operation.type());
        target.setBlockId(operation.target());
        target.setText(operation.content());
        return target;
    }

    /**
     * 执行结果。
     *
     * @param version         最终产出的版本
     * @param appliedBatches  实际生效的批次说明，用于向用户如实汇报
     */
    public record ExecutionResult(VersionVO version, List<String> appliedBatches) {
    }
}
