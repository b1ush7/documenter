package com.documenter.vo;

import java.util.List;

/** PDF 或图片 OCR 的统一结果。 */
public record OcrResultVO(Long fileId, Integer versionNo, String engine, String language,
                          int totalPages, int renderedPages, boolean truncated,
                          List<Page> pages) {

    public OcrResultVO {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** source 为 NATIVE / OCR / EMPTY。 */
    public record Page(int pageNo, String source, int width, int height, String text,
                       Double confidence, boolean needsReview, List<Line> lines,
                       boolean truncated) {

        public Page {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** OCR 行坐标单位为对应渲染图或原始图片的像素，confidence 为 0～100。 */
    public record Line(String text, int left, int top, int width, int height,
                       double confidence, boolean needsReview) {
    }
}
