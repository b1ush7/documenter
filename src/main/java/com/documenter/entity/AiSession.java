package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话（TODO 3.2：对话模块需执行服务端归属校验）。
 *
 * <p>归属由 {@code userId} 唯一确定，与 {@link AiMessage} 一起构成
 * 「按用户隔离、可选按文档隔离」的会话模型。子表不冗余 userId，
 * 归属一律通过 {@code sessionId} 回溯到本表判断，避免两处归属数据不一致。
 */
@TableName("ai_session")
@Data
public class AiSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 会话所有者。归属校验以此字段为唯一依据。 */
    private Long userId;
    /** 绑定的文件；纯对话可以为空。 */
    private Long fileId;
    private String title;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否活跃（仅业务判断，不入库）。 */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
