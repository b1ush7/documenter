-- =============================================================================
-- 任务级版本幂等（TODO 3.4）
--
-- 背景：任务失败后会重试，而重试会重新调用处理器。处理器里的
-- versionWriter.appendVersion 每次都会「分配新版本号 + 插入版本记录」，
-- 因此一次「已经产出版本、但在后续步骤失败」的执行，重试后会再产出
-- 一个内容相同的版本 —— 用户看到重复版本，额度也可能被重复扣减。
--
-- 解法：给每次执行尝试一个稳定标识 (task_id, attempt_no)，并让它成为
-- 版本记录上的唯一键。同一个任务的同一次尝试最多产生一个版本。
--
-- 为什么用 (task_id, attempt_no) 而不是只用 task_id：
-- 用户显式重试时应该允许产出新版本（那是新的执行意图），
-- 但同一次尝试内部的失败重入、超时重入不应重复产出。
--
-- 说明：本脚本依赖 Flyway 的迁移版本表保证只执行一次，
-- ALTER TABLE ADD COLUMN 本身不可重复执行。
-- =============================================================================

-- 归属任务；手工编辑、上传等非任务产出的版本该列为 NULL。
-- MariaDB 唯一索引允许多行 NULL，因此不影响非任务版本的写入。
ALTER TABLE document_version
    ADD COLUMN task_id BIGINT NULL DEFAULT NULL
        COMMENT '产出该版本的任务 ID；非任务产出为 NULL' AFTER change_type;

-- 任务内的执行尝试序号：第一次执行为 1。非任务产出为 NULL。
ALTER TABLE document_version
    ADD COLUMN attempt_no INT NULL DEFAULT NULL
        COMMENT '任务内的执行尝试序号，从 1 开始；非任务产出为 NULL' AFTER task_id;

-- 同一个任务的同一次尝试最多产出一个版本。
-- 这条唯一索引是幂等性的最终保证：即使应用层的「先查后插」在并发下失效，
-- 数据库也会拒绝第二条记录，从而不会出现重复版本。
ALTER TABLE document_version
    ADD UNIQUE INDEX uk_document_version_task_attempt (task_id, attempt_no) USING BTREE;

-- 按任务查询产出的版本，供「重试前检查是否已产出」与管理员排查使用。
ALTER TABLE document_version
    ADD INDEX idx_document_version_task (task_id) USING BTREE;
