package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.service.AiTool;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocxStructureVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 读取文档结构（TODO 4.2「封装受控工具：读取文档」）。
 *
 * <p>这个工具<b>不重新读取</b>文档，只把上下文中已缓存的结构返回给模型。
 * 原因有三：
 * <ol>
 *   <li>块 ID 一致性：若每次调用都重读，期间文档被别的操作改动会导致
 *       模型看到的块 ID 与后续编辑时校验用的集合不一致；</li>
 *   <li>效率：一次对话里模型可能多次查看文档，重复解析没有意义；</li>
 *   <li>权限：读取已经在进入对话时完成并校验过归属，工具层不需要也不应该
 *       再走一遍鉴权 —— 少一个入口就少一处遗漏的可能。</li>
 * </ol>
 *
 * <p>返回内容会被截断：长文档的结构可能远超上下文预算，
 * 而一次塞满上下文会让后续的工具调用全部失败。
 */
@Component
public class ReadDocumentTool extends AbstractAiTool {

    public static final String NAME = "read_document";

    private final AiModelProperties properties;

    public ReadDocumentTool(AiModelProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "分页读取当前文档结构，返回可编辑块 ID。支持 offset、limit、query 和 mode。"
                + "长文档必须按 nextOffset 继续读取；mode=outline 可浏览标题及疑似未标记的编号标题。"
                + "块 ID 用于后续编辑操作的 target 参数。"
                + "开始任何修改前必须先调用它，否则无法知道合法的目标块 ID。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.optional("offset", "从第几条结果开始，默认 0；续读使用 nextOffset"),
                ToolParameter.optional("limit", "最多返回条数，1 到 500，默认 100；还受字符预算限制"),
                ToolParameter.optional("query", "按块 ID 或文字筛选，续读时保持不变"),
                ToolParameter.optional("mode", "blocks（默认）或 outline（标题及疑似编号标题）"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String outline = describe(context, normalize(arguments));
        return ToolResult.of(outline);
    }

    private String describe(ToolExecutionContext context, Map<String, String> args) {
        StringBuilder builder = new StringBuilder();
        builder.append("文档格式：").append(context.format())
                .append("，当前版本：v").append(context.versionNo()).append('\n');

        if (context.docxStructure() != null) {
            appendDocx(builder, context.docxStructure().blocks(), 0);
        } else if (context.xlsxStructure() != null) {
            appendXlsx(builder, context);
        } else {
            builder.append("（该版本没有可读取的结构信息）");
        }

        Integer requestedOffset = optionalInt(args, "offset");
        Integer requestedLimit = optionalInt(args, "limit");
        int offset = requestedOffset == null ? 0 : requestedOffset;
        int limit = requestedLimit == null ? 100 : requestedLimit;
        String mode = optionalString(args, "mode");
        String query = optionalString(args, "query");
        if (offset < 0 || limit < 1 || limit > 500
                || (mode != null && !List.of("blocks", "outline").contains(mode))) {
            throw new com.documenter.exception.BusinessException(422, "offset 必须非负，limit 必须为 1 到 500，mode 必须为 blocks 或 outline");
        }
        List<String> lines = builder.toString().lines().skip(1)
                .filter(line -> query == null || line.contains(query))
                .filter(line -> !"outline".equals(mode) || line.contains("标题")
                        || line.matches(".*: (?:[一二三四五六七八九十百]+[、．.].*|[0-9]+(?:\\.[0-9]+)+\\s*.*)"))
                .toList();
        if (offset > lines.size()) throw new com.documenter.exception.BusinessException(422, "offset 超出结果数量");
        // 预留分页元数据空间，避免外层工具结果限制再截掉 nextOffset。
        int budget = properties.getMaxDocumentChars() > 0 ? properties.getMaxDocumentChars() : 12000;
        if (properties.getMaxToolResultChars() > 0) budget = Math.min(budget, properties.getMaxToolResultChars());
        StringBuilder page = new StringBuilder();
        int next = offset;
        while (next < lines.size() && next - offset < limit) {
            String line = lines.get(next);
            if (page.length() + line.length() + 1 > budget - 300) break;
            page.append(line).append('\n');
            next++;
        }
        if (next == offset && next < lines.size()) {
            throw new com.documenter.exception.BusinessException(422, "读取字符预算不足以容纳一个完整块，请提高文档及工具结果字符预算");
        }
        return "文档格式：" + context.format() + "，当前版本：v" + context.versionNo()
                + "，total=" + lines.size() + "，offset=" + offset + "，returned=" + (next - offset)
                + "，hasMore=" + (next < lines.size()) + "，nextOffset=" + (next < lines.size() ? next : "null")
                + (context.xlsxStructure() != null && context.xlsxStructure().truncated()
                    ? "，sourceTruncated=true（工作簿结构读取已达单元格上限，分页结束不代表检查了整张表）" : "")
                + "\n" + page;
    }

    private void appendDocx(StringBuilder builder, List<DocxStructureVO.Block> blocks, int depth) {
        if (blocks == null) {
            return;
        }
        for (DocxStructureVO.Block block : blocks) {
            String type = block.type();
            if ("HEADER".equals(type) || "FOOTER".equals(type)) continue;
            if ("PARAGRAPH".equals(type) || "HEADING".equals(type) || "CELL".equals(type)) {
                builder.append("  ".repeat(Math.min(depth, 4)))
                        .append('[').append(block.blockId()).append("] ")
                        .append(typeLabel(type));
                if (block.headingLevel() != null) {
                    builder.append("(H").append(block.headingLevel()).append(')');
                }
                if (block.styleId() != null) {
                    builder.append("(style=").append(block.styleId()).append(')');
                }
                if (block.numberingFormat() != null) {
                    builder.append("(list=").append(block.numberingFormat()).append(')');
                }
                if (block.pageBreakBefore() != null) {
                    builder.append("(pageBreakBefore=").append(block.pageBreakBefore()).append(')');
                }
                builder.append(": ")
                        .append(block.text() == null ? "（空）" : abbreviate(block.text()))
                        .append('\n');
            } else if ("IMAGE".equals(type)) {
                builder.append("  ".repeat(Math.min(depth, 4)))
                        .append('[').append(block.blockId()).append("] 图片: ")
                        .append(block.name() == null ? "未命名" : block.name()).append('\n');
            }
            if (block.children() != null && !block.children().isEmpty()) {
                appendDocx(builder, block.children(), depth + 1);
            }
        }
    }

    private void appendXlsx(StringBuilder builder, ToolExecutionContext context) {
        for (var sheet : context.xlsxStructure().sheets()) {
            builder.append("工作表 [").append(sheet.name()).append("]");
            if (sheet.firstRow() != null) {
                builder.append(" 行范围 ").append(sheet.firstRow()).append('-').append(sheet.lastRow());
            }
            builder.append('\n');
            if (sheet.cells() == null) {
                continue;
            }
            for (var cell : sheet.cells()) {
                String value = cell.formula() != null && !cell.formula().isBlank()
                        ? "=" + cell.formula() : cell.value();
                if (value == null || value.isBlank()) {
                    continue;
                }
                builder.append("  [").append(sheet.name()).append('!').append(cell.address())
                        .append("] ").append(abbreviate(value)).append('\n');
            }
        }
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "HEADING" -> "标题";
            case "CELL" -> "单元格";
            default -> "段落";
        };
    }

    private static String abbreviate(String text) {
        String single = text.replace('\n', ' ').trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    /** 该工具只读，不产生新版本。 */
    @Override
    public boolean mutating() {
        return false;
    }
}
