package com.documenter.service;

import com.documenter.dto.OcrRequestDTO;
import com.documenter.vo.OcrResultVO;

/** 对明确 PDF 或图片版本执行受控 OCR。 */
public interface OcrService {

    OcrResultVO recognize(Long userId, Long fileId, Integer versionNo, OcrRequestDTO request);
}
