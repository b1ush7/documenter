package com.documenter.service;

import com.documenter.dto.DocTemplateQueryDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.vo.DocTemplateVO;
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
     * 查询已启用的模板，供普通用户选择。
     *
     * <p>刻意不返回值可能为空的字段，也不暴露存储标识。
     */
    java.util.List<DocTemplateVO> listEnabled();

    DocTemplateVO detail(Long id);

    /** 新建模板；文件必传，否则模板没有实际内容。 */
    DocTemplateVO create(SaveDocTemplateDTO dto, MultipartFile file);

    /** 更新元数据（名称、分类、说明、变量定义）。 */
    DocTemplateVO update(Long id, SaveDocTemplateDTO dto);

    /**
     * 替换模板内容，版本号 +1。
     *
     * <p>不覆盖历史版本内容（旧 storageKey 仍保留在磁盘上），
     * 便于出问题时回退；清理交给过期文件清理任务。
     */
    DocTemplateVO replaceContent(Long id, MultipartFile file);

    DocTemplateVO updateEnabled(Long id, boolean enabled);

    /** 删除模板记录；磁盘文件保留，由清理任务回收。 */
    void delete(Long id);

    /** 下载模板文件，供管理员核对内容。 */
    FileDownload download(Long id);
}
