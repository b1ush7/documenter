package com.documenter.vo;

import java.util.List;

/**
 * 两个文档版本的差异比较结果（TODO 4.3「支持修改前后内容比较」）。
 *
 * <p>刻意采用<b>块级</b>而不是字符级比较。原因：本文档是结构化办公文档，
 * 用户关心的是「第二段被改了」「表格里删了一行」，
 * 而字符级 diff 会把这类改动渲染成一串难以阅读的增删碎片，
 * 反而看不出发生了什么。
 *
 * <p>块 ID 是块在 OOXML 正文树中的结构路径（如 {@code body/p2}、{@code body/t0/r1/c0}）。
 * 未被改动的块在两个版本里 ID 相同，因此可以直接按 ID 对齐；
 * 这依赖「结构路径稳定」这一前提，若两个版本的块结构差异很大，
 * ID 对齐会退化，此时仍能正确报出新增与删除，只是「修改」可能被拆成「删除 + 新增」。
 *
 * @param fileId     文件 ID
 * @param fromVersion 比较基准版本（旧）
 * @param toVersion   比较目标版本（新）
 * @param format      比较所用的格式（DOCX / XLSX），决定比较的粒度
 * @param changes     差异清单，按文档顺序排列
 * @param truncated   是否因超出检查上限而未比较完；true 时 changes 不是完整结论
 */
public record VersionComparisonVO(
        Long fileId,
        Integer fromVersion,
        Integer toVersion,
        String format,
        List<Change> changes,
        boolean truncated) {

    public VersionComparisonVO {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 统计某类改动数量，便于前端显示「新增 3 处、删除 1 处」。 */
    public long countOf(ChangeType type) {
        return changes.stream().filter(change -> change.type() == type).count();
    }

    /**
     * 单处差异。
     *
     * @param type    改动类型
     * @param key     定位标识。DOCX 为块 ID，XLSX 为「工作表!单元格地址」
     * @param label   面向人的位置描述，如「段落 body/p2」「工作表 Sheet1 单元格 B3」
     * @param before  改动前的内容；{@link ChangeType#ADDED} 时为 null
     * @param after   改动后的内容；{@link ChangeType#REMOVED} 时为 null
     * @param kind    块类型（PARAGRAPH / HEADING / TABLE / ROW / CELL / IMAGE / CELL），
     *                便于前端按类型显示图标
     */
    public record Change(ChangeType type, String key, String label,
                         String before, String after, String kind) {
    }

    /** 改动类型。 */
    public enum ChangeType {
        /** 新增的块或单元格。 */
        ADDED,
        /** 删除的块或单元格。 */
        REMOVED,
        /** 内容发生变化的块或单元格。 */
        MODIFIED
    }
}
