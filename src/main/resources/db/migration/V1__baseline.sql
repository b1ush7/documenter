-- =============================================================================
-- Documenter 基线迁移
-- 对应 TODO.md 3.4「引入数据库迁移管理，建立 file_asset、document_version、
-- processing_task、task_step、ai_session、ai_message、verification_record、audit_log 等表」
--
-- 目标库：MariaDB 10.6+ / 11.x（沿用现有 utf8mb4_uca1400_ai_ci 排序规则）
-- 说明：本脚本只建表结构，不写业务数据。Flyway 会按版本号顺序执行。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 用户表：在原有字段基础上补充账号状态与审计字段
-- 原字段：id / phone / username / password / role / balance
-- -----------------------------------------------------------------------------
CREATE TABLE `user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `phone`       VARCHAR(50)  NULL DEFAULT NULL COMMENT '手机号，验证码登录与找回密码使用',
    `username`    VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希，禁止存明文',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN',
    `balance`     DECIMAL(20, 6) NOT NULL DEFAULT 5.000000 COMMENT '余额，是否用于计费待定',
    -- 账号启用状态：TODO 3.1 要求支持禁用用户，TODO 3.2 要求管理员可禁用/恢复
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED / DISABLED',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `last_login_time` DATETIME NULL DEFAULT NULL COMMENT '最近登录时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_username` (`username`) USING BTREE,
    UNIQUE INDEX `uk_user_phone` (`phone`) USING BTREE,
    CONSTRAINT `ck_user_role` CHECK (`role` IN ('USER', 'ADMIN')),
    CONSTRAINT `ck_user_status` CHECK (`status` IN ('ENABLED', 'DISABLED'))
)
    COMMENT ='用户'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- 禁用/恢复用户、按手机号登录都需要稳定查询
CREATE INDEX `idx_user_status` ON `user` (`status`) USING BTREE;

-- -----------------------------------------------------------------------------
-- 文件元数据：TODO 3.3「建立文件元数据：所有者、原名称、存储标识、真实类型、
-- 大小、摘要、来源、创建时间、状态」
-- -----------------------------------------------------------------------------
CREATE TABLE `file_asset`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，内部文件 ID，对外不暴露真实路径',
    `user_id`        BIGINT       NOT NULL COMMENT '所有者，归属校验以此为准，不信任前端传入的用户 ID',
    `original_name`  VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
    `display_name`   VARCHAR(255) NOT NULL COMMENT '展示名，重命名只改这里，不影响存储路径',
    `storage_key`    VARCHAR(512) NOT NULL COMMENT '存储标识，相对存储根目录，禁止直接拼接绝对路径',
    `content_type`   VARCHAR(128) NOT NULL COMMENT '服务端识别出的真实类型，不采用客户端声明',
    `extension`      VARCHAR(32)  NOT NULL COMMENT '规范化后的扩展名，小写不带点',
    `size_bytes`     BIGINT       NOT NULL COMMENT '实际字节数',
    `sha256`         CHAR(64)     NOT NULL COMMENT '内容摘要，用于去重与完整性校验',
    `source`         VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD' COMMENT '来源：UPLOAD / GENERATED / CONVERTED',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT '状态：READY / DELETED / QUARANTINED',
    `latest_version` INT          NOT NULL DEFAULT 0 COMMENT '当前最新版本号，0 表示尚无版本记录',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `delete_time`    DATETIME     NULL DEFAULT NULL COMMENT '软删除时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_file_asset_storage_key` (`storage_key`) USING BTREE,
    INDEX `idx_file_asset_owner` (`user_id`, `status`, `create_time`) USING BTREE,
    INDEX `idx_file_asset_sha256` (`sha256`) USING BTREE,
    CONSTRAINT `ck_file_asset_source` CHECK (`source` IN ('UPLOAD', 'GENERATED', 'CONVERTED')),
    CONSTRAINT `ck_file_asset_status` CHECK (`status` IN ('READY', 'DELETED', 'QUARANTINED'))
)
    COMMENT ='文件元数据'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 文档版本：TODO 3.3「保留原文件；编辑产物新增版本，记录来源版本、操作者、
