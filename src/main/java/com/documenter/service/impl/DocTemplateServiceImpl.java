package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.component.StorageWriter;
import com.documenter.dto.DocTemplateQueryDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.dto.TemplateVersionQueryDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.entity.DocTemplateVersion;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.mapper.DocTemplateVersionMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocTemplateService;
import com.documenter.service.FileStorage;
import com.documenter.util.TemplateVariables;
import com.documenter.vo.DocTemplateVO;
import com.documenter.vo.DocTemplateVersionVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 公共模板管理实现（TODO 3.2「管理公共模板」）。
 *
 * <p>与文件上传的关系：模板文件复用 {@link StorageWriter}，
 * 因此类型识别、结构安全检查、大小限制与用户上传完全一致，
 * 不存在「模板上传是绕过校验的后门」这种问题。
 *
 * <p>版本策略：当前内容与不可变历史索引在同一事务保存，以版本号乐观锁串行化文件写入。
 * 历史只保存文件内容；变量、分类及启用状态不随恢复而改变。正式存储回收仍待实现。
 * 新上传文件在本次操作失败或事务回滚时清理。
 */
@Slf4j
@Service
public class DocTemplateServiceImpl implements DocTemplateService {

    private final DocTemplateMapper mapper;
    private final DocTemplateVersionMapper versions;
    private final StorageWriter storageWriter;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    public DocTemplateServiceImpl(DocTemplateMapper mapper, DocTemplateVersionMapper versions, StorageWriter storageWriter,
                                  FileStorage fileStorage, AuditService auditService) {
        this.mapper = mapper;
        this.versions = versions;
        this.storageWriter = storageWriter;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    @Override
    public PageResult<DocTemplateVO> listForAdmin(DocTemplateQueryDTO query) {
        Boolean enabled = parseEnabled(query.getEnabled());
        String category = trimToNull(query.getCategory());
        String keyword = trimToNull(query.getKeyword());
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = mapper.selectCount(Wrappers.<DocTemplate>lambdaQuery()
                .eq(enabled != null, DocTemplate::getEnabled, enabled)
                .eq(category != null, DocTemplate::getCategory, category)
                .and(keyword != null, w -> w.like(DocTemplate::getCode, keyword)
                        .or().like(DocTemplate::getName, keyword)));

        List<DocTemplate> records = mapper.selectList(Wrappers.<DocTemplate>lambdaQuery()
                .eq(enabled != null, DocTemplate::getEnabled, enabled)
                .eq(category != null, DocTemplate::getCategory, category)
                .and(keyword != null, w -> w.like(DocTemplate::getCode, keyword)
                        .or().like(DocTemplate::getName, keyword))
                .orderByDesc(DocTemplate::getUpdateTime)
                .last("LIMIT " + query.getSize() + " OFFSET " + offset));

        PageResult<DocTemplateVO> result = new PageResult<>();
        result.setRecords(records.stream().map(DocTemplateVO::from).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public List<DocTemplateVO> listEnabled() {
        return mapper.selectList(Wrappers.<DocTemplate>lambdaQuery()
                        .eq(DocTemplate::getEnabled, true)
                        .eq(DocTemplate::getContentType, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                        .isNotNull(DocTemplate::getStorageKey)
                        .ne(DocTemplate::getStorageKey, "")
                        .gt(DocTemplate::getVersionNo, 0)
                        .orderByAsc(DocTemplate::getCategory)
                        .orderByAsc(DocTemplate::getName))
                .stream()
                .map(DocTemplateVO::from)
                .toList();
    }

    @Override
    public DocTemplateVO detail(Long id) {
        return DocTemplateVO.from(requireTemplate(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocTemplateVO create(SaveDocTemplateDTO dto, MultipartFile file) {
        String code = dto.getCode().trim();
        if (existsByCode(code)) {
            throw new BusinessException(409, "模板标识「" + code + "」已存在");
        }

        String variables = TemplateVariables.validateAndNormalize(dto.getVariables());
        DocTemplate template = new DocTemplate();
        template.setCode(code);
        template.setName(dto.getName().trim());
        template.setCategory(trimToNull(dto.getCategory()));
        template.setDescription(trimToNull(dto.getDescription()));
        template.setVariables(variables);
        template.setVersionNo(1);
        // 新建默认停用：模板要经过管理员核对后才能对普通用户可见
        template.setEnabled(Boolean.FALSE);

        return withStoredFile(file, stored -> {
            template.setStorageKey(stored.storageKey());
            template.setOriginalName(stored.originalName());
            template.setContentType(stored.contentType());
            template.setExtension(stored.extension());
            template.setSizeBytes(stored.sizeBytes());
            int inserted;
            try {
                inserted = mapper.insert(template);
            } catch (DuplicateKeyException e) {
                // 并发创建同 code 时由唯一索引兜底，文件由 withStoredFile 清理。
                throw new BusinessException(409, "模板标识「" + code + "」已存在");
            }
            if (inserted != 1 || template.getId() == null) {
                throw new BusinessException(500, "创建模板失败，请稍后重试");
            }
            appendSnapshot(snapshot(template, "CREATE", null));

            auditService.success(AuditAction.ADMIN_TEMPLATE_CREATE, "TEMPLATE", template.getId(),
                    "新建模板 code=" + code + ", 类型=" + stored.extension());
            return DocTemplateVO.from(requireTemplate(template.getId()));
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocTemplateVO update(Long id, SaveDocTemplateDTO dto) {
        DocTemplate existing = requireTemplate(id);
        String newCode = dto.getCode().trim();
        if (!newCode.equals(existing.getCode()) && existsByCode(newCode)) {
            throw new BusinessException(409, "模板标识「" + newCode + "」已存在");
        }

        DocTemplate update = new DocTemplate();
        update.setId(id);
        update.setCode(newCode);
        update.setName(dto.getName().trim());
        update.setCategory(trimToNull(dto.getCategory()));
        update.setDescription(trimToNull(dto.getDescription()));
        update.setVariables(TemplateVariables.validateAndNormalize(dto.getVariables()));

        try {
            if (mapper.updateMetadata(update) != 1) {
                throw new BusinessException(409, "模板元数据更新失败，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "模板标识「" + newCode + "」已存在");
        }

        auditService.success(AuditAction.ADMIN_TEMPLATE_UPDATE, "TEMPLATE", id,
                "更新模板元数据 code=" + newCode);
        return DocTemplateVO.from(requireTemplate(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocTemplateVO replaceContent(Long id, MultipartFile file) {
        DocTemplate existing = requireTemplate(id);
        int next = nextVersion(existing);
        int expected = existing.getVersionNo();
        return withStoredFile(file, stored -> {
            int updated = mapper.replaceContent(id, stored.storageKey(), stored.originalName(),
                    stored.contentType(), stored.extension(), stored.sizeBytes(), expected);
            if (updated != 1) {
                throw new BusinessException(409, "模板内容更新失败，请刷新后重试");
            }
            DocTemplate content = new DocTemplate();
            content.setId(id);
            content.setVersionNo(next);
            content.setStorageKey(stored.storageKey());
            content.setOriginalName(stored.originalName());
            content.setContentType(stored.contentType());
            content.setExtension(stored.extension());
            content.setSizeBytes(stored.sizeBytes());
            appendSnapshot(snapshot(content, "REPLACE", null));

            log.info("模板内容已替换, templateId={}, 旧 storageKey 保留={}", id, existing.getStorageKey() != null);
            auditService.success(AuditAction.ADMIN_TEMPLATE_CONTENT_REPLACE, "TEMPLATE", id,
                    "替换模板内容，类型=" + stored.extension());
            return DocTemplateVO.from(requireTemplate(id));
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocTemplateVO updateEnabled(Long id, boolean enabled) {
        DocTemplate existing = requireTemplate(id);
        if (enabled && (existing.getStorageKey() == null || existing.getStorageKey().isBlank())) {
            // 没有内容的模板启用后用户选了也生成不出东西
            throw new BusinessException(409, "该模板尚未上传文件，无法启用");
        }

        if (mapper.updateEnabled(id, enabled) != 1) {
            throw new BusinessException(409, "模板状态更新失败，请刷新后重试");
        }

        auditService.success(AuditAction.ADMIN_TEMPLATE_ENABLE, "TEMPLATE", id,
                (enabled ? "启用" : "停用") + "模板 code=" + existing.getCode());
        return DocTemplateVO.from(requireTemplate(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DocTemplate existing = requireTemplate(id);
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            // 启用中的模板可能正被使用，要求先停用，避免生成流程中途找不到模板
            throw new BusinessException(409, "请先停用该模板再删除");
        }
        if (mapper.deleteDisabled(id) != 1) {
            throw new BusinessException(409, "模板已启用或已变更，请刷新后重试");
        }
        // 磁盘文件暂时保留；正式存储的回收策略仍待实现。
        auditService.success(AuditAction.ADMIN_TEMPLATE_DELETE, "TEMPLATE", id,
                "删除模板 code=" + existing.getCode());
    }

    @Override
    public FileDownload download(Long id) {
        DocTemplate template = requireTemplate(id);
        if (template.getStorageKey() == null || !fileStorage.exists(template.getStorageKey())) {
            log.error("模板元数据存在但存储缺失, templateId={}", id);
            throw new BusinessException(500, "模板文件已丢失，请重新上传");
        }
        InputStream input = fileStorage.read(template.getStorageKey());

        // 复用 FileDownload 的字段结构：它只要求一个能提供 contentType 与文件名的实体，
        // 因此用一个临时 FileAsset 承载展示信息，避免为模板再引入一套下载模型。
        com.documenter.entity.FileAsset carrier = new com.documenter.entity.FileAsset();
        carrier.setDisplayName(template.getName() + "." + template.getExtension());
        carrier.setOriginalName(template.getOriginalName());
        carrier.setContentType(template.getContentType());
        return FileDownload.of(carrier, input,
                template.getSizeBytes() == null ? 0L : template.getSizeBytes());
    }

    @Override
    public PageResult<DocTemplateVersionVO> listVersions(Long id, TemplateVersionQueryDTO query) {
        requireTemplate(id);
        if (query == null || query.getPage() < 1 || query.getSize() < 1 || query.getSize() > 100) {
            throw new BusinessException(400, "模板历史分页参数不合法");
        }
        long offset;
        try {
            offset = Math.multiplyExact(query.getPage() - 1, query.getSize());
        } catch (ArithmeticException e) {
            throw new BusinessException(400, "模板历史页码过大");
        }
        PageResult<DocTemplateVersionVO> result = new PageResult<>();
        result.setTotal(versions.countVersions(id));
        result.setRecords(versions.listVersions(id, query.getSize(), offset).stream()
                .map(DocTemplateVersionVO::from).toList());
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public FileDownload downloadVersion(Long id, Integer versionNo) {
        DocTemplate template = requireTemplate(id);
        DocTemplateVersion version = requireVersion(id, versionNo);
        requireStoredContent(version);
        com.documenter.entity.FileAsset carrier = new com.documenter.entity.FileAsset();
        carrier.setDisplayName(template.getName() + "-v" + versionNo);
        carrier.setOriginalName(version.getOriginalName());
        carrier.setContentType(version.getContentType());
        carrier.setExtension(version.getExtension());
        // Explicit filename preserves dots in template display names while using the historical format.
        String filename = template.getName() + "-v" + versionNo + "." + version.getExtension();
        auditService.success(AuditAction.ADMIN_TEMPLATE_VERSION_DOWNLOAD, "TEMPLATE", id, "versionNo=" + versionNo);
        return FileDownload.of(carrier, fileStorage.read(version.getStorageKey()), version.getSizeBytes(), filename);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocTemplateVO restoreVersion(Long id, Integer versionNo, Integer expectVersion) {
        if (expectVersion == null || expectVersion < 1) {
            throw new BusinessException(400, "恢复模板必须指定正数 expectVersion");
        }
        DocTemplate current = requireTemplate(id);
        if (!expectVersion.equals(current.getVersionNo())) {
            throw new BusinessException(409, "模板已更新，请刷新后重试");
        }
        int next = nextVersion(current);
        DocTemplateVersion source = requireVersion(id, versionNo);
        requireStoredContent(source);
        if (mapper.replaceContent(id, source.getStorageKey(), source.getOriginalName(), source.getContentType(),
                source.getExtension(), source.getSizeBytes(), expectVersion) != 1) {
            throw new BusinessException(409, "模板已更新，请刷新后重试");
        }
        DocTemplate content = new DocTemplate();
        content.setId(id);
        content.setVersionNo(next);
        content.setStorageKey(source.getStorageKey());
        content.setOriginalName(source.getOriginalName());
        content.setContentType(source.getContentType());
        content.setExtension(source.getExtension());
        content.setSizeBytes(source.getSizeBytes());
        appendSnapshot(snapshot(content, "RESTORE", versionNo));
        auditService.success(AuditAction.ADMIN_TEMPLATE_RESTORE, "TEMPLATE", id,
                "restoredFromVersion=" + versionNo + ", versionNo=" + next);
        return DocTemplateVO.from(requireTemplate(id));
    }

    private DocTemplateVersion requireVersion(Long id, Integer versionNo) {
        if (versionNo == null || versionNo < 1) {
            throw new BusinessException(400, "模板版本号必须为正数");
        }
        DocTemplateVersion version = versions.findVersion(id, versionNo);
        if (version == null) throw new BusinessException(404, "模板版本不存在");
        return version;
    }

    private void requireStoredContent(DocTemplateVersion version) {
        if (version.getStorageKey() == null || version.getStorageKey().isBlank()
                || version.getContentType() == null || version.getContentType().isBlank()
                || version.getExtension() == null || version.getExtension().isBlank()
                || version.getSizeBytes() == null || version.getSizeBytes() < 0) {
            throw new BusinessException(500, "模板版本元数据不完整，请重新上传");
        }
        if (!fileStorage.exists(version.getStorageKey())) {
            throw new BusinessException(500, "该模板版本文件已丢失，请重新上传");
        }
    }

    private static int nextVersion(DocTemplate template) {
        if (template.getVersionNo() == null || template.getVersionNo() < 0
                || template.getVersionNo() == Integer.MAX_VALUE) {
            throw new BusinessException(409, "模板内容版本号异常，无法新增版本");
        }
        return template.getVersionNo() + 1;
    }

    private static DocTemplateVersion snapshot(DocTemplate template, String changeType, Integer restoredFrom) {
        DocTemplateVersion version = new DocTemplateVersion();
        version.setTemplateId(template.getId());
        version.setVersionNo(template.getVersionNo());
        version.setStorageKey(template.getStorageKey());
        version.setOriginalName(template.getOriginalName());
        version.setContentType(template.getContentType());
        version.setExtension(template.getExtension());
        version.setSizeBytes(template.getSizeBytes());
        version.setChangeType(changeType);
        version.setRestoredFromVersion(restoredFrom);
        return version;
    }

    private void appendSnapshot(DocTemplateVersion version) {
        try {
            if (versions.insert(version) != 1) {
                throw new BusinessException(500, "保存模板历史版本失败，请稍后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "模板版本已变化，请刷新后重试");
        }
    }

    private DocTemplate requireTemplate(Long id) {
        if (id == null || id < 1) {
            throw new BusinessException(400, "模板 ID 必须为正数");
        }
        DocTemplate template = mapper.selectById(id);
        if (template == null) {
            throw new BusinessException(404, "模板不存在");
        }
        return template;
    }

    private boolean existsByCode(String code) {
        return mapper.exists(Wrappers.<DocTemplate>lambdaQuery().eq(DocTemplate::getCode, code));
    }

    private DocTemplateVO withStoredFile(MultipartFile file,
                                         Function<StorageWriter.StoredFile, DocTemplateVO> operation) {
        StorageWriter.StoredFile stored = storageWriter.store(file);
        AtomicBoolean cleanupAttempted = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleanupAttempted.compareAndSet(false, true)) {
                return;
            }
            try {
                if (!fileStorage.delete(stored.storageKey())) {
                    log.warn("无法清理未提交的模板文件, storageKey={}", stored.storageKey());
                }
            } catch (RuntimeException e) {
                // 保留原始业务/数据库异常，清理失败单独记录。
                log.warn("清理未提交的模板文件失败, storageKey={}", stored.storageKey(), e);
            }
        };
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            cleanup.run();
                        }
                    }
                });
            }
            return operation.apply(stored);
        } catch (RuntimeException | Error e) {
            cleanup.run();
            throw e;
        }
    }

    private static Boolean parseEnabled(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.valueOf(value.trim());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
