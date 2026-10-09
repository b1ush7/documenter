package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 处理任务（TODO 3.4）。
 *
 * <p>字段与 processing_task 表对齐。{@code idempotencyKey} 与 user_id 构成唯一索引，
 * 用于「创建任务和确认编辑设置幂等机制」，避免重复生成版本或重复扣减额度。
 */
@TableName("processing_task")
@Data
public class ProcessingTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long fileId;
    private Integer inputVersion;
    private Integer outputVersion;
    private String taskType;
    private String status;
    private Integer progress;
    private String currentStep;
    private String instruction;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetry;
    private String idempotencyKey;
    private String requestId;
    /** 是否已请求取消；0/1 映射为 Boolean。 */
    private Boolean cancelRequested;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private Integer timeoutSeconds;
}
