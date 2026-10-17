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
        return "读取当前文档的结构，返回可编辑的块清单（段落、标题、表格单元格）。"
                + "块 ID 用于后续编辑操作的 target 参数。"
                + "开始任何修改前必须先调用它，否则无法知道合法的目标块 ID。";
    }

    @Override
    public List<ToolParameter> parameters() {
        // 无需参数：要读的文档与版本由上下文决定，不由模型指定。
        // 这是刻意的 —— 若让模型传 fileId，它可以尝试读取别的文件
        return List.of();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String outline = describe(context);
        return ToolResult.of(outline);
    }

    private String describe(ToolExecutionContext context) {
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

        String text = builder.toString();
        int limit = properties.getMaxDocumentChars();
        if (text.length() > limit) {
            // 明确告知被截断，否则模型会以为自己看到了完整文档，
            // 进而基于缺失的信息做判断
            return text.substring(0, limit)
                    + "\n…（内容过长已截断，共 " + text.length() + " 字符。"
                    + "如需查看某部分，请缩小范围后重新读取）";
        }
        return text;
    }

    private void appendDocx(StringBuilder builder, List<DocxStructureVO.Block> blocks, int depth) {
        if (blocks == null) {
            return;
        }
        for (DocxStructureVO.Block block : blocks) {
            String type = block.type();
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
