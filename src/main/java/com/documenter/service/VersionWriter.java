package com.documenter.service;

import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;

/**
 * 新增文档版本（TODO 3.3「编辑产物新增版本，记录来源版本、操作者、指令和结果」）。
 *
 * <p>版本号由 file_asset.latest_version 原子递增得到，因此并发编辑不会产生重复版本。
 */
public interface VersionWriter {

    /**
     * 为文件追加一个新版本。
     *
     * @param fileId      文件 ID
     * @param userId      操作者
     * @param content     新版本内容的位置与摘要
     * @param changeType  变更类型
     * @param instruction 产生该版本的自然语言指令，可为 null
     * @param summary     结果说明，可为 null
     * @param expectVersion 期望的当前版本号，用于并发冲突检测；传 null 表示不检查
     * @return 新建的版本记录
     * @throws com.documenter.exception.BusinessException 期望版本与当前版本不一致时抛出冲突
     */
    DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                  ChangeType changeType, String instruction, String summary,
                                  Integer expectVersion);

    /**
     * 新版本内容的位置与摘要。
     *
     * @param storageKey 存储标识
     * @param sizeBytes  字节数
     * @param sha256     内容摘要
     */
    record VersionContent(String storageKey, long sizeBytes, String sha256) {
    }
}
