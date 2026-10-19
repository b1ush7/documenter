package com.documenter.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅用于显式启用的执行租约；所有有效期比较采用 MariaDB 时间。 */
@Mapper
public interface ExecutionLeaseMapper {
    @Update("""
            UPDATE processing_task SET status='RUNNING', start_time=NOW(3), current_step='启动',
                execution_owner=#{owner}, lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE id=#{id} AND status='PENDING'
            """)
    int claimTask(@Param("id") long id, @Param("owner") String owner, @Param("micros") long micros);

    @Update("""
            UPDATE ai_message SET execution_owner=#{owner}, lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE id=#{id} AND run_status='RUNNING' AND execution_owner IS NULL
            """)
    int claimReply(@Param("id") long id, @Param("owner") String owner, @Param("micros") long micros);

    @Select("""
            <script>SELECT id FROM processing_task WHERE execution_owner=#{owner} AND status='RUNNING'
            AND lease_until &gt; NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id FOR UPDATE SKIP LOCKED</script>
            """)
    List<Long> lockRenewableTasks(@Param("ids") List<Long> ids, @Param("owner") String owner);

    @Select("""
            <script>SELECT id FROM ai_message WHERE execution_owner=#{owner} AND run_status='RUNNING'
            AND lease_until &gt; NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id FOR UPDATE SKIP LOCKED</script>
            """)
    List<Long> lockRenewableReplies(@Param("ids") List<Long> ids, @Param("owner") String owner);

    @Update("""
            <script>UPDATE processing_task SET lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE execution_owner=#{owner} AND status='RUNNING' AND lease_until &gt; NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    int renewTasks(@Param("ids") List<Long> ids, @Param("owner") String owner, @Param("micros") long micros);

    @Update("""
            <script>UPDATE ai_message SET lease_until=TIMESTAMPADD(MICROSECOND,#{micros},NOW(3))
            WHERE execution_owner=#{owner} AND run_status='RUNNING' AND lease_until &gt; NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    int renewReplies(@Param("ids") List<Long> ids, @Param("owner") String owner, @Param("micros") long micros);

    @Select("""
            SELECT id FROM processing_task WHERE id=#{id} AND status='RUNNING'
                AND execution_owner=#{owner} AND lease_until>NOW(3) FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockTask(@Param("id") long id, @Param("owner") String owner);

    @Select("SELECT id FROM processing_task WHERE id=#{id} AND execution_owner=#{owner} AND lease_until>NOW(3) FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockTaskOwnership(@Param("id") long id, @Param("owner") String owner);

    @Select("""
            SELECT id FROM processing_task WHERE id=#{id} AND user_id=#{user} AND file_id=#{file}
                AND status='RUNNING' AND cancel_requested=0 AND execution_owner=#{owner}
                AND lease_until>NOW(3) FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockTaskVersion(@Param("id") long id, @Param("owner") String owner,
                         @Param("user") long user, @Param("file") long file);

    @Select("""
            SELECT COUNT(*) FROM processing_task WHERE id=#{id} AND status='RUNNING'
                AND cancel_requested=0 AND execution_owner=#{owner} AND lease_until>NOW(3)
            """)
    int activeTask(@Param("id") long id, @Param("owner") String owner);

    @Select("""
            SELECT id FROM ai_message WHERE id=#{id} AND run_status='RUNNING'
                AND execution_owner=#{owner} AND lease_until>NOW(3) FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockReply(@Param("id") long id, @Param("owner") String owner);

    @Select("SELECT id FROM ai_message WHERE id=#{id} AND execution_owner=#{owner} AND lease_until>NOW(3) FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockReplyOwnership(@Param("id") long id, @Param("owner") String owner);

    @Select("""
            SELECT id FROM processing_task WHERE status='RUNNING' AND execution_owner IS NOT NULL
                AND lease_until<=NOW(3) ORDER BY id LIMIT #{batch} FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockExpiredTasks(@Param("batch") int batch);

    @Update("""
            <script>UPDATE task_step SET status='FAILED', error_message='执行租约过期，步骤中断', finish_time=NOW(3),
                duration_ms=CASE WHEN start_time IS NULL THEN NULL
                    ELSE GREATEST(0,TIMESTAMPDIFF(MICROSECOND,start_time,NOW(3)) DIV 1000) END
            WHERE status='RUNNING' AND task_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    int failSteps(@Param("ids") List<Long> ids);

    @Update("""
            <script>UPDATE processing_task SET status='FAILED', error_code='LEASE_EXPIRED',
                error_message='执行实例失联或执行租约过期，请重试', cancel_requested=1, finish_time=NOW(3), current_step=NULL
            WHERE status='RUNNING' AND lease_until &lt;= NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    int failTasks(@Param("ids") List<Long> ids);

    @Select("""
            SELECT id FROM ai_message WHERE run_status='RUNNING' AND execution_owner IS NOT NULL
                AND lease_until<=NOW(3) ORDER BY id LIMIT #{batch} FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockExpiredReplies(@Param("batch") int batch);

    @Update("""
            <script>UPDATE ai_message SET run_status='FAILED', plan_status='NONE', result_json=NULL,
                content='执行租约过期，本次回复已中断，请使用新的幂等键重新发送'
            WHERE run_status='RUNNING' AND lease_until &lt;= NOW(3) AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
            """)
    int failReplies(@Param("ids") List<Long> ids);

    @Select("SELECT id FROM processing_task WHERE status='PENDING' ORDER BY create_time,id LIMIT #{batch}")
    List<Long> pendingTasks(@Param("batch") int batch);
}
