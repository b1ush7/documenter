package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.component.TaskExecutionEngine;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.dto.CreateTaskDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskStatus;
import com.documenter.enums.TaskType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.FileService;
import com.documenter.service.TaskService;
import com.documenter.service.VerificationRequestContextHolder;
import com.documenter.vo.PageResult;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskStepVO;
import com.documenter.vo.TaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 处理任务业务实现（TODO 3.4）。
 *
 * <p>幂等实现要点：唯一索引是 (user_id, idempotency_key)。这里不去「先查后插」——
 * 并发下两个请求都会查不到，然后一个插入成功、另一个撞唯一索引。
 * 因此直接插入并捕获 {@link DuplicateKeyException}，再回查已有记录返回，
 * 让数据库来做幂等判定，逻辑简单且没有竞态。
 */
@Slf4j
@Service
public class TaskServiceImpl implements TaskService {

    private final ProcessingTaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final FileService fileService;
    private final TaskExecutionEngine executionEngine;
    private final TaskExecutorProperties properties;

    /**
     * executionEngine 用 @Lazy 注入：引擎依赖 TaskHandler 注册表，
     * 而处理器在 P1 之后可能需要反向注入 TaskService（例如提交子任务），
     * 延迟解析可以避免这类环在启动期直接失败。
     */
    public TaskServiceImpl(ProcessingTaskMapper taskMapper,
                           TaskStepMapper stepMapper,
                           FileService fileService,
                           @Lazy TaskExecutionEngine executionEngine,
                           TaskExecutorProperties properties) {
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.fileService = fileService;
        this.executionEngine = executionEngine;
        this.properties = properties;
    }

    @Override
    public TaskVO submit(Long userId, CreateTaskDTO dto) {
        TaskType type = parseType(dto.getTaskType());

        ProcessingTask task = new ProcessingTask();
        task.setUserId(userId);
        task.setTaskType(type.name());
        task.setStatus(TaskStatus.PENDING.name());
        task.setProgress(0);
        task.setInstruction(trimToNull(dto.getInstruction()));
        task.setRetryCount(0);
        task.setMaxRetry(properties.getDefaultMaxRetry());
        task.setCancelRequested(Boolean.FALSE);
        task.setIdempotencyKey(trimToNull(dto.getIdempotencyKey()));
        task.setTimeoutSeconds(dto.getTimeoutSeconds() != null
                ? dto.getTimeoutSeconds() : (int) properties.getDefaultTimeout().toSeconds());
        task.setRequestId(VerificationRequestContextHolder.get().requestId());

        if (dto.getFileId() != null) {
            // 归属校验：只能对自己的文件发起任务，避免越权读取他人文档
            FileAsset asset = fileService.requireOwned(userId, dto.getFileId());
            task.setFileId(asset.getId());
            task.setInputVersion(resolveInputVersion(dto, asset));
        } else if (dto.getInputVersion() != null) {
            throw new BusinessException(400, "未指定文件时不能指定输入版本");
        }

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 幂等命中：同一用户同一幂等键，返回已有任务而不是再建一个
            ProcessingTask existing = findByIdempotencyKey(userId, task.getIdempotencyKey());
            if (existing == null) {
                throw e;
            }
            log.info("任务提交命中幂等键, userId={}, key={}, 已存在 taskId={}",
                    userId, task.getIdempotencyKey(), existing.getId());
            return TaskVO.from(existing);
        }

        if (task.getId() == null) {
            throw new BusinessException(500, "创建任务失败，请稍后重试");
        }

