package com.documenter.service;

import com.documenter.dto.EditImageDTO;
import com.documenter.vo.VersionVO;

/** 基于明确源版本的图片编辑，结果保存为新版本。 */
public interface ImageDocumentService {
    VersionVO edit(Long userId, Long fileId, Integer sourceVersion, EditImageDTO request);
}
