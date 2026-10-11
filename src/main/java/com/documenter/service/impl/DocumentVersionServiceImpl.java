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
import org.springframework.stereotype.Service;
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
        InputStream input = fileStorage.read(version.getStorageKey());
        // 下载名带上版本号，避免用户下载多个版本后无法区分
        String fileName = withVersionSuffix(asset.getDisplayName(), version.getVersionNo());
        return FileDownload.of(asset, input, version.getSizeBytes(), fileName);
    }

    // ------------------------------------------------------------------ 追加

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentVersion appendVersion(Long fileId, Long userId, VersionContent content,
                                         ChangeType changeType, String instruction, String summary,
                                         Integer expectVersion) {
        if (fileId == null || userId == null) {
            throw new BusinessException(400, "文件 ID 与用户 ID 不能为空");
        }
        if (content == null || content.storageKey() == null || content.storageKey().isBlank()) {
            throw new BusinessException(400, "版本内容不能为空");
        }

        FileAsset asset = fileAssetMapper.selectById(fileId);
        if (asset == null) {
            throw new BusinessException(404, "文件不存在");
        }
        if (!STATUS_READY.equals(asset.getStatus())) {
            throw new BusinessException(400, "文件已删除，无法新增版本");
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
        version.setSizeBytes(content.sizeBytes());
        version.setSha256(content.sha256());
        version.setInstruction(instruction);
        version.setResultSummary(summary);
        version.setChangeType(changeType.name());
        versionMapper.insert(version);

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
                new VersionContent(source.getStorageKey(), source.getSizeBytes(), source.getSha256()),
                ChangeType.RESTORE,
                "恢复至版本 " + versionNo,
                "由版本 " + versionNo + " 恢复",
                expectVersion);
        return VersionVO.from(restored);
    }

    // ------------------------------------------------------------------ 内部

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
