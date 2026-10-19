package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
import com.documenter.service.ToolExecutionContext;
import com.documenter.util.XlsxFormulaTarget;
import com.documenter.util.DocxNormalizationSpec;
import com.documenter.exception.BusinessException;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.DocumentEditPlan.Operation;
import com.documenter.vo.DocumentEditPlan.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/**
 * 编辑计划校验器（TODO 4.2「校验目标、字段和操作范围」）。
 *
 * <p>为什么要有一个集中的校验环节：模型输出的计划是<b>不可信输入</b>，
 * 它可能引用不存在的块、给出后端不支持的操作、或者试图一次改几百处。
 * 如果把这些问题留到执行阶段，会出现「改了一半失败」的中间状态 ——
 * 文档既不是原样也不是期望结果，用户还得自己判断哪里出了问题。
 *
 * <p>因此策略是<b>先整体校验，全部合法才执行</b>：
 * 任何一项不合格就把问题清单退回给模型重来，而不是部分执行。
 *
 * <p>校验分四类，与 TODO 的措辞一一对应：
 * <ol>
 *   <li><b>目标</b>：块 ID 必须存在于该版本的文档结构里。
 *       这是最关键的一项 —— 模型很容易凭想象编造 {@code body/p99}；</li>
 *   <li><b>字段</b>：操作类型必须被支持，且该类型要求的内容字段不能为空；</li>
 *   <li><b>操作范围</b>：操作数量上限、同一目标不重复修改、
 *       以及删除操作不能超出文档允许的范围（例如不能删掉全部内容）；</li>
 *   <li><b>意图完整性</b>：计划不能为空，否则等于「什么都没做」却返回成功。</li>
 * </ol>
 */
@Slf4j
@Component
public class EditPlanValidator {

    /** 单次计划的操数量上限。防止一次请求把整篇文档重写。 */
    private static final int MAX_OPERATIONS = 50;

    /** 单个操作内容的字符数上限，防止把整篇文档塞进一个段落。 */
    private static final int MAX_CONTENT_CHARS = 20000;

    private final AiModelProperties properties;

