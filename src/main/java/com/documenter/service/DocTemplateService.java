package com.documenter.service;

import com.documenter.dto.DocTemplateQueryDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.dto.TemplateVersionQueryDTO;
import com.documenter.vo.DocTemplateVO;
import com.documenter.vo.DocTemplateVersionVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共模板管理（TODO 3.2「管理公共模板」）。
 *
 * <p>模板是所有用户共享的资源，因此只有管理员能写入；
 * 普通用户只应读取「已启用」的模板列表。
 */
public interface DocTemplateService {

    /** 管理员查询全部模板（含停用）。 */
    PageResult<DocTemplateVO> listForAdmin(DocTemplateQueryDTO query);

    /**
     * 查询已启用且有明确内容版本的 DOCX 模板，供普通用户生成文档。
     *
     * <p>返回可渲染变量表单的元数据，不暴露存储标识。
     */
    java.util.List<DocTemplateVO> listEnabled();

    DocTemplateVO detail(Long id);

    /** 新建模板；文件必传，否则模板没有实际内容。 */
    DocTemplateVO create(SaveDocTemplateDTO dto, MultipartFile file);

    /** 完整替换元数据（标识、名称、分类、说明、变量定义），可选字段为空时清空。 */
    DocTemplateVO update(Long id, SaveDocTemplateDTO dto);

    /**
     * 替换模板内容，版本号 +1。
     *
     * <p>同一事务写入历史索引；基于读取到的版本执行乐观锁更新。
     */
    DocTemplateVO replaceContent(Long id, MultipartFile file);

    DocTemplateVO updateEnabled(Long id, boolean enabled);

    /** 删除模板记录；磁盘文件暂时保留，正式存储回收策略尚未实现。 */
    void delete(Long id);

    /** 下载模板文件，供管理员核对内容。 */
    FileDownload download(Long id);

    PageResult<DocTemplateVersionVO> listVersions(Long id, TemplateVersionQueryDTO query);

    FileDownload downloadVersion(Long id, Integer versionNo);

    /** Appends a new content version pointing to the selected historical file; never restores metadata. */
    DocTemplateVO restoreVersion(Long id, Integer versionNo, Integer expectVersion);
}
