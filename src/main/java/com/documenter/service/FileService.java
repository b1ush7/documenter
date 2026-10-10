package com.documenter.service;

import com.documenter.dto.FileQueryDTO;
import com.documenter.entity.FileAsset;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件业务（TODO 3.3）。
 *
 * <p>所有方法都以 {@code userId} 作为归属判定的唯一依据。
 * 调用方必须传入当前登录用户 ID（{@code SecurityUtil.requireUserId()}），
 * 绝不允许使用前端传入的 ID，否则等于没有鉴权（TODO 3.2）。
 */
public interface FileService {

    /** 上传文件。存储前校验真实类型与大小，落库后返回元数据。 */
    FileAssetVO upload(Long userId, MultipartFile file);

    /** 保存服务端本地生成的 DOCX，并建立版本 1。 */
    FileAssetVO createGeneratedDocx(Long userId, String displayName, byte[] content,
                                    String instruction);

    /** 分页查询当前用户的文件。 */
    PageResult<FileAssetVO> list(Long userId, FileQueryDTO query);

    /** 查询文件详情，非本人文件按「不存在」处理，避免泄露是否存在。 */
    FileAssetVO detail(Long userId, Long fileId);

    /** 重命名展示名。 */
    FileAssetVO rename(Long userId, Long fileId, String displayName);

    /**
     * 删除文件（软删除 + 释放磁盘文件）。
     *
     * <p>TODO 3.3 明确要求「明确删除时如何处理历史版本和衍生文件」：当前策略是
     * 软删除 file_asset 记录、保留磁盘文件，以便历史版本仍可追溯；
     * 物理清理交给后续的过期清理任务统一处理。
     */
    void delete(Long userId, Long fileId);

    /** 下载，返回与元数据一致的载荷。 */
    FileDownload download(Long userId, Long fileId);

    /**
     * 取当前用户拥有的文件实体，供其它模块（版本、任务）做归属校验复用。
     *
     * @throws com.documenter.exception.BusinessException 不存在或不属于该用户
     */
    FileAsset requireOwned(Long userId, Long fileId);

    /** 当前用户已用存储字节数，用于额度展示。 */
    long usedBytes(Long userId);
}
