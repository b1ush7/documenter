package com.documenter.component;

import com.documenter.configuration.AiModelProperties;
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
        int deletions = 0;

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

            if (operation.target() == null || operation.target().isBlank()) {
                problems.add(position + "缺少目标块标识");
            } else {
                if (availableBlocks != null && !availableBlocks.contains(operation.target())) {
                    problems.add(position + "的目标「" + operation.target() + "」在该版本中不存在");
                }
                // 同一目标被多次修改时，后一次会覆盖前一次，用户的预期难以预测
                if (!touchedTargets.add(operation.target())) {
                    problems.add(position + "重复修改同一个目标「" + operation.target() + "」；"
                            + "请合并为一次操作");
                }
            }

            if (type.requiresContent()) {
                if (operation.content() == null || operation.content().isBlank()) {
                    problems.add(position + "（" + type + "）缺少内容");
                } else if (operation.content().length() > MAX_CONTENT_CHARS) {
                    problems.add(position + "的内容长度 " + operation.content().length()
                            + " 超过上限 " + MAX_CONTENT_CHARS);
                }
            }

            if (type == OperationType.DELETE) {
                deletions++;
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

    /** 供诊断使用。 */
    public int maxOperations() {
        return MAX_OPERATIONS;
    }

    /** 供诊断使用。 */
    public boolean requireConfirmation() {
        return properties.isRequireConfirmation();
    }
}
