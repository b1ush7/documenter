package com.documenter.service;

import com.documenter.dto.SaveOcrCorrectionsDTO;
import com.documenter.vo.OcrCorrectionVO;

/** OCR 人工校正文本持久化。 */
public interface OcrCorrectionService {

    OcrCorrectionVO save(Long userId, Long fileId, Integer versionNo,
                         SaveOcrCorrectionsDTO request);

    OcrCorrectionVO get(Long userId, Long fileId, Integer versionNo, String fingerprint);
}