    public EditPlanValidator(AiModelProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验计划。
     *
     * @param plan            待校验的计划
     * @param availableBlocks 该版本中真实存在的块 ID 集合；为 null 时跳过目标存在性校验
     *                        （用于尚未读取结构的场景）
     * @throws BusinessException 校验不通过，消息中包含全部问题以便退回给模型
     */
    public void validate(DocumentEditPlan plan, Set<String> availableBlocks) {
        if (plan == null) {
            throw new BusinessException(422, "模型未产出编辑计划");
        }

        List<String> problems = new ArrayList<>();

        if (plan.isEmpty()) {
            problems.add("计划中没有可执行操作");
        }
        if (plan.operations().size() > MAX_OPERATIONS) {
            problems.add("操作数量 " + plan.operations().size() + " 超过上限 "
                    + MAX_OPERATIONS + "；请拆分为多次较小的修改");
        }

        Set<String> touchedTargets = new HashSet<>();
        Map<String, Set<OperationType>> targetTypes = new HashMap<>();
        int deletions = 0;
        int images = 0;
        Set<String> formulaTargets = new HashSet<>();
        boolean hasXlsx = false;
        boolean hasDocx = false;
        Set<String> batchSelectors = new HashSet<>();

        for (int index = 0; index < plan.operations().size(); index++) {
            Operation operation = plan.operations().get(index);
            String position = "第 " + (index + 1) + " 个操作";

            OperationType type = operation.operationType();
            if (type == null) {
                // 模型编造操作名是常见情况，这里明确列出支持的类型帮助它纠正
                problems.add(position + "的操作类型「" + operation.type() + "」不被支持；支持的类型："
                        + supportedTypeNames());
                continue;
            }

            if (isXlsxOperation(type)) {
                hasXlsx = true;
                if (operation.target() == null || operation.target().isBlank()) {
                    problems.add(position + "缺少 XLSX 目标");
                }
                if (operation.content() == null || operation.content().isBlank()
                        || operation.content().length() > (type == OperationType.SET_FORMULA ? 8192 : 100_000)) {
                    problems.add(position + "的 XLSX 操作内容为空或超长");
                }
                if (operation.image() != null || operation.format() != null) {
                    problems.add(position + "的 XLSX 操作不能携带图片或段落格式");
                }
                if (type == OperationType.SET_FORMULA) {
                    try {
                        var target = XlsxFormulaTarget.parse(operation.target());
                        if (!formulaTargets.add(target.key())) problems.add(position + "重复修改同一个公式目标");
                    } catch (BusinessException invalid) {
                        problems.add(position + invalid.getMessage());
                    }
                }
                continue;
            }
            hasDocx = true;

            if (type == OperationType.NORMALIZE_DOCX_FORMAT || type == OperationType.RESIZE_DOCX_IMAGES) {
                if (!"body".equals(operation.target()) || operation.image() != null || operation.format() != null) {
                    problems.add(position + "批量操作仅支持 target=body，且不能包含 image/format");
                }
                try {
                    String selector = type == OperationType.NORMALIZE_DOCX_FORMAT
                            ? DocxNormalizationSpec.format(operation.content()).selector()
                            : DocxNormalizationSpec.json(DocxNormalizationSpec.images(operation.content()));
                    if (!batchSelectors.add(type + ":" + selector)) problems.add(position + "重复的批量格式范围");
                } catch (BusinessException invalid) { problems.add(position + "：" + invalid.getMessage()); }
                continue;
            }

            if (operation.target() == null || operation.target().isBlank()) {
                problems.add(position + "缺少目标块标识");
            } else {
                String pattern = type == OperationType.SET_CELL_TEXT
                        ? "^body(?:/t\\d+/r\\d+/c\\d+)+$"
                        : "^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$";
                if (!operation.target().matches(pattern)) {
                    problems.add(position + "的目标类型与操作不匹配；当前计划执行仅支持 DOCX 段落与单元格");
                }
                if (availableBlocks != null && !availableBlocks.contains(operation.target())) {
                    problems.add(position + "的目标「" + operation.target() + "」在该版本中不存在");
                }
                touchedTargets.add(operation.target());
                Set<OperationType> previous = targetTypes.computeIfAbsent(operation.target(), key -> new HashSet<>());
                boolean formattingPair = previous.size() == 1 && (
                        (type == OperationType.FORMAT_PARAGRAPH && (previous.contains(OperationType.REPLACE_TEXT)
                                || previous.contains(OperationType.INSERT_IMAGE)))
                        || (previous.contains(OperationType.FORMAT_PARAGRAPH)
                                && (type == OperationType.REPLACE_TEXT || type == OperationType.INSERT_IMAGE)));
                if (!previous.isEmpty() && !formattingPair) {
                    problems.add(position + "重复修改同一个目标「" + operation.target() + "」；"
                            + "请合并为一次操作");
                }
                previous.add(type);
            }

            if (type.requiresContent()) {
                if (operation.content() == null || operation.content().isBlank()) {
                    problems.add(position + "（" + type + "）缺少内容");
                } else if (operation.content().length() > MAX_CONTENT_CHARS) {
                    problems.add(position + "的内容长度 " + operation.content().length()
                            + " 超过上限 " + MAX_CONTENT_CHARS);
                }
            }

            if (type == OperationType.INSERT_IMAGE) {
                images++;
                var image = operation.image();
                if (image == null || image.fileId() == null || image.fileId() <= 0
                        || image.versionNo() == null || image.versionNo() <= 0
                        || image.widthPixels() == null || image.widthPixels() < 1 || image.widthPixels() > 10000
                        || image.heightPixels() == null || image.heightPixels() < 1 || image.heightPixels() > 10000) {
                    problems.add(position + "必须提供正数图片 ID、版本，以及 1 到 10000 像素的宽高");
                }
                if (operation.content() != null) {
                    problems.add(position + "的 INSERT_IMAGE 只能使用 image，不能包含文本 content");
                }
            } else if (operation.image() != null) {
                problems.add(position + "不是图片插入操作，不能包含 image 字段");
            }

            if (type == OperationType.FORMAT_PARAGRAPH) {
                var format = operation.format();
                if (format != null && format.type() != null && format.type().isPageBreakBefore()
                        && (operation.target() == null || !operation.target().matches("^body/p\\d+$"))) {
                    problems.add(position + "的段前分页仅支持正文顶层段落 body/pN");
                }
                if (format == null || format.type() == null) {
                    problems.add(position + "必须提供受支持的 format.type");
                } else if (format.type() == DocumentEditPlan.FormatType.SET_HEADING) {
                    if (format.level() == null || format.level() < 1 || format.level() > 9) {
                        problems.add(position + "的标题级别必须在 1 到 9 之间");
                    }
                } else if (format.level() != null) {
                    problems.add(position + "只有 SET_HEADING 可以提供 level");
                }
                if (operation.content() != null) problems.add(position + "格式操作不能包含 content");
            } else if (operation.format() != null) {
                problems.add(position + "不是格式操作，不能包含 format 字段");
            }

            if (type == OperationType.DELETE) {
                deletions++;
                if (operation.content() != null) problems.add(position + "的 DELETE 不能包含 content");
            }
        }

        if (images > 20) problems.add("单次计划最多插入 20 张图片");
        if (hasXlsx && hasDocx) problems.add("同一计划不能混合 DOCX 与 XLSX 操作");

        for (Operation operation : plan.operations()) {
            if (operation.operationType() == OperationType.SET_CELL_TEXT && operation.target() != null) {
                for (String target : touchedTargets) {
                    if (target.startsWith(operation.target() + "/")) {
                        problems.add("不能同时替换单元格及其内部内容：" + operation.target());
                        break;
                    }
                }
            }
        }

        // 全部块都被删除通常是模型误解了需求，而不是用户真想清空文档。
        // 这种情况下直接执行会让用户丢掉全部内容，代价很高，因此要求拆分确认。
        if (availableBlocks != null && !availableBlocks.isEmpty()
                && deletions == availableBlocks.size() && deletions > 1) {
            problems.add("计划将删除全部 " + deletions + " 个内容块；"
                    + "如果确实需要清空文档，请分次执行或使用删除文件");
        }

        if (!problems.isEmpty()) {
            String message = "编辑计划校验未通过：" + String.join("；", problems);
            log.warn("编辑计划校验失败, 问题数={}, 详情={}", problems.size(), message);
            throw new BusinessException(422, message);
        }
    }

    /** XLSX 允许在已有工作表的空白单元格写公式；DOCX 仍要求目标块实际存在。 */
    public void validateForContext(DocumentEditPlan plan, ToolExecutionContext context) {
        validate(plan, context.blockIds());
        for (var operation : plan.operations()) {
            if (operation.operationType() == OperationType.NORMALIZE_DOCX_FORMAT
                    || operation.operationType() == OperationType.RESIZE_DOCX_IMAGES) {
                DocxNormalizationSpec.validateContext(operation, context);
            }
            if (isXlsxOperation(operation.operationType())) {
                if (!"XLSX".equals(context.format()) || context.xlsxStructure() == null) {
                    throw new BusinessException(422, "当前文档格式不能执行 XLSX 计划");
                }
                if (operation.operationType() == OperationType.SET_FORMULA) {
                    var target = XlsxFormulaTarget.parse(operation.target());
                    if (context.xlsxStructure().sheets().stream()
                            .noneMatch(sheet -> sheet.name().equalsIgnoreCase(target.sheetName()))) {
                        throw new BusinessException(422, "公式目标工作表不属于当前 XLSX 版本");
                    }
                }
            } else if (!"DOCX".equals(context.format())) {
                throw new BusinessException(422, "当前文档格式不能执行 DOCX 计划");
            }
        }
    }

    /** 供提示词使用的支持类型清单。 */
    public static String supportedTypeNames() {
        StringBuilder builder = new StringBuilder();
        for (OperationType type : OperationType.values()) {
            if (builder.length() > 0) {
                builder.append("、");
            }
            builder.append(type.name());
        }
        return builder.toString();
    }

    private static boolean isXlsxOperation(OperationType type) {
        return type == OperationType.SET_FORMULA
                || type == OperationType.CREATE_TABLE
                || type == OperationType.CREATE_CHART;
    }

    /** 供诊断使用。 */
    public int maxOperations() {
        return MAX_OPERATIONS;
    }

    /** 供诊断使用。 */
    public boolean requireConfirmation() {
        return properties.isRequireConfirmation();
    }
}
