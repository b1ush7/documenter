package com.documenter.vo;

/** DOCX 中检测到的第一个 TOC 域。 */
public record DocxTableOfContentsVO(Long fileId, Integer versionNo, boolean present,
                                    String blockId, String instruction,
                                    boolean updateFieldsOnOpen) { }
