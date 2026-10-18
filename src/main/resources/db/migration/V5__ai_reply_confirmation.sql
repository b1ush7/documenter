-- 在助手消息上保存一次模型回复及其待确认计划；旧消息和原有 messages 接口不变。
ALTER TABLE ai_message
    ADD COLUMN request_key VARCHAR(128) NULL,
    ADD COLUMN user_message_id BIGINT NULL,
    ADD COLUMN source_version INT NULL,
    ADD COLUMN output_version INT NULL,
    ADD COLUMN run_status VARCHAR(16) NULL,
    ADD COLUMN plan_status VARCHAR(16) NULL,
    ADD COLUMN result_json MEDIUMTEXT NULL,
    ADD UNIQUE INDEX uk_ai_reply_request (session_id, request_key),
    ADD INDEX idx_ai_reply_running (session_id, run_status),
    ADD CONSTRAINT ck_ai_reply_run_status CHECK (run_status IS NULL OR run_status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT ck_ai_reply_plan_status CHECK (plan_status IS NULL OR plan_status IN ('NONE', 'PENDING', 'CONFIRMED', 'DISCARDED'));
