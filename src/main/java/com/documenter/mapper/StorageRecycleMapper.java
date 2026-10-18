package com.documenter.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StorageRecycleMapper {
    String ELIGIBLE = """
            a.status = 'DELETED' AND a.storage_purged_time IS NULL AND a.delete_time &lt; #{cutoff}
            AND NOT EXISTS (SELECT 1 FROM processing_task t WHERE t.file_id = a.id
                            AND t.status IN ('PENDING', 'RUNNING'))
            AND NOT EXISTS (SELECT 1 FROM ai_session s JOIN ai_message m ON m.session_id = s.id
                            WHERE s.user_id = a.user_id AND (m.run_status = 'RUNNING' OR m.plan_status = 'PENDING'))
            """;

    @Select("<script>SELECT a.id FROM file_asset a WHERE " + ELIGIBLE
            + " ORDER BY a.delete_time, a.id LIMIT #{limit}</script>")
    List<Long> eligibleFiles(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Update("<script>UPDATE file_asset a SET storage_purged_time = #{now} WHERE " + ELIGIBLE
            + " AND a.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int markPurged(@Param("ids") List<Long> ids, @Param("cutoff") LocalDateTime cutoff,
                   @Param("now") LocalDateTime now);

    /** 所有模板历史始终受保护，包含已删除模板的历史；悬空版本索引也保守保留。 */
    @Select("""
            <script>
            SELECT (
                EXISTS(SELECT 1 FROM file_asset a WHERE a.storage_key = #{key} AND a.storage_purged_time IS NULL
                  <if test='ignored.size() > 0'>AND a.id NOT IN
                    <foreach collection='ignored' item='id' open='(' separator=',' close=')'>#{id}</foreach>
                  </if>)
                OR EXISTS(SELECT 1 FROM document_version v LEFT JOIN file_asset a ON a.id = v.file_id
                  WHERE v.storage_key = #{key} AND (a.id IS NULL OR (a.storage_purged_time IS NULL
                  <if test='ignored.size() > 0'>AND a.id NOT IN
                    <foreach collection='ignored' item='id' open='(' separator=',' close=')'>#{id}</foreach>
                  </if>)))
                OR EXISTS(SELECT 1 FROM doc_template WHERE storage_key = #{key})
                OR EXISTS(SELECT 1 FROM doc_template_version WHERE storage_key = #{key})
            )
            </script>
            """)
    Integer protectedReference(@Param("key") String key, @Param("ignored") List<Long> ignored);
}
