package com.documenter.service;

import com.documenter.vo.PdfContentVO;

/** PDF 原生文本提取与页面类型检测。 */
public interface PdfDocumentService {

    /** 读取一个明确 PDF 版本；扫描页仅标记为需要 OCR。 */
    PdfContentVO readContent(Long userId, Long fileId, Integer versionNo);
}
