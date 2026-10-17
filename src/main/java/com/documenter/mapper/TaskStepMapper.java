package com.documenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.documenter.entity.TaskStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStep> {

    /** 重试沿用同一任务，步骤号必须接续已有记录以满足唯一索引。 */
    @Select("SELECT COALESCE(MAX(step_no), 0) FROM task_step WHERE task_id = #{taskId}")
    int selectMaxStepNo(@Param("taskId") Long taskId);

    /** 按步骤序号列出某任务的全部步骤（TODO 3.4）。 */
    @Select("SELECT * FROM task_step WHERE task_id = #{taskId} ORDER BY step_no")
    List<TaskStep> selectByTaskId(@Param("taskId") Long taskId);
}
