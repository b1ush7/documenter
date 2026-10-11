package com.documenter.vo;

import java.util.List;

/**
 * 一个明确 DOCX 版本的结构化只读表示。
 *
 * <p>块 ID 是块在该版本 OOXML 正文树中的结构路径。它在重复读取同一版本时保持稳定，
 * 同时可直接映射回正文、表格行、单元格和段落的位置。跨版本编辑时必须重新读取结构，
 * 不能把旧版本块 ID 直接用于新版本。
 */
public record DocxStructureVO(Long fileId, Integer versionNo, List<Block> blocks) {

    public DocxStructureVO {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * @param blockId      当前版本内的结构路径
     * @param type         PARAGRAPH / HEADING / TABLE / ROW / CELL / IMAGE
     * @param text         段落或单元格可见文本；容器块和图片为 null
     * @param styleId      DOCX 原始段落样式 ID；非段落块为 null
     * @param headingLevel 能可靠识别时返回 1-9，否则为 null
     * @param name         图片在 DOCX 包中的文件名；非图片块为 null
     * @param contentType  图片 MIME 类型；非图片块为 null
     * @param sizeBytes    图片原始字节数；非图片块为 null
     * @param children     按原文档顺序排列的子块
     */
    public record Block(String blockId, String type, String text, String styleId,
                        Integer headingLevel, String name, String contentType,
                        Long sizeBytes, List<Block> children) {

        public Block {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
