package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /** 按时间正序列出某会话的全部消息。 */
    @Select("SELECT * FROM ai_message WHERE session_id = #{sessionId} ORDER BY create_time, id")
    List<AiMessage> selectBySession(@Param("sessionId") Long sessionId);
}
