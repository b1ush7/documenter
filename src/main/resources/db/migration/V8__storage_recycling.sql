-- 回收只删除过期软删除文件的物理内容，不删除审计元数据或修剪活跃文件历史。
ALTER TABLE file_asset
    ADD COLUMN storage_purged_time DATETIME(3) NULL COMMENT '正式回收墓碑，非空后禁止访问内容',
    ADD INDEX idx_file_asset_recycle (status, storage_purged_time, delete_time);
CREATE INDEX idx_document_version_storage ON document_version (storage_key);
CREATE INDEX idx_doc_template_storage ON doc_template (storage_key);
CREATE INDEX idx_doc_template_version_storage ON doc_template_version (storage_key);
