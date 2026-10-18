-- 为每个版本保存格式；此前同一文件的各版本格式一致，按文件元数据回填。
USE documenter;
ALTER TABLE document_version
    ADD COLUMN content_type VARCHAR(128) NULL AFTER storage_key,
    ADD COLUMN extension VARCHAR(16) NULL AFTER content_type;

UPDATE document_version v
JOIN file_asset f ON f.id = v.file_id
SET v.content_type = f.content_type,
    v.extension = f.extension;

ALTER TABLE document_version
    MODIFY COLUMN content_type VARCHAR(128) NOT NULL,
    MODIFY COLUMN extension VARCHAR(16) NOT NULL;

-- 当前文件记录指向最新版本，历史版本保留独立的内容与格式。
UPDATE file_asset f
JOIN document_version v ON v.file_id = f.id AND v.version_no = f.latest_version
SET f.storage_key = v.storage_key,
    f.size_bytes = v.size_bytes,
    f.sha256 = v.sha256,
    f.content_type = v.content_type,
    f.extension = v.extension;
