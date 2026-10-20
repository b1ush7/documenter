package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    @Select("SELECT * FROM ai_message WHERE session_id = #{sessionId} AND request_key = #{requestKey}")
    AiMessage selectByRequestKey(@Param("sessionId") Long sessionId, @Param("requestKey") String requestKey);

    @Select("SELECT * FROM ai_message WHERE session_id = #{sessionId} AND run_status = 'RUNNING' LIMIT 1")
    AiMessage selectRunningReply(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT * FROM ai_message WHERE session_id = #{sessionId}
              AND (role = 'USER' OR (role = 'ASSISTANT' AND run_status = 'COMPLETED'))
            ORDER BY id DESC LIMIT 100
            """)
    List<AiMessage> selectRecentConversation(@Param("sessionId") Long sessionId);

    @Update("""
            UPDATE ai_message SET run_status = 'FAILED', plan_status = 'NONE',
                content = '服务重启导致本次回复中断，请使用新的幂等键重新发送'
            WHERE run_status = 'RUNNING' AND execution_owner IS NULL
            """)
    int failInterruptedReplies();

    /** 按时间正序列出某会话的全部消息。 */
    @Select("SELECT * FROM ai_message WHERE session_id = #{sessionId} ORDER BY create_time, id")
    List<AiMessage> selectBySession(@Param("sessionId") Long sessionId);
}