-- 指令和结果，支持恢复历史版本」
-- -----------------------------------------------------------------------------
CREATE TABLE `document_version`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_id`        BIGINT       NOT NULL COMMENT '所属文件',
    `user_id`        BIGINT       NOT NULL COMMENT '该版本的操作者',
    `version_no`     INT          NOT NULL COMMENT '版本号，从 1 开始递增，1 为原始上传',
    `parent_version` INT          NULL DEFAULT NULL COMMENT '来源版本号；原始版本为 NULL',
    `storage_key`    VARCHAR(512) NOT NULL COMMENT '该版本产物的存储标识',
    `size_bytes`     BIGINT       NOT NULL COMMENT '该版本产物的字节数',
    `sha256`         CHAR(64)     NOT NULL COMMENT '该版本产物的摘要，用于预览与导出一致性校验',
    `instruction`    TEXT         NULL DEFAULT NULL COMMENT '产生该版本的自然语言指令，原始版本为 NULL',
    `result_summary` TEXT         NULL DEFAULT NULL COMMENT '执行结果说明，例如修改了哪些块',
    `change_type`    VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD' COMMENT '变更类型：UPLOAD / AI_EDIT / MANUAL_EDIT / CONVERT / RESTORE',
    `create_time`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，毫秒精度用于并发冲突判定',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_document_version_no` (`file_id`, `version_no`) USING BTREE,
    INDEX `idx_document_version_chain` (`file_id`, `create_time`) USING BTREE,
    CONSTRAINT `ck_document_version_change_type`
        CHECK (`change_type` IN ('UPLOAD', 'AI_EDIT', 'MANUAL_EDIT', 'CONVERT', 'RESTORE'))
)
    COMMENT ='文档版本'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 处理任务：TODO 3.4「建立可持久化任务记录：PENDING / RUNNING / SUCCEEDED /
