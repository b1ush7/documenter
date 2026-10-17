package com.documenter.service;

import java.util.List;

/** 可替换的云端 OCR 引擎适配层。 */
public interface OcrEngine {

    boolean available();

    String name();

    Result recognize(byte[] pngBytes, String language);

    record Result(List<Line> lines, boolean truncated) {
        public Result {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record Line(String text, int left, int top, int width, int height,
                double confidence, boolean needsReview) {
    }
}
