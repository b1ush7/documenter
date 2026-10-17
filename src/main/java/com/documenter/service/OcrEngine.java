package com.documenter.service;

import java.util.List;

/** 可替换的云端 OCR 引擎适配层。 */
public interface OcrEngine {

    boolean available();

    String name();

    Result recognize(byte[] pngBytes, String language);

    record Result(List<Line> lines, List<Table> tables, boolean truncated) {
        public Result {
            lines = lines == null ? List.of() : List.copyOf(lines);
            tables = tables == null ? List.of() : List.copyOf(tables);
        }

        public Result(List<Line> lines, boolean truncated) {
            this(lines, List.of(), truncated);
        }
    }

    record Line(String text, int left, int top, int width, int height,
                double confidence, boolean needsReview) {
    }

    record Table(int rows, int columns, double confidence, boolean needsReview,
                 List<Cell> cells) {
        public Table {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    record Cell(int row, int column, int rowSpan, int columnSpan,
                String text, int left, int top, int width, int height,
                double confidence, boolean needsReview) {
    }
}
