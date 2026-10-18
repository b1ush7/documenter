package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

/**
 * 文档版本实现（TODO 3.3）。
 *
 * <p>核心不变式：
 * <ul>
 *   <li>版本号从 1 开始，且由 file_asset.latest_version 原子递增分配，并发安全；</li>
 *   <li>任何变更都新增记录，绝不覆盖或删除历史版本，因此「恢复」也可被再次撤销；</li>
 *   <li>版本内容与 file_asset 的记录分开存储，同一份内容可以被多个版本引用。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentVersionServiceImpl implements DocumentVersionService, VersionWriter {

    private static final String STATUS_READY = "READY";
    private static final String STATUS_DELETED = "DELETED";

    private final DocumentVersionMapper versionMapper;
    private final FileAssetMapper fileAssetMapper;
    private final FileService fileService;
    private final FileStorage fileStorage;

    public DocumentVersionServiceImpl(DocumentVersionMapper versionMapper,
                                      FileAssetMapper fileAssetMapper,
                                      FileService fileService,
                                      FileStorage fileStorage) {
        this.versionMapper = versionMapper;
        this.fileAssetMapper = fileAssetMapper;
        this.fileService = fileService;
        this.fileStorage = fileStorage;
    }

    // ------------------------------------------------------------------ 查询

    @Override
    public List<VersionVO> listVersions(Long userId, Long fileId) {
        FileAsset asset = fileService.requireOwned(userId, fileId);
        return versionMapper.selectList(Wrappers.<DocumentVersion>lambdaQuery()
                        .eq(DocumentVersion::getFileId, asset.getId())
                        .orderByDesc(DocumentVersion::getVersionNo))
                .stream()
                .map(VersionVO::from)
                .toList();
    }

    @Override
    public VersionVO getVersion(Long userId, Long fileId, Integer versionNo) {
        FileAsset asset = fileService.requireOwned(userId, fileId);
        return VersionVO.from(requireVersion(asset.getId(), versionNo));
    }

    @Override
    public FileDownload downloadVersion(Long userId, Long fileId, Integer versionNo) {
        FileAsset asset = fileService.requireOwned(userId, fileId);
        DocumentVersion version = requireVersion(asset.getId(), versionNo);

        if (!fileStorage.exists(version.getStorageKey())) {
            log.error("版本元数据存在但存储缺失, fileId={}, versionNo={}, storageKey={}",
                    asset.getId(), version.getVersionNo(), version.getStorageKey());
            throw new BusinessException(500, "该版本的内容已丢失，请联系管理员");
        }
        FileAsset snapshot = new FileAsset();
        BeanUtils.copyProperties(asset, snapshot);
        snapshot.setStorageKey(version.getStorageKey());
        snapshot.setSizeBytes(version.getSizeBytes());
        snapshot.setSha256(version.getSha256());
        snapshot.setContentType(version.getContentType());
        snapshot.setExtension(version.getExtension());
        // 下载名带上版本号，避免用户下载多个版本后无法区分
        String fileName = withVersionSuffix(FileDownload.withExtension(asset.getDisplayName(),
                version.getExtension()), version.getVersionNo());
        InputStream input = fileStorage.read(version.getStorageKey());
        return FileDownload.of(snapshot, input, version.getSizeBytes(), fileName);
    }

    // ------------------------------------------------------------------ 追加

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                         ChangeType changeType, String instruction, String summary,
                                         Integer expectVersion) {
        // 非任务产出（上传、手工编辑）没有任务级幂等标识
        return appendVersion(fileId, userId, content, changeType, instruction, summary,
                expectVersion, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                         ChangeType changeType, String instruction, String summary,
                                         Integer expectVersion, Long taskId, Integer attemptNo) {
        if (fileId == null || userId == null) {
            throw new BusinessException(400, "文件 ID 与用户 ID 不能为空");
        }
        if (content == null || content.storageKey() == null || content.storageKey().isBlank()) {
            throw new BusinessException(400, "版本内容不能为空");
        }
        if (taskId != null && (attemptNo == null || attemptNo < 1)) {
            throw new BusinessException(400, "任务产出必须提供正数的尝试序号");
        }

        // 任务级幂等（TODO 3.4）：同一任务的同一次尝试已经产出过版本时，
        // 直接返回已有版本，不再分配版本号、不再落盘。
        // 这是「失败重试不重复生成版本」的核心判断。
        if (taskId != null) {
            DocumentVersion existing = findVersionByTaskAttempt(taskId, attemptNo);
            if (existing != null) {
                log.info("任务尝试已产出过版本，复用已有记录, taskId={}, attemptNo={}, versionNo={}",
                        taskId, attemptNo, existing.getVersionNo());
                return existing;
            }
        }

        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(404, "文件不存在");
        }
        if (!STATUS_READY.equals(asset.getStatus())) {
            throw new BusinessException(400, "文件已删除，无法新增版本");
        }
        String contentType = content.contentType() == null ? asset.getContentType() : content.contentType();
        String extension = content.extension() == null ? asset.getExtension() : content.extension();
        if ((content.contentType() == null) != (content.extension() == null)
                || contentType == null || contentType.isBlank() || contentType.length() > 128
                || extension == null || !extension.matches("[a-z0-9]{1,16}")) {
            throw new BusinessException(400, "版本格式信息无效");
        }

        // 原子分配版本号：期望版本不符时影响行数为 0，说明期间已有其他任务改了版本
        int updated = fileAssetMapper.advanceVersion(fileId, expectVersion);
        if (updated != 1) {
            Integer actual = fileAssetMapper.selectLatestVersion(fileId);
            log.info("版本冲突, fileId={}, expect={}, actual={}", fileId, expectVersion, actual);
            throw new BusinessException(409,
                    "文档已被其他操作修改（当前版本 " + (actual == null ? "未知" : actual)
                            + "），请刷新后重试");
        }

        Integer newVersionNo = fileAssetMapper.selectLatestVersion(fileId);
        if (newVersionNo == null) {
            throw new BusinessException(500, "分配版本号失败");
        }

        DocumentVersion version = new DocumentVersion();
        version.setFileId(fileId);
        version.setUserId(userId);
        version.setVersionNo(newVersionNo);
        // parentVersion 表达版本链：一个版本的来源是它被创建时的当前版本
        version.setParentVersion(expectVersion != null ? expectVersion : newVersionNo - 1);
        version.setStorageKey(content.storageKey());
        version.setContentType(contentType);
        version.setExtension(extension);
        version.setSizeBytes(content.sizeBytes());
        version.setSha256(content.sha256());
        version.setInstruction(instruction);
        version.setResultSummary(summary);
        version.setChangeType(changeType.name());
        version.setTaskId(taskId);
        version.setAttemptNo(attemptNo);
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            // 并发下同一任务尝试可能同时通过上面的「先查」检查，
            // 此时唯一索引 uk_document_version_task_attempt 会拒绝第二条。
            // 回查并返回已存在的版本，保证幂等成立。
            if (taskId != null) {
                DocumentVersion existing = findVersionByTaskAttempt(taskId, attemptNo);
                if (existing != null) {
                    log.info("并发写入被唯一索引拦截，复用已有版本, taskId={}, attemptNo={}",
                            taskId, attemptNo);
                    return existing;
                }
            }
            throw e;
        }

        FileAsset update = new FileAsset();
        update.setId(fileId);
        update.setStorageKey(content.storageKey());
        update.setSizeBytes(content.sizeBytes());
        update.setSha256(content.sha256());
        update.setContentType(contentType);
        update.setExtension(extension);
        if (!extension.equals(asset.getExtension())) {
            update.setDisplayName(FileDownload.withExtension(asset.getDisplayName(), extension));
        }
        if (fileAssetMapper.updateById(update) != 1) {
            throw new BusinessException(500, "同步当前版本内容失败");
        }

        log.info("新增文档版本, fileId={}, versionNo={}, changeType={}, parentVersion={}",
                fileId, newVersionNo, changeType, version.getParentVersion());
        return version;
    }

    // ------------------------------------------------------------------ 恢复

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VersionVO restore(Long userId, Long fileId, Integer versionNo, Integer expectVersion) {
        FileAsset asset = fileService.requireOwned(userId, fileId);
        DocumentVersion source = requireVersion(asset.getId(), versionNo);

        // 恢复不修改历史，而是把目标版本的内容复制成一个新版本，
        // 这样「恢复」本身也能被再次撤销，历史链条保持完整
        DocumentVersion restored = appendVersion(asset.getId(), userId,
                new VersionContent(source.getStorageKey(), source.getSizeBytes(), source.getSha256(),
                        source.getContentType(), source.getExtension()),
                ChangeType.RESTORE,
                "恢复至版本 " + versionNo,
                "由版本 " + versionNo + " 恢复",
                expectVersion);
        return VersionVO.from(restored);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 按任务与尝试序号查已产出的版本（TODO 3.4 任务级幂等）。
     *
     * <p>同一任务的同一次尝试最多一条记录，因此这里的 selectOne 不会返回多条；
     * 唯一索引 {@code uk_document_version_task_attempt} 保证这一点。
     */
    private DocumentVersion findVersionByTaskAttempt(Long taskId, Integer attemptNo) {
        return versionMapper.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getTaskId, taskId)
                .eq(DocumentVersion::getAttemptNo, attemptNo));
    }

    @Override
    public boolean hasProducedVersion(Long taskId) {
        if (taskId == null) {
            return false;
        }
        Long count = versionMapper.selectCount(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getTaskId, taskId));
        return count != null && count > 0;
    }

    private DocumentVersion requireVersion(Long fileId, Integer versionNo) {
        if (versionNo == null) {
            throw new BusinessException(400, "版本号不能为空");
        }
        DocumentVersion version = versionMapper.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getFileId, fileId)
                .eq(DocumentVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BusinessException(404, "版本不存在");
        }
        return version;
    }

    /** 在扩展名前插入版本号，例如 report.docx -> report-v2.docx。 */
    private static String withVersionSuffix(String displayName, Integer versionNo) {
        String name = displayName == null || displayName.isBlank() ? "document" : displayName;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name + "-v" + versionNo;
        }
        return name.substring(0, dot) + "-v" + versionNo + name.substring(dot);
    }
}
