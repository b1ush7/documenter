package com.documenter.service;

import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.vo.VersionVO;

/** 将一个 PDF 版本的正文和基础字体布局重建为可继续编辑的 DOCX。 */
public interface PdfToDocxService {

    /**
     * 转换明确 PDF 版本并追加一个 DOCX 版本。
     *
     * <p>源版本同时作为并发期望版本。托管任务传入 taskId/attemptNo 以复用任务级版本幂等；
     * 同步接口两个参数传 null。
     */
    VersionVO convert(Long userId, Long fileId, Integer sourceVersion,
                      ConvertPdfToDocxDTO request, Long taskId, Integer attemptNo);
}
