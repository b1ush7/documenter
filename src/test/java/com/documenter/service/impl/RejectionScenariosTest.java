package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.documenter.component.StorageWriter;
import com.documenter.component.TaskExecutionEngine;
import com.documenter.component.UploadContentInspector;
import com.documenter.configuration.StorageProperties;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.configuration.VerificationCodeProperties;
import com.documenter.dto.CreateTaskDTO;
import com.documenter.entity.ProcessingTask;
import com.documenter.entity.User;
import com.documenter.enums.VerificationScene;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.UserService;
import com.documenter.service.VerificationCodeSender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拒绝路径校验（TODO 8 第 166 行：上传限制、验证码重放、任务重复执行）。
 *
 * <p>本类专门验证「应当被拒绝时确实拒绝」。功能正常路径通常不会出安全问题，
 * 越权与滥用几乎都发生在边界与异常输入上，而这类分支在手工测试中极易被漏掉。
 *
 * <p>局限：Redis 相关的 Lua 脚本语义在这里用 Mockito 打桩替代，
 * 因此本类验证的是「业务层是否按脚本返回值做出正确决策」，
 * 而不是「Lua 脚本本身是否正确」——后者必须在真 Redis 上验证。
 *
 * <p>注意：本类不使用 {@code @DisplayName}。中文全角标点在写入过程中可能被
 * 破坏成半角引号从而截断字符串字面量，导致编译失败；用英文方法名标识用例
 * 更稳妥，中文说明放在注释里。
 */
class RejectionScenariosTest {

