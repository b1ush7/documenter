package com.documenter.vo;

import java.util.List;

/** DOCX 中被正文引用且可安全识别的脚注。 */
public record DocxFootnotesVO(Long fileId, Integer versionNo, List<Footnote> footnotes) {

    public DocxFootnotesVO {
        footnotes = footnotes == null ? List.of() : List.copyOf(footnotes);
    }

    public record Footnote(int id, String text, List<String> referencedBy) {
        public Footnote {
            referencedBy = referencedBy == null ? List.of() : List.copyOf(referencedBy);
        }
    }
}
