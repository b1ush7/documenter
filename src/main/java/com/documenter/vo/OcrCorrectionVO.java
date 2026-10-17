package com.documenter.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 保存后的 OCR 逐页校正文本。 */
public record OcrCorrectionVO(Long fileId, Integer versionNo, String fingerprint,
                              List<Page> pages, LocalDateTime updatedAt) {

    public OcrCorrectionVO {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public record Page(Integer pageNo, String text, List<Table> tables) {

        public Page {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record Table(int tableIndex, int rows, int columns, List<Cell> cells) {

        public Table {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Cell(int row, int column, int rowSpan, int columnSpan, String text) {
    }
}
