-- =============================================================================
-- 模型配置与公共模板（TODO 3.2：管理员可管理公共模板和模型配置；模型密钥不得返回前端）
--
-- 目标库：MariaDB 10.6+ / 11.x（沿用 utf8mb4_uca1400_ai_ci 排序规则）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模型配置
--
-- 密钥存储约定：api_key_cipher 存的是 AES-GCM 加密后的密文（Base64），
-- 加密主密钥来自环境变量 MODEL_CONFIG_SECRET，绝不落库。
-- 这样可以避免「数据库被导出/备份泄露 = 模型密钥泄露」。
--
-- api_key_hint 只保存密钥末尾若干位，仅用于前端显示「已配置哪把密钥」，
-- 不足以还原密钥。
-- -----------------------------------------------------------------------------
CREATE TABLE `model_config`
(
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`                VARCHAR(64)  NOT NULL COMMENT '配置唯一标识，例如 default-text',
    `name`                VARCHAR(128) NOT NULL COMMENT '展示名称',
    `scope`               VARCHAR(16)  NOT NULL COMMENT '用途：TEXT / IMAGE / OCR',
    `provider`            VARCHAR(32)  NOT NULL COMMENT '服务商标识，例如 openai-compatible、deepseek',
    `base_url`            VARCHAR(512) NULL DEFAULT NULL COMMENT '接口地址，留空走服务商默认',
    `model_name`          VARCHAR(128) NOT NULL COMMENT '模型名，例如 qwen-plus',
    `api_key_cipher`      VARCHAR(1024) NULL DEFAULT NULL COMMENT 'API Key 密文（AES-GCM，Base64）',
    `api_key_hint`        VARCHAR(16)  NULL DEFAULT NULL COMMENT '密钥末尾提示位，供前端展示',
    `timeout_seconds`     INT          NOT NULL DEFAULT 60 COMMENT '调用超时秒数',
    `max_output_tokens`   INT          NULL DEFAULT NULL COMMENT '输出长度上限',
    `temperature`         DECIMAL(3, 2) NULL DEFAULT NULL COMMENT '采样温度，0.00-2.00',
    `status`              VARCHAR(16)  NOT NULL DEFAULT 'DISABLED' COMMENT '状态：ENABLED / DISABLED',
    `is_default`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为同 scope 下的默认配置',
    `remark`              VARCHAR(255) NULL DEFAULT NULL COMMENT '备注',
    -- 同一 scope 下只允许一个默认配置。MariaDB 唯一索引允许多行 NULL，
    -- 因此用「非默认时存 NULL」的技巧：is_default=0 时该列为 NULL，不参与唯一性判断。
    `default_scope_marker` VARCHAR(16) NULL DEFAULT NULL COMMENT '内部使用：is_default=1 时等于 scope，否则为 NULL',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_model_config_code` (`code`) USING BTREE,
    UNIQUE INDEX `uk_model_config_default` (`default_scope_marker`) USING BTREE,
    INDEX `idx_model_config_scope` (`scope`, `status`) USING BTREE,
    CONSTRAINT `ck_model_config_scope` CHECK (`scope` IN ('TEXT', 'IMAGE', 'OCR')),
    CONSTRAINT `ck_model_config_status` CHECK (`status` IN ('ENABLED', 'DISABLED')),
    CONSTRAINT `ck_model_config_temperature`
        CHECK (`temperature` IS NULL OR (`temperature` >= 0 AND `temperature` <= 2))
)
    COMMENT ='模型配置'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 公共模板
--
-- 版本策略：单行保存当前版本，version_no 在每次更新内容时递增。
-- 不复用 document_version 那种「多行链式版本」，因为模板体量小、
-- 且使用方只需要「当前生效的那一份」；历史回溯需求出现时再引入模板版本表。
--
-- 文件内容复用 file_storage 组件，与用户文件同样只保存 storage_key，不暴露给前端。
-- -----------------------------------------------------------------------------
CREATE TABLE `doc_template`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`            VARCHAR(64)  NOT NULL COMMENT '模板唯一标识',
    `name`            VARCHAR(128) NOT NULL COMMENT '展示名称',
    `category`        VARCHAR(32)  NULL DEFAULT NULL COMMENT '分类，例如 公文、报告、简历',
    `description`     VARCHAR(500) NULL DEFAULT NULL COMMENT '用途说明',
    `storage_key`     VARCHAR(512) NULL DEFAULT NULL COMMENT '模板文件存储标识，不返回前端',
    `original_name`   VARCHAR(255) NULL DEFAULT NULL COMMENT '上传时的文件名',
    `content_type`    VARCHAR(128) NULL DEFAULT NULL COMMENT '文件类型',
    `extension`       VARCHAR(16)  NULL DEFAULT NULL COMMENT '扩展名',
    `size_bytes`      BIGINT       NULL DEFAULT NULL COMMENT '文件字节数',
    `version_no`      INT          NOT NULL DEFAULT 0 COMMENT '内容版本号，每次更新内容 +1',
    `variables`       TEXT         NULL DEFAULT NULL COMMENT '变量定义 JSON 数组，例如 [{"name":"title","label":"标题","required":true}]',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用：1 启用 0 停用',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_doc_template_code` (`code`) USING BTREE,
    INDEX `idx_doc_template_enabled` (`enabled`, `category`) USING BTREE
)
    COMMENT ='公共模板'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;
