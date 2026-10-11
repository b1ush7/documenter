package com.documenter.enums;

/**
 * 任务类型（TODO 3.4）。
 *
 * <p>取值必须与 processing_task.task_type 的 CHECK 约束保持一致，
 * 增减类型时要同步修改迁移脚本与 {@code TaskHandler} 的注册表。
 */
public enum TaskType {

    /** PDF / 图片的 OCR 识别（P2）。 */
    OCR,

    /** 格式转换，例如 PDF 转 DOCX（P2）。 */
    CONVERT,

    /** AI 文档编辑（P1）。 */
    AI_EDIT,

    /** 导出，例如 DOCX 转 PDF（P1）。 */
    EXPORT,

    /** 图片生成与编辑（P2）。 */
    IMAGE_GEN
}
