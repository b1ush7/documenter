package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.TaskStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStep> {

    /** 与任务恢复处于同一事务，避免任务失败后其步骤仍永久显示运行中。 */
    @Update("""
            UPDATE task_step s JOIN processing_task t ON t.id = s.task_id
               SET s.status = 'FAILED',
                   s.error_message = '服务重启导致步骤中断',
                   s.finish_time = NOW(3),
                   s.duration_ms = CASE WHEN s.start_time IS NULL THEN NULL
                       ELSE GREATEST(0, TIMESTAMPDIFF(MICROSECOND, s.start_time, NOW(3)) DIV 1000) END
             WHERE t.status = 'RUNNING' AND t.execution_owner IS NULL AND s.status = 'RUNNING'
            """)
    int failInterruptedSteps();

    /** 重试沿用同一任务，步骤号必须接续已有记录以满足唯一索引。 */
    @Select("SELECT COALESCE(MAX(step_no), 0) FROM task_step WHERE task_id = #{taskId}")
    int selectMaxStepNo(@Param("taskId") Long taskId);

    /** 按步骤序号列出某任务的全部步骤（TODO 3.4）。 */
    @Select("SELECT * FROM task_step WHERE task_id = #{taskId} ORDER BY step_no")
    List<TaskStep> selectByTaskId(@Param("taskId") Long taskId);
}
