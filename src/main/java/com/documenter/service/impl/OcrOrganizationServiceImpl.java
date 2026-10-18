package com.documenter.service.impl;

import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.OcrOrganizeRequestDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.AgentService;
import com.documenter.service.OcrCorrectionService;
import com.documenter.service.OcrOrganizationService;
import com.documenter.util.PromptInjectionGuard;
import com.documenter.vo.AgentRunResult;
import com.documenter.vo.OcrCorrectionVO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 复用现有 ChatModel、额度、重试与用量统计，不开放任何文档写工具。 */
@Service
public class OcrOrganizationServiceImpl implements OcrOrganizationService {

    private static final String DEFAULT_INSTRUCTION =
            "把 OCR 人工校正结果整理为结构清晰的 Markdown。保留页面边界、表格行列和合并跨度，"
                    + "不添加原文没有的事实；无法确定的内容明确标注待核验。";

    private final OcrCorrectionService corrections;
    private final AgentService agentService;
    private final AiModelProperties properties;

    public OcrOrganizationServiceImpl(OcrCorrectionService corrections,
                                      AgentService agentService,
                                      AiModelProperties properties) {
        this.corrections = corrections;
        this.agentService = agentService;
        this.properties = properties;
    }

    @Override
    public AgentRunResult organize(Long userId, Long fileId, Integer versionNo,
                                   String fingerprint, OcrOrganizeRequestDTO request) {
        OcrCorrectionVO correction = corrections.get(userId, fileId, versionNo, fingerprint);
        String content = correctedContent(correction);
        if (content.isBlank()) {
            throw new BusinessException(422, "人工校正结果没有可整理的文本或表格");
        }
        String instruction = request == null || request.getInstruction() == null
                || request.getInstruction().isBlank()
                ? DEFAULT_INSTRUCTION : request.getInstruction().trim();
        String prompt = instruction + "\n\n"
                + PromptInjectionGuard.wrapAsData(content);
        if (prompt.length() > properties.getMaxDocumentChars()) {
            throw new BusinessException(413,
                    "人工校正结果超过 AI 整理上下文限制，请减少页面或拆分整理");
        }
        // 不绑定 fileId/version：本次请求只能整理已保存的文本，不能调用文档写工具。
        return agentService.runConversation(userId, null, null, prompt,
                List.of(), () -> false);
    }

    private static String correctedContent(OcrCorrectionVO correction) {
        StringBuilder builder = new StringBuilder();
        for (OcrCorrectionVO.Page page : correction.pages()) {
            if (!builder.isEmpty()) builder.append("\n\n");
            builder.append("【第 ").append(page.pageNo()).append(" 页】\n");
            if (page.text() != null && !page.text().isBlank()) {
                builder.append(page.text()).append('\n');
            }
            for (OcrCorrectionVO.Table table : page.tables()) {
                builder.append("\n[表格 ").append(table.tableIndex())
                        .append("：").append(table.rows()).append(" 行 × ")
                        .append(table.columns()).append(" 列]\n");
                for (OcrCorrectionVO.Cell cell : table.cells()) {
                    builder.append("r").append(cell.row())
                            .append("c").append(cell.column())
                            .append(" span=").append(cell.rowSpan()).append('x')
                            .append(cell.columnSpan())
                            .append(": ").append(cell.text()).append('\n');
                }
            }
        }
        return builder.toString().strip();
    }
}
