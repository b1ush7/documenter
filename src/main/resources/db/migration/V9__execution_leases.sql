-- 租约使用数据库时间；随机 owner 每次应用启动生成，过期后不允许续回。
ALTER TABLE processing_task
    ADD COLUMN execution_owner VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD INDEX idx_task_expired_lease (status, lease_until, id);

ALTER TABLE ai_message
    ADD COLUMN execution_owner VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD INDEX idx_reply_expired_lease (run_status, lease_until, id);
