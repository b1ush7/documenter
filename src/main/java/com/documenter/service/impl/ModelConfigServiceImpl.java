package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.dto.ModelConfigQueryDTO;
import com.documenter.dto.SaveModelConfigDTO;
import com.documenter.entity.ModelConfig;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ModelConfigMapper;
import com.documenter.service.AuditService;
import com.documenter.service.ModelConfigService;
import com.documenter.util.SecretCipher;
import com.documenter.vo.ModelConfigVO;
import com.documenter.vo.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模型配置管理实现（TODO 3.2）。
 *
 * <p>密钥处理是这里的核心：
 * <ul>
 *   <li>入库前用 {@link SecretCipher} 加密，数据库里只有密文与末尾提示位；</li>
 *   <li>出参统一走 {@link ModelConfigVO}，它<b>没有</b>密文与提示位字段，
 *       从类型上杜绝「直接返回实体」导致的泄露；</li>
 *   <li>审计只记录「是否更新了密钥」，绝不记录密钥内容。</li>
 * </ul>
 *
 * <p>默认配置的互斥由数据库唯一索引 `uk_model_config_default` 保证，
 * 业务层在设置新默认前先清空同用途的旧默认，两步在同一事务内。
 */
@Slf4j
@Service
public class ModelConfigServiceImpl implements ModelConfigService {

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final ModelConfigMapper mapper;
    private final SecretCipher cipher;
    private final AuditService auditService;

