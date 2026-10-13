package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.component.StorageWriter;
import com.documenter.dto.DocTemplateQueryDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocTemplateService;
import com.documenter.service.FileStorage;
import com.documenter.util.TemplateVariables;
import com.documenter.vo.DocTemplateVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 公共模板管理实现（TODO 3.2「管理公共模板」）。
 *
 * <p>与文件上传的关系：模板文件复用 {@link StorageWriter}，
 * 因此类型识别、结构安全检查、大小限制与用户上传完全一致，
 * 不存在「模板上传是绕过校验的后门」这种问题。
 *
 * <p>版本策略：单行保存当前版本，{@code version_no} 在替换内容时由 SQL 原子递增。
 * 旧 storageKey 对应的磁盘文件不会被删除——出问题时可回退，
 * 物理回收交给过期文件清理任务。
 */
@Slf4j
@Service
public class DocTemplateServiceImpl implements DocTemplateService {

    private final DocTemplateMapper mapper;
    private final StorageWriter storageWriter;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    public DocTemplateServiceImpl(DocTemplateMapper mapper, StorageWriter storageWriter,
                                  FileStorage fileStorage, AuditService auditService) {
        this.mapper = mapper;
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

        StorageWriter.StoredFile stored = storageWriter.store(file);

        DocTemplate template = new DocTemplate();
        template.setCode(code);
        template.setName(dto.getName().trim());
        template.setCategory(trimToNull(dto.getCategory()));
        template.setDescription(trimToNull(dto.getDescription()));
        template.setVariables(TemplateVariables.validateAndNormalize(dto.getVariables()));
        template.setStorageKey(stored.storageKey());
        template.setOriginalName(stored.originalName());
        template.setContentType(stored.contentType());
        template.setExtension(stored.extension());
        template.setSizeBytes(stored.sizeBytes());
        template.setVersionNo(1);
        // 新建默认停用：模板要经过管理员核对后才能对普通用户可见
        template.setEnabled(Boolean.FALSE);

        try {
            mapper.insert(template);
        } catch (DuplicateKeyException e) {
            // 并发创建同 code 时先查后插会失效，靠唯一索引兜底；顺带清理已落盘的文件
            fileStorage.delete(stored.storageKey());
            throw new BusinessException(409, "模板标识「" + code + "」已存在");
        }
        if (template.getId() == null) {
            fileStorage.delete(stored.storageKey());
            throw new BusinessException(500, "创建模板失败，请稍后重试");
        }

        auditService.success(AuditAction.ADMIN_TEMPLATE_CREATE, "TEMPLATE", template.getId(),
                "新建模板 code=" + code + ", 类型=" + stored.extension());
        return DocTemplateVO.from(requireTemplate(template.getId()));
    }

    @Override
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
            mapper.updateById(update);
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
        StorageWriter.StoredFile stored = storageWriter.store(file);

        String previousKey = existing.getStorageKey();
        int updated = mapper.replaceContent(id, stored.storageKey(), stored.originalName(),
                stored.contentType(), stored.extension(), stored.sizeBytes());
        if (updated != 1) {
            // 更新失败说明记录状态异常，清理刚写入的文件避免产生孤儿
            fileStorage.delete(stored.storageKey());
            throw new BusinessException(409, "模板内容更新失败，请刷新后重试");
        }

        // 旧文件不删除：模板内容被替换后仍可回退，清理交给过期文件清理任务。
        // 若后续确认不需要回退，可在这里改为 fileStorage.delete(previousKey)。
        log.info("模板内容已替换, templateId={}, 旧 storageKey 保留={}", id, previousKey != null);

        auditService.success(AuditAction.ADMIN_TEMPLATE_CONTENT_REPLACE, "TEMPLATE", id,
                "替换模板内容，类型=" + stored.extension());
        return DocTemplateVO.from(requireTemplate(id));
    }

    @Override
    public DocTemplateVO updateEnabled(Long id, boolean enabled) {
        DocTemplate existing = requireTemplate(id);
        if (enabled && (existing.getStorageKey() == null || existing.getStorageKey().isBlank())) {
            // 没有内容的模板启用后用户选了也生成不出东西
            throw new BusinessException(409, "该模板尚未上传文件，无法启用");
        }

        DocTemplate update = new DocTemplate();
        update.setId(id);
        update.setEnabled(enabled);
        mapper.updateById(update);

        auditService.success(AuditAction.ADMIN_TEMPLATE_ENABLE, "TEMPLATE", id,
                (enabled ? "启用" : "停用") + "模板 code=" + existing.getCode());
        return DocTemplateVO.from(requireTemplate(id));
    }

    @Override
    public void delete(Long id) {
        DocTemplate existing = requireTemplate(id);
        if (Boolean.TRUE.equals(existing.getEnabled())) {
            // 启用中的模板可能正被使用，要求先停用，避免生成流程中途找不到模板
            throw new BusinessException(409, "请先停用该模板再删除");
        }
        mapper.deleteById(id);
        // 磁盘文件保留，由清理任务统一回收
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

    private DocTemplate requireTemplate(Long id) {
        if (id == null) {
            throw new BusinessException(400, "模板 ID 不能为空");
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
