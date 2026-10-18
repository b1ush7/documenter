-- 保存用户对一次 OCR 结果的逐页人工校正文本。
-- fingerprint 来自识别结果本身；同一文件版本可因语言、区域、页选择不同而产生多条记录。
CREATE TABLE ocr_correction
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    corrections_json MEDIUMTEXT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_ocr_correction_source
        (user_id, file_id, version_no, fingerprint),
    INDEX idx_ocr_correction_file_version (file_id, version_no),
    CONSTRAINT ck_ocr_correction_version_positive CHECK (version_no > 0)
)
    COMMENT = 'OCR 逐页人工校正文本；不覆盖源文件，不创建新版本'
    COLLATE = 'utf8mb4_0900_ai_ci'
    ENGINE = InnoDB;
