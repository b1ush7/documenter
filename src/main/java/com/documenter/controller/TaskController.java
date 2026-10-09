package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.CreateTaskDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.service.TaskService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.PageResult;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口（TODO 3.4）。
 *
 * <p>状态查询走普通 REST 轮询。TODO 3.4 提到「确定是否通过 SSE 推送进度」，
 * 该决策尚未作最终选择，因此先提供稳定可用的轮询接口；
 * 后续如需 SSE，可在同一服务上增加推送端点而不影响已有接口。
 */
@RestController
@RequestMapping("/task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建并提交任务。
     *
     * <p>若请求携带 idempotencyKey 且该键已有任务，返回已有任务而不是重复创建，
     * 前端可安全重试提交。
     */
    @PostMapping
    public ApiResponse<TaskVO> submit(@Valid @RequestBody CreateTaskDTO dto) {
        return ApiResponse.success("任务已提交", taskService.submit(SecurityUtil.requireUserId(), dto));
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<TaskVO>> list(@Valid @ModelAttribute TaskQueryDTO query) {
        return ApiResponse.success(taskService.list(SecurityUtil.requireUserId(), query));
    }

    /** 任务详情，含步骤列表，用于失败定位。 */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskDetailVO> detail(@PathVariable Long taskId) {
        return ApiResponse.success(taskService.detail(SecurityUtil.requireUserId(), taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskVO> cancel(@PathVariable Long taskId) {
        return ApiResponse.success("取消请求已受理", taskService.cancel(SecurityUtil.requireUserId(), taskId));
    }

    /** 只允许重试失败或已取消的任务；成功任务会被拒绝。 */
    @PostMapping("/{taskId}/retry")
    public ApiResponse<TaskVO> retry(@PathVariable Long taskId) {
        return ApiResponse.success("重试任务已提交", taskService.retry(SecurityUtil.requireUserId(), taskId));
    }

    /** 某文件的处理历史。 */
    @GetMapping("/by-file/{fileId}")
    public ApiResponse<List<TaskVO>> listByFile(@PathVariable Long fileId) {
        return ApiResponse.success(taskService.listByFile(SecurityUtil.requireUserId(), fileId));
    }
}