-- FAILED / CANCELLED，包含步骤、进度、错误、输入输出版本和耗时」
-- 同时承载 TODO 3.4 要求的幂等机制与重试计数。
-- -----------------------------------------------------------------------------
CREATE TABLE `processing_task`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，同时作为对外任务 ID',
    `user_id`          BIGINT       NOT NULL COMMENT '发起人，任务归属校验依据',
    `file_id`          BIGINT       NULL DEFAULT NULL COMMENT '关联文件；纯生成类任务可为空',
    `input_version`    INT          NULL DEFAULT NULL COMMENT '输入版本号',
    `output_version`   INT          NULL DEFAULT NULL COMMENT '输出（新生成）版本号',
    `task_type`        VARCHAR(32)  NOT NULL COMMENT '任务类型：OCR / CONVERT / AI_EDIT / EXPORT / IMAGE_GEN',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED',
    `progress`         TINYINT      NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    `current_step`     VARCHAR(64)  NULL DEFAULT NULL COMMENT '当前步骤名，便于前端展示',
    `instruction`      TEXT         NULL DEFAULT NULL COMMENT '用户原始指令',
    `error_code`       VARCHAR(64)  NULL DEFAULT NULL COMMENT '失败错误码',
    `error_message`    TEXT         NULL DEFAULT NULL COMMENT '失败原因，需脱敏，不得回传模型密钥',
    `retry_count`      INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`        INT          NOT NULL DEFAULT 2 COMMENT '最大重试次数',
    `idempotency_key`  VARCHAR(128) NULL DEFAULT NULL COMMENT '幂等键；同一用户同一键只允许一个活跃任务，避免重复生成版本',
    `request_id`       VARCHAR(64)  NULL DEFAULT NULL COMMENT '请求链路 ID，与日志中的 requestId 对齐',
    `cancel_requested` TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已请求取消',
    `create_time`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `start_time`       DATETIME(3)  NULL DEFAULT NULL COMMENT '实际开始执行时间',
    `finish_time`      DATETIME(3)  NULL DEFAULT NULL COMMENT '实际结束时间',
    `timeout_seconds`  INT          NOT NULL DEFAULT 600 COMMENT '超时秒数',
    PRIMARY KEY (`id`) USING BTREE,
    -- 幂等键在「未结束」状态下唯一；MariaDB 唯一索引允许多行 NULL，因此不传幂等键的任务不受影响
    UNIQUE INDEX `uk_processing_task_idempotency` (`user_id`, `idempotency_key`) USING BTREE,
    INDEX `idx_processing_task_owner` (`user_id`, `create_time`) USING BTREE,
    INDEX `idx_processing_task_status` (`status`, `create_time`) USING BTREE,
    INDEX `idx_processing_task_file` (`file_id`) USING BTREE,
    CONSTRAINT `ck_processing_task_status`
        CHECK (`status` IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT `ck_processing_task_progress` CHECK (`progress` BETWEEN 0 AND 100)
)
    COMMENT ='处理任务'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 任务步骤：TODO 3.4 要求任务「包含步骤」，用于展示进度与失败定位
-- -----------------------------------------------------------------------------
CREATE TABLE `task_step`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`      BIGINT      NOT NULL COMMENT '所属任务',
    `step_no`      INT         NOT NULL COMMENT '步骤序号，从 1 开始',
    `step_name`    VARCHAR(64) NOT NULL COMMENT '步骤名称',
    `status`       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED',
    `detail`       TEXT        NULL DEFAULT NULL COMMENT '步骤细节，例如处理的块范围',
    `error_message` TEXT       NULL DEFAULT NULL COMMENT '该步骤的失败原因',
    `start_time`   DATETIME(3) NULL DEFAULT NULL COMMENT '开始时间',
    `finish_time`  DATETIME(3) NULL DEFAULT NULL COMMENT '结束时间',
    `duration_ms`  BIGINT      NULL DEFAULT NULL COMMENT '耗时毫秒',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_task_step_no` (`task_id`, `step_no`) USING BTREE,
    CONSTRAINT `ck_task_step_status`
        CHECK (`status` IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
)
    COMMENT ='任务步骤'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 验证码记录：TODO 3.1「按接收对象和用途保存摘要、有效期与发送记录，
-- 验证成功后原子消费」以及「限制发送频率、日发送量和校验错误次数」
-- 只存摘要，不存明文验证码。
-- -----------------------------------------------------------------------------
CREATE TABLE `verification_record`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `identifier`     VARCHAR(128) NOT NULL COMMENT '接收对象，例如手机号或邮箱，需归一化后存储',
    `scene`          VARCHAR(32)  NOT NULL COMMENT '用途：REGISTER / LOGIN / RESET_PASSWORD',
    `code_hash`      CHAR(64)     NOT NULL COMMENT '验证码摘要，禁止存明文',
    `send_count`     INT          NOT NULL DEFAULT 1 COMMENT '该记录已重发次数，用于发送频率限制',
    `verify_attempts` INT         NOT NULL DEFAULT 0 COMMENT '已校验错误次数，超出即作废',
    `max_attempts`   INT          NOT NULL DEFAULT 5 COMMENT '允许的最大校验错误次数',
    `status`         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / CONSUMED / EXPIRED / LOCKED',
    `request_ip`     VARCHAR(64)  NULL DEFAULT NULL COMMENT '请求来源 IP，用于风控',
    `request_id`     VARCHAR(64)  NULL DEFAULT NULL COMMENT '请求链路 ID',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `sent_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次发送时间，用于发送频率限制',
    `expire_time`    DATETIME     NOT NULL COMMENT '过期时间',
    `consume_time`   DATETIME     NULL DEFAULT NULL COMMENT '消费时间',
    PRIMARY KEY (`id`) USING BTREE,
    -- 同一接收对象同一用途同时只保留一条记录，便于并发发送时用原子更新去重
    UNIQUE INDEX `uk_verification_record_target` (`identifier`, `scene`) USING BTREE,
    INDEX `idx_verification_record_expire` (`expire_time`) USING BTREE,
    INDEX `idx_verification_record_sent` (`sent_time`) USING BTREE,
    CONSTRAINT `ck_verification_record_scene`
        CHECK (`scene` IN ('REGISTER', 'LOGIN', 'RESET_PASSWORD')),
    CONSTRAINT `ck_verification_record_status`
        CHECK (`status` IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'LOCKED'))
)
    COMMENT ='验证码记录'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- 审计日志：TODO 3.2「记录管理操作」，TODO 3.4「补齐请求 ID 和基础运行日志」
