-- Template history versions only the file. Variables/category/name/enabled remain on doc_template.
CREATE TABLE doc_template_version
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NULL,
    content_type VARCHAR(128) NULL,
    extension VARCHAR(16) NULL,
    size_bytes BIGINT NULL,
    change_type VARCHAR(16) NOT NULL,
    restored_from_version INT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_template_version (template_id, version_no),
    CONSTRAINT ck_template_version_positive CHECK (version_no > 0),
    CONSTRAINT ck_template_version_change CHECK (change_type IN ('BASELINE', 'CREATE', 'REPLACE', 'RESTORE')),
    CONSTRAINT ck_template_version_restore CHECK (
        (change_type = 'RESTORE' AND restored_from_version IS NOT NULL AND restored_from_version > 0
            AND restored_from_version < version_no)
        OR (change_type <> 'RESTORE' AND restored_from_version IS NULL)
    )
)
    COMMENT = '公共模板文件历史；不包含变量、分类及启用状态'
    COLLATE = 'utf8mb4_0900_ai_ci'
    ENGINE = InnoDB;

-- Only the current indexed file is known. Older unindexed disk files cannot be reconstructed safely.
-- Keep the original version number; do not invent versions 1..N-1 or a historical creation timestamp.
INSERT INTO doc_template_version
    (template_id, version_no, storage_key, original_name, content_type, extension, size_bytes, change_type)
SELECT id, version_no, storage_key, original_name, content_type, extension, size_bytes, 'BASELINE'
  FROM doc_template
 WHERE version_no > 0 AND storage_key IS NOT NULL AND TRIM(storage_key) <> '';
