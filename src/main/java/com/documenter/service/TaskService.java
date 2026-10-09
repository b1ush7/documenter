package com.documenter.service;

import com.documenter.dto.CreateTaskDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.entity.ProcessingTask;
import com.documenter.vo.PageResult;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;

import java.util.List;

/**
 * 处理任务业务（TODO 3.4）。
 *
 * <p>归属校验一律以传入的 {@code userId} 为准，调用方必须传当前登录用户 ID。
 */
public interface TaskService {

    /**
     * 创建并提交任务。
     *
     * <p>若携带 idempotencyKey 且该键已有任务，直接返回已有任务而不重复创建
     * （TODO 3.4「为创建任务和确认编辑设置幂等机制」）。
     */
    TaskVO submit(Long userId, CreateTaskDTO dto);

    /** 分页查询当前用户的任务。 */
    PageResult<TaskVO> list(Long userId, TaskQueryDTO query);

    /** 任务详情，含步骤列表。 */
    TaskDetailVO detail(Long userId, Long taskId);

    /**
     * 请求取消。
     *
     * <p>对 PENDING 任务直接置为 CANCELLED；对 RUNNING 任务只置取消标记，
     * 由执行循环在步骤边界检查后自行收尾（TODO 3.4「取消」）。
     */
    TaskVO cancel(Long userId, Long taskId);

    /**
     * 重试失败的任务。
     *
     * <p>不修改原任务记录，而是基于它新建一个任务，避免丢失失败现场。
     */
    TaskVO retry(Long userId, Long taskId);

    /** 取任务实体并校验归属，供执行器与其它模块复用。 */
    ProcessingTask requireOwned(Long userId, Long taskId);

    /** 执行期内部使用：不校验归属，直接取任务。 */
    ProcessingTask findById(Long taskId);

    /** 列出某文件的所有任务，用于文档详情页展示处理历史。 */
    List<TaskVO> listByFile(Long userId, Long fileId);
}
