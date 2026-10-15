package com.documenter.service;

import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.XlsxStructureVO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具执行上下文（TODO 4.2「工具统一执行权限和版本检查」）。
 *
 * <p>这个对象是<b>权限边界</b>的载体。关键设计：
 * <ul>
 *   <li>{@link #userId()} 来自已认证的会话，<b>不接受模型提供</b>。
 *       如果让模型传 userId，它可以填别人的 ID —— 这是最直接的一类越权。</li>
 *   <li>{@link #versionNo()} 是本次对话绑定的明确版本。所有修改类工具都会校验
 *       自己操作的是这个版本，避免「基于 v1 生成的计划被应用到 v3」。</li>
 *   <li>文档结构只读取<b>一次</b>并在整个对话中复用。这既省去了重复解析，
 *       也保证了块 ID 的一致性 —— 若每个工具各自重读结构，
 *       期间文档被改动会导致不同工具看到不同的块 ID。</li>
 * </ul>
 */
public class ToolExecutionContext {

    private final Long userId;
    private final Long fileId;
    private final Integer versionNo;
    private final String format;

    /** 该版本的块 ID 集合，用于校验编辑计划的目标是否存在。 */
    private final Set<String> blockIds;

    /** DOCX 结构（仅 DOCX 版本非空）。 */
    private final DocxStructureVO docxStructure;

    /** XLSX 结构（仅 XLSX 版本非空）。 */
    private final XlsxStructureVO xlsxStructure;

    /** 对话内已调用的工具次数，用于强制调用上限。 */
    private int toolCallCount;

    /** 每个工具的调用次数，用于抑制原地打转。 */
    private final java.util.Map<String, Integer> toolUsage = new java.util.HashMap<>();

    /** 修改类工具产出的计划，按顺序累积，等待用户确认后统一执行。 */
    private final List<DocumentEditPlan> collectedPlans = new ArrayList<>();

    /** 本次对话是否已被请求中止。 */
    private boolean cancelled;

    ToolExecutionContext(Long userId, Long fileId, Integer versionNo, String format,
                         DocxStructureVO docxStructure, XlsxStructureVO xlsxStructure) {
        this.userId = userId;
        this.fileId = fileId;
        this.versionNo = versionNo;
        this.format = format;
        this.docxStructure = docxStructure;
        this.xlsxStructure = xlsxStructure;
        this.blockIds = extractBlockIds(docxStructure, xlsxStructure);
    }

    /**
     * 创建未绑定文档的上下文，用于纯对话（没有关联文件）。
     *
     * <p>这样模型在纯对话场景下也没有文档可编辑 —— 它不会凭空产生编辑意图。
     */
    public static ToolExecutionContext withoutDocument(Long userId) {
        return new ToolExecutionContext(userId, null, null, "NONE", null, null);
    }

    /** 创建绑定到明确文档版本的上下文。 */
    public static ToolExecutionContext forVersion(Long userId, Long fileId, Integer versionNo,
                                                 String format, DocxStructureVO docx,
                                                 XlsxStructureVO xlsx) {
        return new ToolExecutionContext(userId, fileId, versionNo, format, docx, xlsx);
    }

    // ------------------------------------------------------------ 只读访问

    public Long userId() {
        return userId;
    }

    public Long fileId() {
        return fileId;
    }

    public Integer versionNo() {
        return versionNo;
    }

    public String format() {
        return format;
    }

    public Set<String> blockIds() {
        return Set.copyOf(blockIds);
    }

    public DocxStructureVO docxStructure() {
        return docxStructure;
    }

    public XlsxStructureVO xlsxStructure() {
        return xlsxStructure;
    }

    public int toolCallCount() {
        return toolCallCount;
    }

    public int usageOf(String toolName) {
        return toolUsage.getOrDefault(toolName, 0);
    }

    public List<DocumentEditPlan> collectedPlans() {
        return List.copyOf(collectedPlans);
    }

    public boolean cancelled() {
        return cancelled;
    }

    // ------------------------------------------------------------ 状态变更

    /** 记录一次工具调用。由智能体循环调用。 */
    public void recordToolCall(String toolName) {
        toolCallCount++;
        toolUsage.merge(toolName, 1, Integer::sum);
    }

    /** 收集修改类工具产出的计划，等待用户确认后统一执行。 */
    public void collectPlan(DocumentEditPlan plan) {
        if (plan != null && !plan.isEmpty()) {
            collectedPlans.add(plan);
        }
    }

    public void cancel() {
        this.cancelled = true;
    }

    // ------------------------------------------------------------ 内部

    /**
     * 提取可用于编辑目标的块 ID。
     *
     * <p>只收「可以直接编辑」的块：段落、标题、单元格。
     * 表格与行是容器，模型对它们的操作意图应当落到具体单元格上；
     * 图片块没有文本内容，编辑它需要的是替换而非改文本。
     * 把容器也放进来会让模型产生「修改 body/t0」这种无法执行的操作。
     */
    private static Set<String> extractBlockIds(DocxStructureVO docx, XlsxStructureVO xlsx) {
        Set<String> ids = new HashSet<>();
        if (docx != null && docx.blocks() != null) {
            collectDocxIds(docx.blocks(), ids);
        }
        if (xlsx != null && xlsx.sheets() != null) {
            for (XlsxStructureVO.Sheet sheet : xlsx.sheets()) {
                if (sheet.cells() == null) {
                    continue;
                }
                for (XlsxStructureVO.Cell cell : sheet.cells()) {
                    ids.add(sheet.name() + "!" + cell.address());
                }
            }
        }
        return ids;
    }

    private static void collectDocxIds(List<DocxStructureVO.Block> blocks, Set<String> ids) {
        for (DocxStructureVO.Block block : blocks) {
            String type = block.type();
            if ("PARAGRAPH".equals(type) || "HEADING".equals(type) || "CELL".equals(type)) {
                if (block.blockId() != null && !block.blockId().isBlank()) {
                    ids.add(block.blockId());
                }
            }
            if (block.children() != null && !block.children().isEmpty()) {
                collectDocxIds(block.children(), ids);
            }
        }
    }
}
