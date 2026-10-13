package com.documenter.vo;

import java.util.List;

/** PDF 原生文本和按页类型检测结果；扫描页只标记，不在此处执行 OCR。 */
public record PdfContentVO(Long fileId, Integer versionNo, String documentType,
                           int totalPages, int textPages, int scannedPages,
                           int mixedPages, int emptyPages, int extractedTextChars,
                           boolean textTruncated, List<Page> pages) {

    public PdfContentVO {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** type 为 TEXT / SCANNED / MIXED / EMPTY。 */
    public record Page(int pageNo, String type, String text, int textCharCount,
                       int imageCount, boolean textTruncated) {
    }
}