-- -----------------------------------------------------------------------------
CREATE TABLE `audit_log`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NULL DEFAULT NULL COMMENT '操作者，系统操作可为空',
    `username`    VARCHAR(50)  NULL DEFAULT NULL COMMENT '操作者用户名快照，用户改名后仍可追溯',
    `action`      VARCHAR(64)  NOT NULL COMMENT '动作标识，例如 USER_DISABLE / ROLE_CHANGE / FILE_DELETE',
    `target_type` VARCHAR(32)  NULL DEFAULT NULL COMMENT '目标类型：USER / FILE / VERSION / TASK / MODEL_CONFIG',
    `target_id`   VARCHAR(64)  NULL DEFAULT NULL COMMENT '目标标识',
    `detail`      TEXT         NULL DEFAULT NULL COMMENT '操作细节 JSON，禁止写入密码、密钥或文件正文',
    `result`      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT '结果：SUCCESS / FAILURE',
    `request_ip`  VARCHAR(64)  NULL DEFAULT NULL COMMENT '请求来源 IP',
    `request_id`  VARCHAR(64)  NULL DEFAULT NULL COMMENT '请求链路 ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_audit_log_user` (`user_id`, `create_time`) USING BTREE,
    INDEX `idx_audit_log_action` (`action`, `create_time`) USING BTREE,
    INDEX `idx_audit_log_request` (`request_id`) USING BTREE,
    CONSTRAINT `ck_audit_log_result` CHECK (`result` IN ('SUCCESS', 'FAILURE'))
)
    COMMENT ='审计日志'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

-- -----------------------------------------------------------------------------
-- AI 会话与消息：TODO 4.2「建立按用户和文档隔离的对话会话，支持多轮指令」
-- P0 阶段先建表占位，P1 接入模型后使用。
-- -----------------------------------------------------------------------------
CREATE TABLE `ai_session`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '会话所有者，按用户隔离',
    `file_id`     BIGINT       NULL DEFAULT NULL COMMENT '绑定的文件，按文档隔离',
    `title`       VARCHAR(128) NULL DEFAULT NULL COMMENT '会话标题，可由首条指令截断生成',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / ARCHIVED',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_ai_session_owner` (`user_id`, `status`, `update_time`) USING BTREE,
    INDEX `idx_ai_session_file` (`file_id`) USING BTREE,
    CONSTRAINT `ck_ai_session_status` CHECK (`status` IN ('ACTIVE', 'ARCHIVED'))
)
    COMMENT ='AI 会话'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;

CREATE TABLE `ai_message`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`    BIGINT      NOT NULL COMMENT '所属会话',
    `role`          VARCHAR(16) NOT NULL COMMENT '角色：USER / ASSISTANT / TOOL / SYSTEM',
    `content`       MEDIUMTEXT  NULL DEFAULT NULL COMMENT '消息内容',
    `tool_name`     VARCHAR(64) NULL DEFAULT NULL COMMENT '工具调用名，仅 role=TOOL 时使用',
    `tool_args`     TEXT        NULL DEFAULT NULL COMMENT '工具参数 JSON，需脱敏',
    `tool_result`   TEXT        NULL DEFAULT NULL COMMENT '工具返回结果摘要',
    `model_name`    VARCHAR(64) NULL DEFAULT NULL COMMENT '实际调用的模型名',
    `prompt_tokens` INT         NULL DEFAULT NULL COMMENT '输入 token 数，用于计量与成本估算',
    `completion_tokens` INT     NULL DEFAULT NULL COMMENT '输出 token 数',
    `duration_ms`   BIGINT      NULL DEFAULT NULL COMMENT '模型调用耗时毫秒',
    `create_time`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，保证多轮顺序稳定',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_ai_message_session` (`session_id`, `create_time`) USING BTREE,
    CONSTRAINT `ck_ai_message_role` CHECK (`role` IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM'))
)
    COMMENT ='AI 消息'
    COLLATE = 'utf8mb4_uca1400_ai_ci'
    ENGINE = InnoDB;