        log.info("任务已创建, taskId={}, userId={}, type={}, fileId={}, inputVersion={}",
                task.getId(), userId, type, task.getFileId(), task.getInputVersion());
        executionEngine.submit(task.getId());
        return TaskVO.from(task);
    }

    @Override
    public PageResult<TaskVO> list(Long userId, TaskQueryDTO query) {
        String status = trimToNull(query.getStatus());
        String taskType = trimToNull(query.getTaskType());

        // 与文件列表同理：不依赖 PaginationInnerInterceptor（其构件可能缺失），
        // 用显式 LIMIT/OFFSET 保证分页行为确定
        long offset = (query.getPage() - 1) * query.getSize();

        Long total = taskMapper.selectCount(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getUserId, userId)
                .eq(status != null, ProcessingTask::getStatus, status)
                .eq(taskType != null, ProcessingTask::getTaskType, taskType)
                .eq(query.getFileId() != null, ProcessingTask::getFileId, query.getFileId()));

        List<ProcessingTask> records = taskMapper.selectList(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getUserId, userId)
                .eq(status != null, ProcessingTask::getStatus, status)
                .eq(taskType != null, ProcessingTask::getTaskType, taskType)
                .eq(query.getFileId() != null, ProcessingTask::getFileId, query.getFileId())
                .orderByDesc(ProcessingTask::getCreateTime)
                .last("LIMIT " + query.getSize() + " OFFSET " + offset));

        PageResult<TaskVO> result = new PageResult<>();
        result.setRecords(records.stream().map(TaskVO::from).toList());
        result.setTotal(total == null ? 0L : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public TaskDetailVO detail(Long userId, Long taskId) {
        ProcessingTask task = requireOwned(userId, taskId);
        List<TaskStepVO> steps = stepMapper.selectByTaskId(taskId).stream()
                .map(TaskStepVO::from)
                .toList();
        return TaskDetailVO.of(TaskVO.from(task), steps);
    }

    @Override
    public TaskVO cancel(Long userId, Long taskId) {
        ProcessingTask task = requireOwned(userId, taskId);
        TaskStatus status = TaskStatus.valueOf(task.getStatus());
        if (status.isTerminal()) {
            // 幂等：已完成的任务再次取消直接返回当前状态，不报错
            log.info("任务已处于终态，取消请求被忽略, taskId={}, status={}", taskId, status);
            return TaskVO.from(task);
        }

        if (status == TaskStatus.PENDING) {
            // 还没开始执行：直接置为取消，避免它被调度线程捡起来
            taskMapper.finish(taskId, TaskStatus.CANCELLED.name(), 0, null, null,
                    List.of(TaskStatus.PENDING.name()));
        } else {
            // 正在执行：只打标记，执行循环会在步骤边界自行收尾
            taskMapper.requestCancel(taskId);
        }
        return TaskVO.from(taskMapper.selectById(taskId));
    }

    @Override
    public TaskVO retry(Long userId, Long taskId) {
        ProcessingTask source = requireOwned(userId, taskId);
        TaskStatus status = TaskStatus.valueOf(source.getStatus());
        if (!status.isTerminal() || status == TaskStatus.SUCCEEDED) {
            throw new BusinessException(409, "只有失败或已取消的任务可以重试");
        }
        if (source.getRetryCount() != null && source.getMaxRetry() != null
                && source.getRetryCount() >= source.getMaxRetry()) {
            throw new BusinessException(429, "重试次数已达上限，请调整参数后重新提交");
        }

        // 重试不复用原记录：保留失败现场便于排查，新任务带 parent 关系由 instruction 说明
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setTaskType(source.getTaskType());
        dto.setFileId(source.getFileId());
        dto.setInputVersion(source.getInputVersion());
        dto.setInstruction(source.getInstruction());
        dto.setTimeoutSeconds(source.getTimeoutSeconds());
        // 不使用原幂等键，否则会命中旧任务而无法重试
        dto.setIdempotencyKey(null);

        log.info("重试任务, 源 taskId={}, 新任务由提交产生", taskId);
        return submit(userId, dto);
    }

    @Override
    public ProcessingTask requireOwned(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            throw new BusinessException(400, "任务 ID 不能为空");
        }
        ProcessingTask task = taskMapper.selectById(taskId);
        // 他人任务按「不存在」处理，避免通过状态码枚举任务 ID
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    @Override
    public ProcessingTask findById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    @Override
    public List<TaskVO> listByFile(Long userId, Long fileId) {
        FileAsset asset = fileService.requireOwned(userId, fileId);
        return taskMapper.selectList(Wrappers.<ProcessingTask>lambdaQuery()
                        .eq(ProcessingTask::getFileId, asset.getId())
                        .orderByDesc(ProcessingTask::getCreateTime))
                .stream()
                .map(TaskVO::from)
                .toList();
    }

    /** 不指定输入版本时取当前最新版本；文件尚未产生版本时返回 null。 */
    private Integer resolveInputVersion(CreateTaskDTO dto, FileAsset asset) {
        if (dto.getInputVersion() != null) {
            return dto.getInputVersion();
        }
        Integer latest = asset.getLatestVersion();
        return latest != null && latest > 0 ? latest : null;
    }

    private ProcessingTask findByIdempotencyKey(Long userId, String key) {
        if (key == null) {
            return null;
        }
        return taskMapper.selectOne(Wrappers.<ProcessingTask>lambdaQuery()
                .eq(ProcessingTask::getUserId, userId)
                .eq(ProcessingTask::getIdempotencyKey, key));
    }

    private static TaskType parseType(String value) {
        try {
            return TaskType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的任务类型：" + value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
