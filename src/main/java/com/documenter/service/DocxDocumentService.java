package com.documenter.service;

import com.documenter.vo.DocxStructureVO;

/** DOCX 版本的结构化读取能力。 */
public interface DocxDocumentService {

    /**
     * 读取用户拥有的一个明确版本。
     *
     * @throws com.documenter.exception.BusinessException 文件不是 DOCX、版本不存在或内容损坏时抛出
     */
    DocxStructureVO readStructure(Long userId, Long fileId, Integer versionNo);
}
