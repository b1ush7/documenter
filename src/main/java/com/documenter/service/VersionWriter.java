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
     * 带任务幂等标识地追加版本（TODO 3.4）。
     *
     * <p>解决的问题：任务失败后重试会重新调用处理器，而处理器里的
     * {@code appendVersion} 每次都会分配新版本号，于是一次
     * 「已经产出版本、但在后续步骤失败」的执行会产出内容相同的重复版本。
     *
     * <p>本方法的契约：
     * <ul>
     *   <li>{@code (taskId, attemptNo)} 已存在版本时<b>直接返回已有版本</b>，
     *       不分配新版本号、不重复落盘；</li>
     *   <li>{@code taskId} 为 null 时退化为普通追加（上传、手工编辑等非任务产出）。</li>
     * </ul>
     *
     * <p>并发保护是双层的：应用层先查一次，减少无谓的版本号分配；
     * 最终由唯一索引 {@code uk_document_version_task_attempt} 兜底——
     * 即使「先查后插」在并发下失效，数据库也会拒绝第二条记录。
     *
     * <p>为什么用 {@code (taskId, attemptNo)} 而不是只用 taskId：
     * 用户显式重试应当允许产出新版本（那是新的执行意图），
     * 但同一次尝试内部的失败重入、超时重入不应重复产出。
     *
     * @param taskId    产出该版本的任务 ID；可为 null
     * @param attemptNo 任务内的尝试序号，从 1 开始；taskId 非 null 时必须为正数
     */
    DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                  ChangeType changeType, String instruction, String summary,
                                  Integer expectVersion, Long taskId, Integer attemptNo);

    /**
     * 该任务是否已经产出过版本（任意尝试）。
     *
     * <p>供需要在产出前后做副作用的调用方使用，典型场景是扣减额度：
     * 处理器应在扣减前检查本方法，避免重试导致重复扣减。
     * 当前尚未确定是否接入余额计费（见 TODO 第 9 节），
     * 因此这里只提供判断依据，不替调用方做计费决策。
     */
    boolean hasProducedVersion(Long taskId);

    /**
     * 新版本内容的位置与摘要。
     *
     * @param storageKey 存储标识
     * @param sizeBytes  字节数
     * @param sha256     内容摘要
     * @param contentType 该版本 MIME 类型；与 extension 同时为 null 时沿用当前格式
     * @param extension  该版本规范化扩展名，不带点
     */
    record VersionContent(String storageKey, long sizeBytes, String sha256,
                          String contentType, String extension) {
        /** 同格式编辑沿用文件当前格式，兼容现有 DOCX/XLSX 调用。 */
        public VersionContent(String storageKey, long sizeBytes, String sha256) {
            this(storageKey, sizeBytes, sha256, null, null);
        }
    }
}
