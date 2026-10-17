package com.documenter.vo;

import java.util.List;

/** DOCX 每个分节的页眉页脚链接状态。第一节没有上一节，全部链接状态为 false。 */
public record DocxHeaderFooterLinksVO(Long fileId, Integer versionNo, int totalSections,
                                      List<Section> sections) {

    public DocxHeaderFooterLinksVO {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public record Section(int sectionNo, boolean defaultHeaderLinked,
                          boolean firstHeaderLinked, boolean evenHeaderLinked,
                          boolean defaultFooterLinked, boolean firstFooterLinked,
                          boolean evenFooterLinked) { }
}
