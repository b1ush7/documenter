package com.documenter.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 保存后的 OCR 逐页校正文本。 */
public record OcrCorrectionVO(Long fileId, Integer versionNo, String fingerprint,
                              List<Page> pages, LocalDateTime updatedAt) {

    public OcrCorrectionVO {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public record Page(Integer pageNo, String text) {
    }
}