    public ModelConfigServiceImpl(ModelConfigMapper mapper, SecretCipher cipher, AuditService auditService) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.auditService = auditService;
    }

    @Override
    public PageResult<ModelConfigVO> list(ModelConfigQueryDTO query) {
        String scope = trimToNull(query.getScope());
        String status = trimToNull(query.getStatus());
        String keyword = trimToNull(query.getKeyword());
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = mapper.selectCount(Wrappers.<ModelConfig>lambdaQuery()
                .eq(scope != null, ModelConfig::getScope, scope)
                .eq(status != null, ModelConfig::getStatus, status)
                .and(keyword != null, w -> w.like(ModelConfig::getCode, keyword)
                        .or().like(ModelConfig::getName, keyword)));

        List<ModelConfig> records = mapper.selectList(Wrappers.<ModelConfig>lambdaQuery()
                .eq(scope != null, ModelConfig::getScope, scope)
                .eq(status != null, ModelConfig::getStatus, status)
                .and(keyword != null, w -> w.like(ModelConfig::getCode, keyword)
                        .or().like(ModelConfig::getName, keyword))
                .orderByAsc(ModelConfig::getScope)
                .orderByDesc(ModelConfig::getIsDefault)
                .orderByAsc(ModelConfig::getCode)
                .last("LIMIT " + query.getSize() + " OFFSET " + offset));

        PageResult<ModelConfigVO> result = new PageResult<>();
        result.setRecords(records.stream().map(c -> ModelConfigVO.from(c, cipher)).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public ModelConfigVO detail(Long id) {
        return ModelConfigVO.from(requireConfig(id), cipher);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVO create(SaveModelConfigDTO dto) {
        ModelConfig config = new ModelConfig();
        config.setCode(dto.getCode().trim());
        config.setName(dto.getName().trim());
        config.setScope(dto.getScope());
        config.setProvider(dto.getProvider().trim());
        config.setBaseUrl(trimToNull(dto.getBaseUrl()));
        config.setModelName(dto.getModelName().trim());
        config.setTimeoutSeconds(dto.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : dto.getTimeoutSeconds());
        config.setMaxOutputTokens(dto.getMaxOutputTokens());
        config.setTemperature(dto.getTemperature());
        config.setRemark(trimToNull(dto.getRemark()));
        // 新建默认停用：避免密钥还没验证就被任务自动选中
        config.setStatus(STATUS_DISABLED);
        config.setIsDefault(Boolean.FALSE);
        config.setDefaultScopeMarker(null);

        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            config.setApiKeyCipher(cipher.encrypt(dto.getApiKey().trim()));
            config.setApiKeyHint(SecretCipher.maskHint(dto.getApiKey().trim()));
        }

        try {
            mapper.insert(config);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "配置标识「" + config.getCode() + "」已存在");
        }
        if (config.getId() == null) {
            throw new BusinessException(500, "创建模型配置失败，请稍后重试");
        }

        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            applyDefault(config.getId(), config.getScope());
        }

        // 审计只记录是否配置了密钥，不记录密钥内容
        auditService.success(AuditAction.ADMIN_MODEL_CONFIG_CREATE, "MODEL_CONFIG", config.getId(),
                "新建模型配置 code=" + config.getCode() + ", scope=" + config.getScope()
                        + ", 是否含密钥=" + (config.getApiKeyCipher() != null));
        return ModelConfigVO.from(requireConfig(config.getId()), cipher);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVO update(Long id, SaveModelConfigDTO dto) {
        ModelConfig existing = requireConfig(id);

        ModelConfig update = new ModelConfig();
        update.setId(id);
        update.setName(dto.getName().trim());
        update.setScope(dto.getScope());
        update.setProvider(dto.getProvider().trim());
        update.setBaseUrl(trimToNull(dto.getBaseUrl()));
        update.setModelName(dto.getModelName().trim());
        update.setTimeoutSeconds(dto.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : dto.getTimeoutSeconds());
        update.setMaxOutputTokens(dto.getMaxOutputTokens());
        update.setTemperature(dto.getTemperature());
        update.setRemark(trimToNull(dto.getRemark()));

        boolean keyChanged = dto.getApiKey() != null && !dto.getApiKey().isBlank();
        if (keyChanged) {
            update.setApiKeyCipher(cipher.encrypt(dto.getApiKey().trim()));
            update.setApiKeyHint(SecretCipher.maskHint(dto.getApiKey().trim()));
        }

        // code 允许修改，但必须保持唯一
        String newCode = dto.getCode().trim();
        boolean codeChanged = !newCode.equals(existing.getCode());
        if (codeChanged) {
            update.setCode(newCode);
        }

        boolean scopeChanged = !dto.getScope().equals(existing.getScope());

        try {
            mapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "配置标识「" + newCode + "」已存在");
        }

        if (scopeChanged) {
            // 用途变了，原默认标记必须清除：否则会与目标用途的默认配置冲突
            mapper.syncDefaultMarker(id, 0);
        }
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            applyDefault(id, dto.getScope());
        }

        auditService.success(AuditAction.ADMIN_MODEL_CONFIG_UPDATE, "MODEL_CONFIG", id,
                "更新模型配置 code=" + newCode + ", 是否更新密钥=" + keyChanged
                        + ", 用途是否变化=" + scopeChanged);
        return ModelConfigVO.from(requireConfig(id), cipher);
    }

    @Override
    public ModelConfigVO updateStatus(Long id, String status) {
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BusinessException(400, "状态只支持 ENABLED、DISABLED");
        }
        ModelConfig existing = requireConfig(id);
        if (STATUS_ENABLED.equals(status)
                && (existing.getApiKeyCipher() == null || existing.getApiKeyCipher().isBlank())) {
            // 启用一个没有密钥的配置，调用模型时必然失败，不如在这里拦住
            throw new BusinessException(409, "该配置尚未设置 API Key，无法启用");
        }

        ModelConfig update = new ModelConfig();
        update.setId(id);
        update.setStatus(status);
        mapper.updateById(update);

        auditService.success(AuditAction.ADMIN_MODEL_CONFIG_ENABLE, "MODEL_CONFIG", id,
                "配置状态改为 " + status + ", code=" + existing.getCode());
        return ModelConfigVO.from(requireConfig(id), cipher);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ModelConfig existing = requireConfig(id);
        if (Boolean.TRUE.equals(existing.getIsDefault())) {
            // 默认配置直接删除会让该用途没有模型可用，要求先指定替代者
            throw new BusinessException(409, "该配置是 " + existing.getScope()
                    + " 用途的默认配置，请先指定其他默认配置再删除");
        }
        mapper.deleteById(id);
        auditService.success(AuditAction.ADMIN_MODEL_CONFIG_DELETE, "MODEL_CONFIG", id,
                "删除模型配置 code=" + existing.getCode());
    }

    @Override
    public String requirePlainApiKey(String code) {
        ModelConfig config = mapper.selectOne(Wrappers.<ModelConfig>lambdaQuery()
                .eq(ModelConfig::getCode, code));
        if (config == null || !STATUS_ENABLED.equals(config.getStatus())) {
            return null;
        }
        if (config.getApiKeyCipher() == null || config.getApiKeyCipher().isBlank()) {
            return null;
        }
        return cipher.decrypt(config.getApiKeyCipher());
    }

    @Override
    public ModelConfigService.ModelRuntimeConfig requireDefaultRuntimeConfig(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new BusinessException(400, "模型用途不能为空");
        }
        ModelConfig config = mapper.selectOne(Wrappers.<ModelConfig>lambdaQuery()
                .eq(ModelConfig::getScope, scope)
                .eq(ModelConfig::getStatus, STATUS_ENABLED)
                .eq(ModelConfig::getIsDefault, true)
                .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException(503,
                    "未配置启用的默认 " + scope + " 模型，请先在管理端完成配置");
        }
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()
                || config.getModelName() == null || config.getModelName().isBlank()
                || config.getApiKeyCipher() == null || config.getApiKeyCipher().isBlank()) {
            throw new BusinessException(503,
                    "默认 " + scope + " 模型配置不完整，需要接口地址、模型名和 API Key");
        }
        return new ModelConfigService.ModelRuntimeConfig(
                config.getCode(), config.getProvider(), config.getBaseUrl().trim(),
                config.getModelName().trim(), cipher.decrypt(config.getApiKeyCipher()),
                config.getTimeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS
                        : config.getTimeoutSeconds(),
                config.getMaxOutputTokens(), config.getTemperature());
    }

    /** 设置默认配置：先清空同用途旧默认，再标记新的。两步同一事务。 */
    private void applyDefault(Long id, String scope) {
        mapper.clearDefaultByScope(scope);
        mapper.syncDefaultMarker(id, 1);
    }

    private ModelConfig requireConfig(Long id) {
        if (id == null) {
            throw new BusinessException(400, "配置 ID 不能为空");
        }
        ModelConfig config = mapper.selectById(id);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }
        return config;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
