package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.ProcessingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProcessingTaskMapper extends BaseMapper<ProcessingTask> {

    /**
     * 原子占用任务：仅当任务仍处于 PENDING 时置为 RUNNING。
     *
     * <p>用条件更新而不是「先查再改」：两个执行线程可能同时拿到同一个任务，
     * 条件更新保证只有一个能把状态推进，另一个影响行数为 0 并主动放弃。
     *
     * @return 影响行数；0 表示任务已被其他线程接管或已进入终态
     */
    @Update("""
            UPDATE processing_task
               SET status = 'RUNNING',
                   start_time = NOW(3),
                   current_step = #{stepName}
             WHERE id = #{taskId}
               AND status = 'PENDING'
            """)
    int markRunning(@Param("taskId") Long taskId, @Param("stepName") String stepName);

    /**
     * 把状态推进到终态，同样要求当前状态在允许的来源集合内。
     *
     * @param fromStatuses 允许的来源状态
     * @return 影响行数；0 表示状态已被其他操作改变
     */
    @Update("""
            <script>
            UPDATE processing_task
               SET status = #{target},
                   progress = #{progress},
                   finish_time = NOW(3),
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   current_step = NULL
             WHERE id = #{taskId}
               AND status IN
               <foreach collection="fromStatuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int finish(@Param("taskId") Long taskId,
               @Param("target") String target,
               @Param("progress") int progress,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage,
               @Param("fromStatuses") List<String> fromStatuses);

    /** 请求取消：只对未进入终态的任务生效。 */
    @Update("""
            UPDATE processing_task
               SET cancel_requested = 1
             WHERE id = #{taskId}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int requestCancel(@Param("taskId") Long taskId);

    /** 查询是否已被请求取消。 */
    @Select("SELECT cancel_requested FROM processing_task WHERE id = #{taskId}")
    Boolean selectCancelRequested(@Param("taskId") Long taskId);

    /**
     * 应用重启恢复（TODO 3.4「重启恢复」）。
     *
     * <p>进程被杀时停留在 RUNNING 的任务不会有线程再推进它，
     * 必须启动时批量标记为失败，否则前端会永远看到「运行中」。
     *
     * @return 被修正的任务数
     */
    @Update("""
            UPDATE processing_task
               SET status = 'FAILED',
                   error_code = 'RESTARTED',
                   error_message = '服务重启导致任务中断',
                   finish_time = NOW(3),
                   current_step = NULL
             WHERE status = 'RUNNING'
            """)
    int failInterruptedTasks();

    /** 取出重启后需要重新入队等待的任务。 */
    @Select("SELECT * FROM processing_task WHERE status = 'PENDING' ORDER BY create_time")
    List<ProcessingTask> selectPendingTasks();
}
