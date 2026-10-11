package com.documenter.service;

import com.documenter.vo.VersionVO;

import java.util.List;

/**
 * 文档版本查询与恢复（TODO 3.3「支持恢复历史版本」）。
 */
public interface DocumentVersionService {

    /** 按版本号倒序列出某文件的全部版本。仅允许访问自己拥有的文件。 */
    List<VersionVO> listVersions(Long userId, Long fileId);

    /** 查询指定版本。 */
    VersionVO getVersion(Long userId, Long fileId, Integer versionNo);

    /**
     * 恢复到指定历史版本。
     *
     * <p>不覆盖、不删除任何历史版本，而是复制目标版本的内容生成一个<b>新版本</b>，
     * 变更类型为 RESTORE，parentVersion 指向被恢复的版本。
     * 这样「恢复」本身也可被再次撤销，历史链条不会断裂。
     */
    VersionVO restore(Long userId, Long fileId, Integer versionNo, Integer expectVersion);

    /**
     * 下载指定版本的内容。
     *
     * <p>与 {@code FileService.download} 的区别：这里定位的是某个具体版本，
     * 用于保证「预览关联明确的文档版本；下载必须来自同一版本」。
     */
    com.documenter.vo.FileDownload downloadVersion(Long userId, Long fileId, Integer versionNo);
}
