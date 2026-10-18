package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {
    @Select("SELECT * FROM ai_session WHERE id = #{sessionId} AND user_id = #{userId} FOR UPDATE")
    AiSession selectOwnedForUpdate(@Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
