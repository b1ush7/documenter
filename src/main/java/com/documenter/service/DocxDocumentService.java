package com.documenter.service;

import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.VersionVO;

/** DOCX 版本的结构化读取能力。 */
public interface DocxDocumentService {

    /**
     * 读取用户拥有的一个明确版本。
     *
     * @throws com.documenter.exception.BusinessException 文件不是 DOCX、版本不存在或内容损坏时抛出
     */
    DocxStructureVO readStructure(Long userId, Long fileId, Integer versionNo);

    /**
     * 替换指定源版本中的段落文本，并保存为新版本。
     *
     * <p>源版本同时作为乐观锁：如果文件已有更新版本，本次编辑会以 409 拒绝。
     */
    VersionVO replaceParagraphText(Long userId, Long fileId, Integer sourceVersion,
                                   ReplaceDocxTextDTO request);
}
