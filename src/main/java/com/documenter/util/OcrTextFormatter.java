package com.documenter.util;

import com.documenter.vo.OcrResultVO;

import java.util.stream.Collectors;

/** 将多页 OCR 结果导出为 UTF-8 纯文本，页间使用换页符。 */
public final class OcrTextFormatter {

    private OcrTextFormatter() {
    }

    public static String format(OcrResultVO result) {
        if (result == null || result.pages().isEmpty()) return "";
        return result.pages().stream()
                .map(page -> page.text() == null ? "" : page.text().strip())
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n\f\n\n"));
    }
}
