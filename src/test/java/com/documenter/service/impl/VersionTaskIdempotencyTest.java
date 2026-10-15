package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务级版本幂等测试（TODO 3.4）。
 *
 * <p>要防的是这一类问题：任务执行到一半失败，重试后<b>又产出内容相同的版本</b>。
 * 用户看到重复版本，额度也可能被重复扣减。
 *
 * <p>这里验证的三件事：
 * <ol>
 *   <li>同一任务的同一次尝试已有版本时，<b>直接复用</b>，不再分配版本号、不再落盘；</li>
 *   <li>并发下「先查后插」失效时，唯一索引拦下的第二条<b>也会退化成复用</b>而不是报错；</li>
 *   <li>非任务产出（上传、手工编辑）不受影响，仍然每次新建版本。</li>
 * </ol>
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败；用英文方法名更稳妥。
 */
class VersionTaskIdempotencyTest {

    private static final Long FILE_ID = 10L;
    private static final Long USER_ID = 1001L;
    private static final Long TASK_ID = 555L;

    private DocumentVersionMapper versionMapper;
    private FileAssetMapper fileAssetMapper;
    private DocumentVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        versionMapper = mock(DocumentVersionMapper.class);
        fileAssetMapper = mock(FileAssetMapper.class);
        FileService fileService = mock(FileService.class);
        FileStorage fileStorage = mock(FileStorage.class);
        service = new DocumentVersionServiceImpl(versionMapper, fileAssetMapper, fileService, fileStorage);
    }

    // ================================================ 同一次尝试复用已有版本

    @Test
    void sameAttemptReusesExistingVersionWithoutAllocatingNewNumber() {
        DocumentVersion existing = version(31L, 3, TASK_ID, 1);
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        DocumentVersion result = service.appendVersion(FILE_ID, USER_ID, content(),
                ChangeType.AI_EDIT, "改一下", "完成", null, TASK_ID, 1);

        // 关键：返回的是已有版本，而不是新建的
        assertSame(existing, result);
        // 关键：不能再去分配版本号，否则会出现「版本号涨了但没有新内容」
        verify(fileAssetMapper, never()).advanceVersion(anyLong(), any());
        // 关键：不能再插一条版本记录
        verify(versionMapper, never()).insert(any(DocumentVersion.class));
    }

    @Test
    void repeatedRetryOfSameAttemptIsIdempotent() {
        DocumentVersion existing = version(31L, 3, TASK_ID, 1);
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        // 模拟处理器被重复调用（超时重入、失败重入）
        service.appendVersion(FILE_ID, USER_ID, content(), ChangeType.AI_EDIT,
                null, null, null, TASK_ID, 1);
        service.appendVersion(FILE_ID, USER_ID, content(), ChangeType.AI_EDIT,
                null, null, null, TASK_ID, 1);

        verify(versionMapper, never()).insert(any(DocumentVersion.class));
        verify(fileAssetMapper, never()).advanceVersion(anyLong(), any());
    }

    @Test
    void differentAttemptMayProduceNewVersion() {
        // 第 1 次尝试没有记录 → 允许产出
        when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(fileAssetMapper.advanceVersion(FILE_ID, null)).thenReturn(1);
        when(fileAssetMapper.selectLatestVersion(FILE_ID)).thenReturn(4);
        when(fileAssetMapper.selectById(FILE_ID)).thenReturn(asset());
        when(fileAssetMapper.updateById(any(FileAsset.class))).thenReturn(1);
        when(versionMapper.insert(any(DocumentVersion.class))).thenReturn(1);

        DocumentVersion result = service.appendVersion(FILE_ID, USER_ID, content(),
                ChangeType.AI_EDIT, null, null, null, TASK_ID, 2);

        // 用户显式重试（新的尝试序号）应当能产出新版本
        assertEquals(4, result.getVersionNo());
        assertEquals(TASK_ID, result.getTaskId());
        assertEquals(2, result.getAttemptNo());
        verify(fileAssetMapper).advanceVersion(FILE_ID, null);
        verify(versionMapper).insert(any(DocumentVersion.class));
    }

    // ================================================ 并发兜底

    @Test
    void duplicateKeyFromConcurrentInsertFallsBackToExistingVersion() {
        DocumentVersion existing = version(31L, 3, TASK_ID, 1);
        // 第一次查询（幂等检查）返回空 —— 模拟并发下两个线程都没查到
        // 第二次查询（唯一索引拦截后回查）返回已有记录
        when(versionMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenReturn(existing);
        when(fileAssetMapper.selectById(FILE_ID)).thenReturn(asset());
        when(fileAssetMapper.advanceVersion(FILE_ID, null)).thenReturn(1);
        when(fileAssetMapper.selectLatestVersion(FILE_ID)).thenReturn(4);
        when(fileAssetMapper.updateById(any(FileAsset.class))).thenReturn(1);
        when(versionMapper.insert(any(DocumentVersion.class)))
                .thenThrow(new DuplicateKeyException("uk_document_version_task_attempt"));

        DocumentVersion result = service.appendVersion(FILE_ID, USER_ID, content(),
                ChangeType.AI_EDIT, null, null, null, TASK_ID, 1);

        // 唯一索引兜底后仍返回已有版本，而不是把异常抛给调用方
        assertSame(existing, result);
    }

    // ================================================ 非任务产出不受影响

    @Test
    void nonTaskVersionStillCreatesNewVersionEachTime() {
        when(fileAssetMapper.selectById(FILE_ID)).thenReturn(asset());
        when(fileAssetMapper.advanceVersion(FILE_ID, null)).thenReturn(1);
        when(fileAssetMapper.selectLatestVersion(FILE_ID)).thenReturn(2);
        when(fileAssetMapper.updateById(any(FileAsset.class))).thenReturn(1);
        when(versionMapper.insert(any(DocumentVersion.class))).thenReturn(1);

        DocumentVersion result = service.appendVersion(FILE_ID, USER_ID, content(),
                ChangeType.MANUAL_EDIT, null, null, null);

        assertEquals(2, result.getVersionNo());
        // 非任务产出没有幂等键，因此 taskId / attemptNo 为空
        assertEquals(null, result.getTaskId());
        assertEquals(null, result.getAttemptNo());
        // 不做幂等查询
        verify(versionMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void taskProducedVersionMustCarryPositiveAttemptNumber() {
        assertThrows(BusinessException.class, () -> service.appendVersion(FILE_ID, USER_ID,
                content(), ChangeType.AI_EDIT, null, null, null, TASK_ID, null));
        assertThrows(BusinessException.class, () -> service.appendVersion(FILE_ID, USER_ID,
                content(), ChangeType.AI_EDIT, null, null, null, TASK_ID, 0));

        // 参数不合法时不应触达任何写操作
        verify(versionMapper, never()).insert(any(DocumentVersion.class));
        verify(fileAssetMapper, never()).advanceVersion(anyLong(), any());
    }

    // ================================================ hasProducedVersion

    @Test
    void hasProducedVersionReflectsAnyAttempt() {
        when(versionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertTrue(service.hasProducedVersion(TASK_ID));
    }

    @Test
    void hasProducedVersionIsFalseWhenNoVersionYet() {
        when(versionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertFalse(service.hasProducedVersion(TASK_ID));
    }

    @Test
    void hasProducedVersionWithNullTaskIsFalseAndSkipsQuery() {
        // 非任务产出无法判断，直接返回 false；不查询数据库
        assertFalse(service.hasProducedVersion(null));
        verify(versionMapper, never()).selectCount(any(Wrapper.class));
    }

    // ================================================ 执行上下文的尝试序号

    @Test
    void executionContextExposesAttemptNumber() {
        ProcessingTaskMapper taskMapper = mock(ProcessingTaskMapper.class);
        TaskStepMapper stepMapper = mock(TaskStepMapper.class);
        ProcessingTask task = new ProcessingTask();
        task.setId(TASK_ID);

        TaskExecutionContextImpl first = new TaskExecutionContextImpl(taskMapper, stepMapper, task, 1);
        TaskExecutionContextImpl second = new TaskExecutionContextImpl(taskMapper, stepMapper, task, 2);

        assertEquals(TASK_ID, first.taskId());
        assertEquals(1, first.attemptNo());
        assertEquals(2, second.attemptNo());
    }

    @Test
    void legacyExecutionContextConstructorDegradesToNonIdempotent() {
        ProcessingTaskMapper taskMapper = mock(ProcessingTaskMapper.class);
        TaskStepMapper stepMapper = mock(TaskStepMapper.class);
        ProcessingTask task = new ProcessingTask();
        task.setId(TASK_ID);

        // 兼容构造器不带尝试序号，返回 0 表示「无尝试上下文」
        // 此时版本写入会退化为非幂等的普通追加 —— 因此真实处理器不应使用该构造器
        @SuppressWarnings("deprecation")
        TaskExecutionContextImpl context = new TaskExecutionContextImpl(taskMapper, stepMapper, task);

        assertEquals(0, context.attemptNo());
        assertEquals(TASK_ID, context.taskId());
    }

    // ================================================ 夹具

    private static FileAsset asset() {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setStatus("READY");
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension("docx");
        asset.setDisplayName("报告.docx");
        asset.setSizeBytes(1024L);
        asset.setLatestVersion(3);
        return asset;
    }

    private static VersionWriter.VersionContent content() {
        return new VersionWriter.VersionContent("2026/10/12/abc.docx", 2048L, "b".repeat(64));
    }

    private static DocumentVersion version(Long id, int versionNo, Long taskId, Integer attemptNo) {
        DocumentVersion version = new DocumentVersion();
        version.setId(id);
        version.setFileId(FILE_ID);
        version.setUserId(USER_ID);
        version.setVersionNo(versionNo);
        version.setTaskId(taskId);
        version.setAttemptNo(attemptNo);
        version.setStorageKey("2026/10/12/abc.docx");
        version.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        version.setExtension("docx");
        version.setSizeBytes(2048L);
        version.setSha256("b".repeat(64));
        version.setChangeType(ChangeType.AI_EDIT.name());
        return version;
    }
}
