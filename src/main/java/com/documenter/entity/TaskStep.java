package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务步骤（TODO 3.4「包含步骤、进度」）。
 *
 * <p>用于前端展示「正在做什么」以及失败时定位到具体环节。
 */
@TableName("task_step")
@Data
public class TaskStep implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer stepNo;
    private String stepName;
    private String status;
    private String detail;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private Long durationMs;
}
