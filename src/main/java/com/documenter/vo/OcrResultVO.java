package com.documenter.vo;

import java.util.List;

/** PDF 或图片 OCR 的统一结果。 */
public record OcrResultVO(Long fileId, Integer versionNo, String engine, String language,
                          int totalPages, int renderedPages, boolean truncated,
                          List<Page> pages, String fingerprint) {

    public OcrResultVO {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** source 为 NATIVE / OCR / EMPTY。 */
    public record Page(int pageNo, String source, int width, int height, String text,
                       Double confidence, boolean needsReview, Region region,
                       List<Paragraph> paragraphs, List<Table> tables,
                       List<Line> lines, boolean truncated) {

        public Page {
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            tables = tables == null ? List.of() : List.copyOf(tables);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** 服务端按纵向间距对 OCR 行做启发式分组，不表示已恢复完整表格结构。 */
    public record Paragraph(String text, int left, int top, int width, int height,
                            double confidence, boolean needsReview, List<Line> lines) {

        public Paragraph {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** OCR 行坐标单位为对应渲染图或原始图片的像素，confidence 为 0～100。 */
    public record Line(String text, int left, int top, int width, int height,
                       double confidence, boolean needsReview) {
    }

    /** 本次识别区域；坐标相对完整页面或原图。 */
    public record Region(int x, int y, int width, int height) {
    }

    /** 行列下标从 0 开始，合并单元格通过 rowSpan/columnSpan 表示。 */
    public record Table(int rows, int columns, double confidence,
                        boolean needsReview, List<Cell> cells) {
        public Table {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Cell(int row, int column, int rowSpan, int columnSpan,
                       String text, int left, int top, int width, int height,
                       double confidence, boolean needsReview) {
    }
}
