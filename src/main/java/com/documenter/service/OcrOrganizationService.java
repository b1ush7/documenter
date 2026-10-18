package com.documenter.service;

import com.documenter.dto.OcrOrganizeRequestDTO;
import com.documenter.vo.AgentRunResult;

/** 将已人工校正的 OCR 内容交给现有文本模型做只读整理。 */
public interface OcrOrganizationService {

    AgentRunResult organize(Long userId, Long fileId, Integer versionNo,
                            String fingerprint, OcrOrganizeRequestDTO request);
}
