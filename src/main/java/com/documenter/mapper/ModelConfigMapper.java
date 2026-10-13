package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.ModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfig> {

    /**
     * 清除某用途下所有配置的「默认」标记。
     *
     * <p>设置新默认值前必须先清旧的：`uk_model_config_default` 是唯一索引，
     * 同一 scope 同时存在两个默认值会直接违反约束。
     * 两步操作放在同一事务内，避免中间态被并发读到。
     */
    @Update("""
            UPDATE model_config
               SET is_default = 0,
                   default_scope_marker = NULL
             WHERE scope = #{scope}
               AND is_default = 1
            """)
    int clearDefaultByScope(@Param("scope") String scope);

    /**
     * 同步默认标记与唯一索引辅助列。
     *
     * <p>`default_scope_marker` 只在 is_default=1 时等于 scope，否则为 NULL，
     * 这样 MariaDB 的唯一索引才能保证「同一 scope 最多一个默认配置」。
     * 两列必须一起更新，分开写会破坏这个不变式。
     */
    @Update("""
            UPDATE model_config
               SET is_default = #{isDefault},
                   default_scope_marker = CASE WHEN #{isDefault} = 1 THEN scope ELSE NULL END
             WHERE id = #{id}
            """)
    int syncDefaultMarker(@Param("id") Long id, @Param("isDefault") int isDefault);
}