    /**
     * 注册实体元数据。
     *
     * <p>Mockito 提供的 mapper 未经过 MyBatis-Plus 初始化流程，
     * lambda 查询拿不到列名缓存。这里手动初始化，便于断言真实 SQL 片段。
     */
    @BeforeAll
    static void registerEntityMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("rejection-test");
        TableInfoHelper.initTableInfo(assistant, ProcessingTask.class);
    }

    // ==================================================== 上传限制

    @Nested
    class UploadLimits {

        private StorageWriter storageWriter;
        private StorageProperties properties;
        private UploadContentInspector inspector;
        private FileStorage fileStorage;

        @BeforeEach
        void setUp() {
            fileStorage = mock(FileStorage.class);
            properties = new StorageProperties();
            inspector = mock(UploadContentInspector.class);
            storageWriter = new StorageWriter(fileStorage, properties, inspector);
        }

        @Test
        void emptyFileRejected() {
            MockMultipartFile empty = new MockMultipartFile("file", "a.docx", null, new byte[0]);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> storageWriter.store(empty));
            assertEquals(400, error.getCode());
        }

        @Test
        void nullFileRejected() {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> storageWriter.store(null));
            assertEquals(400, error.getCode());
        }

        @Test
        void oversizedFileRejectedWithoutStoring() {
            // 把上限压到很小，避免测试里真的造几十 MB 数据
            properties.getUpload().setMaxSizeBytes(16);
            MockMultipartFile big = new MockMultipartFile("file", "a.pdf", null,
                    "%PDF-1.7 padded padding padding".getBytes());

            BusinessException error = assertThrows(BusinessException.class,
                    () -> storageWriter.store(big));

            assertEquals(413, error.getCode());
            verify(fileStorage, never()).store(any(java.io.InputStream.class), anyString());
        }

        @Test
        void executableDisguisedAsDocxRejected() {
            // 内容是 Windows PE 头，文件名伪装成 docx；类型识别只看内容
            byte[] peHeader = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00};
            MockMultipartFile disguised = new MockMultipartFile("file", "report.docx", null, peHeader);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> storageWriter.store(disguised));

            assertEquals(415, error.getCode());
            verify(fileStorage, never()).store(any(java.io.InputStream.class), anyString());
        }

        @Test
        void contentInspectionFailureBlocksStorage() {
            byte[] pdf = "%PDF-1.7\n".getBytes();
            MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);
            org.mockito.Mockito.doThrow(new BusinessException(413, "PDF page limit exceeded"))
                    .when(inspector).inspect(any(), any());

            BusinessException error = assertThrows(BusinessException.class,
                    () -> storageWriter.store(file));

            assertEquals(413, error.getCode());
            // 关键：检查不过必须在写盘之前失败，不能留下半成品文件
            verify(fileStorage, never()).store(any(java.io.InputStream.class), anyString());
        }

        @Test
        void supportedContentIsStoredWithDigest() {
            byte[] pdf = "%PDF-1.7\ncontent".getBytes();
            MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", pdf);
            when(fileStorage.store(any(java.io.InputStream.class), anyString()))
                    .thenReturn("2026/10/12/abc.pdf");

            StorageWriter.StoredFile stored = storageWriter.store(file);

            assertEquals("2026/10/12/abc.pdf", stored.storageKey());
            assertEquals("pdf", stored.extension());
            assertEquals("application/pdf", stored.contentType());
            assertEquals(pdf.length, stored.sizeBytes());
            assertTrue(stored.sha256().matches("^[0-9a-f]{64}$"), "digest format: " + stored.sha256());
        }
    }

    // ==================================================== 验证码限制

    @Nested
    class VerificationCodeLimits {

        private StringRedisTemplate redis;
        private VerificationCodeSender sender;
        private VerificationRecordAuditService auditService;
        private UserService userService;
        private VerificationCodeServiceImpl service;

        @BeforeEach
        void setUp() {
            redis = mock(StringRedisTemplate.class);
            sender = mock(VerificationCodeSender.class);
            auditService = mock(VerificationRecordAuditService.class);
            userService = mock(UserService.class);

            VerificationCodeProperties properties = new VerificationCodeProperties();
            properties.setTtl(Duration.ofMinutes(5));
            properties.setResendInterval(Duration.ofSeconds(60));
            properties.setDailyLimit(10);
            properties.setMaxAttempts(5);
            service = new VerificationCodeServiceImpl(redis, properties, sender, auditService, userService);
        }

        @Test
        void resendWithinIntervalRejected() {
            when(userService.findByPhone("13800000000")).thenReturn(user(1L, "ENABLED"));
            // 脚本返回 0 表示重发间隔占位仍存在
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.LOGIN));

            assertEquals(429, error.getCode());
            // 被限流时绝不能真的把验证码发出去
            verify(sender, never()).send(anyString(), any(), anyString());
        }

        @Test
        void dailyLimitRejected() {
            when(userService.findByPhone("13800000000")).thenReturn(user(1L, "ENABLED"));
            // 日限额由 Redis 的「检查并自增」脚本判定：返回 0 表示已达上限
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.LOGIN));

            assertEquals(429, error.getCode());
            verify(sender, never()).send(anyString(), any(), anyString());
        }

        @Test
        void registerSceneRejectsExistingPhone() {
            when(userService.findByPhone("13800000000")).thenReturn(user(1L, "ENABLED"));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.REGISTER));

            assertEquals(400, error.getCode());
        }

        @Test
        void loginSceneRejectsUnknownPhone() {
            when(userService.findByPhone("13800000000")).thenReturn(null);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.LOGIN));

            assertEquals(400, error.getCode());
        }

        @Test
        void loginSceneRejectsDisabledAccount() {
            when(userService.findByPhone("13800000000")).thenReturn(user(1L, "DISABLED"));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.LOGIN));

            assertEquals(403, error.getCode());
        }

        @Test
        void deliveryFailureReturns502AndReleasesRateLimitSlot() {
            when(userService.findByPhone("13800000000")).thenReturn(user(1L, "ENABLED"));
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
            org.mockito.Mockito.doThrow(new BusinessException(500, "sms unavailable"))
                    .when(sender).send(anyString(), any(), anyString());

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.sendCode("13800000000", VerificationScene.LOGIN));

            assertEquals(502, error.getCode());
            // 不释放占位的话，用户要在整个重发间隔内都无法再试
            verify(redis).delete(anyString());
        }

        @Test
        void consumingMissingCodeRejected() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.consumeCode("13800000000", VerificationScene.LOGIN, "123456"));

            assertEquals(400, error.getCode());
            verify(auditService, never()).recordConsumed(anyString(), any());
        }

        @Test
        void wrongCodeRejectedAndCounted() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-2L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.consumeCode("13800000000", VerificationScene.LOGIN, "000000"));

            assertEquals(400, error.getCode());
            verify(auditService).recordFailure(anyString(), any());
        }

        @Test
        void tooManyAttemptsLocksCode() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.consumeCode("13800000000", VerificationScene.LOGIN, "000000"));

            assertEquals(429, error.getCode());
        }

        @Test
        void successfulConsumeIsRecordedExactlyOnce() {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

            service.consumeCode("13800000000", VerificationScene.LOGIN, "123456");

            // 消费成功必须落库，便于审计「验证码被谁用了」
            verify(auditService).recordConsumed(anyString(), any());
            verify(auditService, never()).recordFailure(anyString(), any());
        }

        @Test
        void unknownScriptResultIsTreatedAsFailure() {
            // Redis 返回 null 时必须按失败处理，绝不能因为拿不到结果就放行
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.consumeCode("13800000000", VerificationScene.LOGIN, "123456"));

            assertEquals(400, error.getCode());
            verify(auditService, never()).recordConsumed(anyString(), any());
        }

        private static User user(Long id, String status) {
            User u = new User();
            u.setId(id);
            u.setStatus(status);
            u.setPhone("13800000000");
            return u;
        }
    }

    // ==================================================== 任务幂等与归属

    @Nested
    class TaskIdempotencyAndOwnership {

        private ProcessingTaskMapper taskMapper;
        private TaskStepMapper stepMapper;
        private FileService fileService;
        private TaskExecutionEngine engine;
        private TaskServiceImpl service;

        @BeforeEach
        void setUp() {
            taskMapper = mock(ProcessingTaskMapper.class);
            stepMapper = mock(TaskStepMapper.class);
            fileService = mock(FileService.class);
            engine = mock(TaskExecutionEngine.class);
            service = new TaskServiceImpl(taskMapper, stepMapper, fileService, engine,
                    new TaskExecutorProperties());
        }

        @Test
        void duplicateIdempotencyKeyReturnsExistingTaskWithoutReExecuting() {
            ProcessingTask existing = new ProcessingTask();
            existing.setId(555L);
            existing.setUserId(1001L);
            existing.setTaskType("EXPORT");
            existing.setStatus("RUNNING");
            existing.setIdempotencyKey("key-1");

            // 插入撞唯一索引后回查，应拿到已有任务
            when(taskMapper.insert(any(ProcessingTask.class)))
                    .thenThrow(new DuplicateKeyException("uk_processing_task_idempotency"));
            when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

            CreateTaskDTO dto = new CreateTaskDTO();
            dto.setTaskType("EXPORT");
            dto.setIdempotencyKey("key-1");

            var result = service.submit(1001L, dto);

            assertEquals(555L, result.getId());
            // 关键：命中幂等键时不能再提交执行，否则会重复产出或重复扣额度
            verify(engine, never()).submit(anyLong());
        }

        @Test
        void inputVersionWithoutFileRejected() {
            CreateTaskDTO dto = new CreateTaskDTO();
            dto.setTaskType("EXPORT");
            dto.setInputVersion(3);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.submit(1001L, dto));

            assertEquals(400, error.getCode());
            verify(taskMapper, never()).insert(any(ProcessingTask.class));
        }

        @Test
        void taskOnForeignFileRejectedWithoutPersisting() {
            when(fileService.requireOwned(1001L, 77L))
                    .thenThrow(new BusinessException(404, "file not found"));

            CreateTaskDTO dto = new CreateTaskDTO();
            dto.setTaskType("EXPORT");
            dto.setFileId(77L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.submit(1001L, dto));

            assertEquals(404, error.getCode());
            verify(taskMapper, never()).insert(any(ProcessingTask.class));
        }

        @Test
        void successfulTaskCannotBeRetried() {
            ProcessingTask done = new ProcessingTask();
            done.setId(10L);
            done.setUserId(1001L);
            done.setStatus("SUCCEEDED");
            when(taskMapper.selectById(10L)).thenReturn(done);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.retry(1001L, 10L));

            assertEquals(409, error.getCode());
        }

        @Test
        void otherUsersTaskDetailReturns404() {
            ProcessingTask other = new ProcessingTask();
            other.setId(10L);
            other.setUserId(2002L);
            other.setStatus("RUNNING");
            when(taskMapper.selectById(10L)).thenReturn(other);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.detail(1001L, 10L));

            assertEquals(404, error.getCode());
        }
    }
}
