package com.documenter.service;

import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;

/** PDF 原生文本提取与页面类型检测。 */
public interface PdfDocumentService {

    /** 读取一个明确 PDF 版本；扫描页仅标记为需要 OCR。 */
    PdfContentVO readContent(Long userId, Long fileId, Integer versionNo);

    /** 将指定页渲染为受像素上限约束的 PNG 预览。 */
    PdfPagePreview renderPage(Long userId, Long fileId, Integer versionNo,
                              Integer pageNo, Integer dpi);
}
