package com.documenter.service;

import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxFootnotesDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.dto.CreateDocxTableOfContentsDTO;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.dto.GenerateDocxDTO;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.dto.UpdateDocxHeaderFooterLinkDTO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.DocxHeaderFooterLinksVO;
import com.documenter.vo.DocxFootnotesVO;
import com.documenter.vo.DocxTableOfContentsVO;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.VersionVO;

/** DOCX 版本的结构化读取能力。 */
public interface DocxDocumentService {

    /** 根据受限结构块或启用的 DOCX 模板在本地生成新文件。 */
    FileAssetVO generate(Long userId, GenerateDocxDTO request);

    /**
     * 读取用户拥有的明确版本，包括正文及页眉页脚部件。
     *
     * @throws com.documenter.exception.BusinessException 文件不是 DOCX、版本不存在或内容损坏时抛出
     */
    DocxStructureVO readStructure(Long userId, Long fileId, Integer versionNo);

    /**
     * 替换指定源版本中正文或页眉页脚的普通段落文本，并保存为新版本。
     *
     * <p>源版本同时作为乐观锁：如果文件已有更新版本，本次编辑会以 409 拒绝。
     */
    VersionVO replaceParagraphText(Long userId, Long fileId, Integer sourceVersion,
                                   ReplaceDocxTextDTO request);

    /** 在明确源版本中插入或删除段落，并保存为新版本。 */
    VersionVO editParagraphs(Long userId, Long fileId, Integer sourceVersion,
                             EditDocxParagraphsDTO request);

    /** 修改单元格文本、增删行，或设置行高、列宽和重复标题行。 */
    VersionVO editTable(Long userId, Long fileId, Integer sourceVersion,
                        EditDocxTableDTO request);

    /** 在同一份源文档中执行已校验的段落和单元格计划，只保存一个新版本。 */
    VersionVO editPlan(Long userId, Long fileId, Integer sourceVersion,
                       DocumentEditPlan plan, String instruction);

    /** 设置标题、普通段落、列表格式或正文段前分页，并保存为新版本。 */
    VersionVO formatParagraphs(Long userId, Long fileId, Integer sourceVersion,
                               FormatDocxParagraphsDTO request);

    /** 将已有 PNG/JPEG 文件版本以内嵌方式追加到段落，并保存为新版本。 */
    VersionVO insertImages(Long userId, Long fileId, Integer sourceVersion,
                           InsertDocxImagesDTO request);

    /** 设置指定分节的页眉或页脚是否链接到上一节。 */
    VersionVO updateHeaderFooterLink(Long userId, Long fileId, Integer sourceVersion,
                                     UpdateDocxHeaderFooterLinkDTO request);

    /** 查询明确 DOCX 版本各分节的页眉页脚链接状态。 */
    DocxHeaderFooterLinksVO readHeaderFooterLinks(Long userId, Long fileId, Integer versionNo);

    /** 查询被正文引用的脚注及其引用段落。 */
    DocxFootnotesVO readFootnotes(Long userId, Long fileId, Integer versionNo);

    /** 新增、修改或删除脚注，并保存为一个新版本。 */
    VersionVO editFootnotes(Long userId, Long fileId, Integer sourceVersion,
                            EditDocxFootnotesDTO request);

    /** 查询 DOCX 中已有的 TOC 域。 */
    DocxTableOfContentsVO readTableOfContents(Long userId, Long fileId, Integer versionNo);

    /** 插入 TOC 域，或把已有 TOC 请求在下一次打开时更新。 */
    VersionVO createOrRefreshTableOfContents(Long userId, Long fileId, Integer sourceVersion,
                                             CreateDocxTableOfContentsDTO request);
}
