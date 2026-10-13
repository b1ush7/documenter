package com.documenter.vo;

import com.documenter.entity.AiSession;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话出参（TODO 3.2）。
 */
@Data
public class AiSessionVO {

    private Long id;
    /** 会话所有者；返回它便于前端确认当前会话归属，本身不是敏感信息。 */
    private Long userId;
    /** 绑定的文件；纯对话为空。 */
    private Long fileId;
    private String title;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static AiSessionVO from(AiSession session) {
        AiSessionVO vo = new AiSessionVO();
        vo.setId(session.getId());
        vo.setUserId(session.getUserId());
        vo.setFileId(session.getFileId());
        vo.setTitle(session.getTitle());
        vo.setStatus(session.getStatus());
        vo.setCreateTime(session.getCreateTime());
        vo.setUpdateTime(session.getUpdateTime());
        return vo;
    }
}
