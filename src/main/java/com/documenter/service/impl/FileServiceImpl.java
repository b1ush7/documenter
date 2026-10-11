package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.component.InitialVersionWriter;
import com.documenter.component.UploadRateLimiter;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.FileQueryDTO;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.util.FileTypeDetector;
import com.documenter.util.Digests;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 文件业务实现（TODO 3.3）。
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    private static final String STATUS_READY = "READY";
    private static final String STATUS_DELETED = "DELETED";
    private static final String SOURCE_UPLOAD = "UPLOAD";
    private static final String SOURCE_GENERATED = "GENERATED";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final FileAssetMapper fileAssetMapper;
    private final FileStorage fileStorage;
    private final StorageProperties properties;
    private final UploadRateLimiter uploadRateLimiter;
    private final InitialVersionWriter initialVersionWriter;

    public FileServiceImpl(FileAssetMapper fileAssetMapper, FileStorage fileStorage,
                           StorageProperties properties, UploadRateLimiter uploadRateLimiter,
                           InitialVersionWriter initialVersionWriter) {
        this.fileAssetMapper = fileAssetMapper;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.uploadRateLimiter = uploadRateLimiter;
        this.initialVersionWriter = initialVersionWriter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileAssetVO upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (file.getSize() > maxSize) {
            throw new BusinessException(413, "文件超过大小限制（最大 " + (maxSize / 1024 / 1024) + "MB）");
        }

        uploadRateLimiter.acquire(userId);

        // 先读头部做真实类型识别：只看内容，不信任客户端声明的 Content-Type 与扩展名
        byte[] head;
        try (InputStream probe = new BufferedInputStream(file.getInputStream())) {
            head = probe.readNBytes(FileTypeDetector.PEEK_SIZE);
        } catch (IOException e) {
            throw new BusinessException(400, "读取上传文件失败，请重试");
        }
        if (head.length == 0) {
            throw new BusinessException(400, "上传文件内容为空");
        }

        FileTypeDetector.DetectedType detected = FileTypeDetector.detect(head);
        if (!detected.supported()) {
            log.info("拒绝不支持的上传类型, userId={}, 声明文件名={}, 实际头部={}",
                    userId, FileTypeDetector.sanitizeDisplayName(file.getOriginalFilename()),
                    FileTypeDetector.describe(head));
            throw new BusinessException(415, detected.message());
        }

        String originalName = FileTypeDetector.sanitizeDisplayName(file.getOriginalFilename());
        String storageKey = null;
        try {
            // 边写盘边算摘要：一次遍历同时完成落盘与 sha256，避免大文件读两遍
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest;
            try (InputStream input = new BufferedInputStream(file.getInputStream());
                 DigestInputStream digestInput = new DigestInputStream(input, sha256)) {
                storageKey = fileStorage.store(digestInput, detected.extension());
            }
            digest = sha256.digest();

            FileAsset asset = new FileAsset();
            asset.setUserId(userId);
            asset.setOriginalName(originalName);
            asset.setDisplayName(originalName);
            asset.setStorageKey(storageKey);
            asset.setContentType(detected.contentType());
            asset.setExtension(detected.extension());
            asset.setSizeBytes(file.getSize());
            asset.setSha256(HexFormat.of().formatHex(digest));
            asset.setSource(SOURCE_UPLOAD);
            asset.setStatus(STATUS_READY);
            asset.setLatestVersion(0);
            fileAssetMapper.insert(asset);

            if (asset.getId() == null) {
                throw new BusinessException(500, "保存文件记录失败，请稍后重试");
            }

            // 建立版本 1（TODO 3.3）：与上传记录同事务，失败会整体回滚，
            // 不会留下「有文件但没有版本」的半成品
            initialVersionWriter.createInitialVersion(asset, userId);
            asset.setLatestVersion(1);

            log.info("文件上传成功, userId={}, fileId={}, type={}, size={}",
                    userId, asset.getId(), detected.extension(), file.getSize());
            return FileAssetVO.from(asset);
        } catch (BusinessException e) {
            cleanupOrphan(userId, storageKey);
            throw e;
        } catch (NoSuchAlgorithmException e) {
            cleanupOrphan(userId, storageKey);
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        } catch (IOException e) {
            cleanupOrphan(userId, storageKey);
            throw new BusinessException(500, "文件保存失败，请稍后重试");
        } catch (RuntimeException e) {
            cleanupOrphan(userId, storageKey);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileAssetVO createGeneratedDocx(Long userId, String displayName, byte[] content,
                                           String instruction) {
        if (userId == null) {
            throw new BusinessException(400, "用户 ID 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(400, "生成的 DOCX 内容不能为空");
        }
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (content.length > maxSize) {
            throw new BusinessException(413, "生成文件超过大小限制（最大 "
                    + (maxSize / 1024 / 1024) + "MB）");
        }
        String safeName = FileTypeDetector.sanitizeDisplayName(displayName);
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
            safeName += ".docx";
        }

        String storageKey = null;
        try {
            storageKey = fileStorage.store(content, "docx");
            FileAsset asset = new FileAsset();
            asset.setUserId(userId);
            asset.setOriginalName(safeName);
            asset.setDisplayName(safeName);
            asset.setStorageKey(storageKey);
            asset.setContentType(DOCX_CONTENT_TYPE);
            asset.setExtension("docx");
            asset.setSizeBytes((long) content.length);
            asset.setSha256(Digests.sha256Hex(content));
            asset.setSource(SOURCE_GENERATED);
            asset.setStatus(STATUS_READY);
            asset.setLatestVersion(0);
            fileAssetMapper.insert(asset);
            if (asset.getId() == null) {
                throw new BusinessException(500, "保存生成文件记录失败，请稍后重试");
            }

            initialVersionWriter.createInitialVersion(asset, userId, ChangeType.MANUAL_EDIT,
                    instruction, "本地结构化生成 DOCX");
            asset.setLatestVersion(1);
            log.info("生成文件保存成功, userId={}, fileId={}, size={}",
                    userId, asset.getId(), content.length);
            return FileAssetVO.from(asset);
        } catch (RuntimeException e) {
            cleanupOrphan(userId, storageKey);
            throw e;
        }
    }

    @Override
    public PageResult<FileAssetVO> list(Long userId, FileQueryDTO query) {
        String status = query.getStatus();
        if (status == null || status.isBlank()) {
            status = STATUS_READY;
        }
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
        boolean hasKeyword = keyword != null && !keyword.isEmpty();

        // 这里刻意不用 selectPage：MyBatis-Plus 的分页依赖 PaginationInnerInterceptor，
        // 而该拦截器来自 mybatis-plus-jsqlparser 构件（3.5.9+ 已从核心拆出）。
        // 若构件缺失，selectPage 不会报错，而是静默返回全量数据——分页形同虚设且难以察觉。
        // 因此用显式 LIMIT 保证行为确定：分页是否生效不依赖任何自动配置。
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = fileAssetMapper.selectCount(Wrappers.<FileAsset>lambdaQuery()
                .eq(FileAsset::getUserId, userId)
                .eq(FileAsset::getStatus, status)
                .like(hasKeyword, FileAsset::getDisplayName, keyword));

        List<FileAsset> records = fileAssetMapper.selectList(Wrappers.<FileAsset>lambdaQuery()
                .eq(FileAsset::getUserId, userId)
                .eq(FileAsset::getStatus, status)
                .like(hasKeyword, FileAsset::getDisplayName, keyword)
                .orderByDesc(FileAsset::getCreateTime)
                .last("LIMIT " + query.getSize() + " OFFSET " + offset));

        PageResult<FileAssetVO> result = new PageResult<>();
        result.setRecords(records.stream().map(FileAssetVO::from).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public FileAssetVO detail(Long userId, Long fileId) {
        return FileAssetVO.from(requireOwned(userId, fileId));
    }

    @Override
    public FileAssetVO rename(Long userId, Long fileId, String displayName) {
        FileAsset asset = requireOwned(userId, fileId);
        if (asset.getStatus() == null || !STATUS_READY.equals(asset.getStatus())) {
            throw new BusinessException(400, "已删除的文件不能重命名");
        }
        String safeName = FileTypeDetector.sanitizeDisplayName(displayName);
        if (safeName.isBlank()) {
            throw new BusinessException(400, "新文件名不能为空");
        }

        FileAsset update = new FileAsset();
        update.setId(asset.getId());
        // 只改展示名；storageKey 不变，磁盘上的文件不移动
        update.setDisplayName(safeName);
        fileAssetMapper.updateById(update);

        return FileAssetVO.from(fileAssetMapper.selectById(asset.getId()));
    }

    @Override
    public void delete(Long userId, Long fileId) {
        FileAsset asset = requireOwned(userId, fileId);
        if (STATUS_DELETED.equals(asset.getStatus())) {
            // 幂等：重复删除直接返回成功，不再改 deleteTime
            return;
        }

        // 软删除，保留磁盘文件：历史版本与衍生文件仍需要能追溯到这份原始内容
        FileAsset update = new FileAsset();
        update.setId(asset.getId());
        update.setStatus(STATUS_DELETED);
        update.setDeleteTime(LocalDateTime.now());
        fileAssetMapper.updateById(update);
        log.info("文件已软删除, userId={}, fileId={}, storageKey 保留", userId, asset.getId());
    }

    @Override
    public FileDownload download(Long userId, Long fileId) {
        FileAsset asset = requireOwned(userId, fileId);
        if (!STATUS_READY.equals(asset.getStatus())) {
            throw new BusinessException(400, "文件已删除，无法下载");
        }
        if (!fileStorage.exists(asset.getStorageKey())) {
            // 元数据在但磁盘文件丢失，属于运维事故，需要明确提示而不是返回空文件
            log.error("文件元数据存在但存储缺失, fileId={}, storageKey={}", asset.getId(), asset.getStorageKey());
            throw new BusinessException(500, "文件内容丢失，请联系管理员");
        }
        InputStream input = fileStorage.read(asset.getStorageKey());
        return FileDownload.of(asset, input, asset.getSizeBytes());
    }

    @Override
    public FileAsset requireOwned(Long userId, Long fileId) {
        if (userId == null || fileId == null) {
            throw new BusinessException(400, "文件 ID 不能为空");
        }
        FileAsset asset = fileAssetMapper.selectById(fileId);
        // 他人的文件统一按「不存在」处理：若返回 403，攻击者可据此枚举出哪些 ID 是真实存在的
        if (asset == null || !userId.equals(asset.getUserId())) {
            throw new BusinessException(404, "文件不存在");
        }
        return asset;
    }

    @Override
    public long usedBytes(Long userId) {
        Long total = fileAssetMapper.sumActiveSizeByUser(userId);
        return total == null ? 0L : total;
    }

    /** 落盘成功但入库失败时清理磁盘文件，避免产生无法被任何记录引用的孤儿文件。 */
    private void cleanupOrphan(Long userId, String storageKey) {
        if (storageKey == null) {
            return;
        }
        boolean deleted = fileStorage.delete(storageKey);
        log.warn("文件保存失败，已清理落盘文件, userId={}, storageKey={}, deleted={}", userId, storageKey, deleted);
    }
}
