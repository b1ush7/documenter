package com.documenter.enums;

/**
 * 文档版本变更类型（TODO 3.3「编辑产物新增版本，记录来源版本、操作者、指令和结果」）。
 *
 * <p>与 document_version.change_type 的 CHECK 约束一一对应。
 */
public enum ChangeType {

    /** 原始上传，version_no = 1，parent_version 为空。 */
    UPLOAD,

    /** AI 编辑产生的版本。 */
    AI_EDIT,

    /** 人工编辑产生的版本。 */
    MANUAL_EDIT,

    /** 格式转换产生的版本，例如 PDF 转 DOCX。 */
    CONVERT,

    /** 从历史版本恢复：不覆盖历史，而是复制该版本内容生成新版本。 */
    RESTORE
}
