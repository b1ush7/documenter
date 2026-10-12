package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.documenter.component.StorageWriter;
import com.documenter.dto.AiSessionQueryDTO;
import com.documenter.dto.CreateAiSessionDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.dto.SaveModelConfigDTO;
import com.documenter.entity.AiMessage;
import com.documenter.entity.AiSession;
import com.documenter.entity.DocTemplate;
import com.documenter.entity.FileAsset;
import com.documenter.entity.ModelConfig;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.AiMessageMapper;
import com.documenter.mapper.AiSessionMapper;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.mapper.ModelConfigMapper;
import com.documenter.service.AuditService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.util.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 越权与资源保护校验（TODO 8 第 166 行：账号越权、文件越权、任务重复执行、版本冲突等）。
 *
 * <p>本类只验证「不需要数据库就能确定」的部分：归属判定与资源保护规则。
 * 用 Mockito 提供 Mapper 替身，因此断言的是<b>业务判断</b>是否正确，
 * 而不是 SQL 是否正确——后者仍必须在真库上验证。
 *
 * <p>为什么不省掉这些测试：越权类缺陷的特点是「功能看起来完全正常」，
 * 只有攻击者视角的用例才能暴露。靠人工点界面几乎不可能覆盖到。
 */
class AccessControlTest {

    private static final Long OWNER = 1001L;
    private static final Long OTHER = 2002L;

    /**
     * 注册实体元数据。
     *
     * <p>Mockito 提供的 mapper 没有经过 MyBatis-Plus 的初始化流程，
     * lambda 查询拿不到列名缓存，`getSqlSegment()` 会直接抛
     * "can not find lambda cache for this entity"。这里手动初始化，
     * 使测试能够断言<b>真实的 SQL 片段</b>而不是退化成弱断言。
     */
    @org.junit.jupiter.api.BeforeAll
    static void registerEntityMetadata() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("access-control-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AiSession.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ModelConfig.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocTemplate.class);
    }

    // ============================================================ 对话模块归属

    @Nested
    @DisplayName("对话会话归属校验")
    class AiSessionOwnership {

        private AiSessionMapper sessionMapper;
        private AiMessageMapper messageMapper;
        private FileService fileService;
        private AiChatServiceImpl service;

        @BeforeEach
        void setUp() {
            sessionMapper = mock(AiSessionMapper.class);
            messageMapper = mock(AiMessageMapper.class);
            fileService = mock(FileService.class);
            service = new AiChatServiceImpl(sessionMapper, messageMapper, fileService, mock(AuditService.class));
        }

        @Test
        @DisplayName("他人会话按不存在处理，返回 404 而不是 403，避免枚举会话 ID")
        void otherUsersSessionIsTreatedAsMissing() {
            when(sessionMapper.selectById(99L)).thenReturn(session(99L, OTHER));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.requireOwnedSession(OWNER, 99L));

            // 返回 403 等于告诉攻击者「这个会话 ID 真实存在」
            assertEquals(404, error.getCode());
        }

        @Test
        @DisplayName("不存在的会话同样返回 404，与越权响应无法区分")
        void missingSessionAlsoReturns404() {
            when(sessionMapper.selectById(anyLong())).thenReturn(null);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.requireOwnedSession(OWNER, 12345L));

            assertEquals(404, error.getCode());
        }

        @Test
        @DisplayName("本人会话可以正常读取")
        void ownSessionIsAccessible() {
            when(sessionMapper.selectById(1L)).thenReturn(session(1L, OWNER));

            AiSession result = service.requireOwnedSession(OWNER, 1L);

            assertEquals(OWNER, result.getUserId());
        }

        @Test
        @DisplayName("读取他人会话消息被拒绝，且不会去查消息表")
        void readingOtherUsersMessagesIsRejectedBeforeQuery() {
            when(sessionMapper.selectById(9L)).thenReturn(session(9L, OTHER));

            assertThrows(BusinessException.class, () -> service.listMessages(OWNER, 9L));

            // 关键：归属校验不过就绝不能触碰消息数据
            verify(messageMapper, never()).selectBySession(anyLong());
        }

        @Test
        @DisplayName("向他人会话发消息被拒绝，且不会写入任何消息")
        void sendingToOtherUsersSessionIsRejected() {
            when(sessionMapper.selectById(9L)).thenReturn(session(9L, OTHER));

            assertThrows(BusinessException.class, () -> service.appendUserMessage(OWNER, 9L, "你好"));

            verify(messageMapper, never()).insert(any(AiMessage.class));
        }

        @Test
        @DisplayName("向已归档会话发消息返回 409")
        void sendingToArchivedSessionIsRejected() {
            AiSession archived = session(5L, OWNER);
            archived.setStatus("ARCHIVED");
            when(sessionMapper.selectById(5L)).thenReturn(archived);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.appendUserMessage(OWNER, 5L, "你好"));

            assertEquals(409, error.getCode());
            verify(messageMapper, never()).insert(any(AiMessage.class));
        }

        @Test
        @DisplayName("绑定他人文件建会话被拒绝，防止借会话绕过文件鉴权")
        void bindingOtherUsersFileIsRejected() {
            // FileService.requireOwned 对非本人文件抛 404
            when(fileService.requireOwned(OWNER, 77L)).thenThrow(new BusinessException(404, "文件不存在"));

            CreateAiSessionDTO dto = new CreateAiSessionDTO();
            dto.setFileId(77L);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.createSession(OWNER, dto));

            assertEquals(404, error.getCode());
            verify(sessionMapper, never()).insert(any(AiSession.class));
        }

        @Test
        @DisplayName("删除他人会话被拒绝，且不会删除任何记录")
        void deletingOtherUsersSessionIsRejected() {
            when(sessionMapper.selectById(9L)).thenReturn(session(9L, OTHER));

            assertThrows(BusinessException.class, () -> service.deleteSession(OWNER, 9L));

            verify(messageMapper, never()).delete(any(Wrapper.class));
            verify(sessionMapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("会话列表查询把当前用户 ID 写入查询条件")
        void sessionListAlwaysFilteredByCurrentUser() {
            when(sessionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of());

            AiSessionQueryDTO query = new AiSessionQueryDTO();
            // 即使前端塞了一个别人的 fileId，查询也必须被自己的 user_id 限制住
            query.setFileId(888L);
            service.listSessions(OWNER, query);

            // 断言真实 SQL 片段里带上了当前用户 ID 与前端传入的 fileId。
            // 这是最强的一种断言：它验证的是「生成的 WHERE 到底过滤了什么」，
            // 而不是「调用方是否记得传参数」。
            org.mockito.ArgumentCaptor<Wrapper<AiSession>> captor =
                    org.mockito.ArgumentCaptor.forClass(Wrapper.class);
            verify(sessionMapper).selectList(captor.capture());
            String sql = captor.getValue().getSqlSegment();
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("user_id"),
                    "会话列表必须按 user_id 过滤，实际 SQL 片段: " + sql);
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("file_id"),
                    "fileId 条件应被带入查询，实际 SQL 片段: " + sql);
        }

        private static AiSession session(Long id, Long userId) {
            AiSession s = new AiSession();
            s.setId(id);
            s.setUserId(userId);
            s.setStatus("ACTIVE");
            return s;
        }
    }

    // ============================================================ 模型配置保护规则

    @Nested
    @DisplayName("模型配置的资源保护")
    class ModelConfigGuards {

        private ModelConfigMapper mapper;
        private SecretCipher cipher;
        private ModelConfigServiceImpl service;

        @BeforeEach
        void setUp() {
            mapper = mock(ModelConfigMapper.class);
            // 用真实 SecretCipher（已配置主密钥），以便验证密文确实不是明文
            cipher = new SecretCipher("unit-test-secret-key-32-bytes-long!");
            cipher.init();
            service = new ModelConfigServiceImpl(mapper, cipher, mock(AuditService.class));
        }

        @Test
        @DisplayName("启用没有密钥的配置被拒绝，避免任务选到必然失败的配置")
        void cannotEnableConfigWithoutApiKey() {
            ModelConfig withoutKey = config(1L, "default-text", "TEXT", null);
            when(mapper.selectById(1L)).thenReturn(withoutKey);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.updateStatus(1L, "ENABLED"));

            assertEquals(409, error.getCode());
        }

        @Test
        @DisplayName("已有密钥的配置可以启用")
        void canEnableConfigWithApiKey() {
            ModelConfig withKey = config(1L, "default-text", "TEXT", "sk-abcdef1234567890");
            when(mapper.selectById(1L)).thenReturn(withKey);

            service.updateStatus(1L, "ENABLED");

            verify(mapper).updateById(any(ModelConfig.class));
        }

        @Test
        @DisplayName("非法状态值被拒绝")
        void invalidStatusRejected() {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.updateStatus(1L, "ARCHIVED"));

            assertEquals(400, error.getCode());
        }

        @Test
        @DisplayName("默认配置不能直接删除，否则该用途会没有可用模型")
        void cannotDeleteDefaultConfig() {
            ModelConfig config = config(1L, "default-text", "TEXT", "sk-xxxx");
            config.setIsDefault(Boolean.TRUE);
            when(mapper.selectById(1L)).thenReturn(config);

            BusinessException error = assertThrows(BusinessException.class, () -> service.delete(1L));

            assertEquals(409, error.getCode());
            verify(mapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("密钥以密文入库，且不包含明文片段")
        void apiKeyIsStoredEncrypted() {
            // 用 Answer 模拟数据库自增回填：insert 时把自增 ID 写回实体，
            // 之后 selectById 能取到同一条记录。不做这一步，create() 末尾的
            // requireConfig 会因拿不到记录而报错。
            java.util.Map<Long, ModelConfig> store = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
            when(mapper.insert(any(ModelConfig.class))).thenAnswer(inv -> {
                ModelConfig toInsert = inv.getArgument(0);
                long id = seq.incrementAndGet();
                toInsert.setId(id);
                store.put(id, toInsert);
                return 1;
            });
            when(mapper.selectById(anyLong())).thenAnswer(inv -> store.get((Long) inv.getArgument(0)));

            SaveModelConfigDTO dto = new SaveModelConfigDTO();
            dto.setCode("default-text");
            dto.setName("默认文本模型");
            dto.setScope("TEXT");
            dto.setProvider("openai-compatible");
            dto.setModelName("qwen-plus");
            String plainKey = "sk-verysecretvalue1234567890";
            dto.setApiKey(plainKey);

            service.create(dto);

            org.mockito.ArgumentCaptor<ModelConfig> captor =
                    org.mockito.ArgumentCaptor.forClass(ModelConfig.class);
            verify(mapper).insert(captor.capture());
            String stored = captor.getValue().getApiKeyCipher();

            org.junit.jupiter.api.Assertions.assertNotNull(stored, "应写入密文");
            assertFalse(stored.contains(plainKey), "密文不得包含明文密钥");
            assertFalse(stored.contains("verysecretvalue"), "密文不得包含明文片段");
            // 密文必须能被同一主密钥解开，验证加解密闭环
            assertEquals(plainKey, cipher.decrypt(stored));
        }

        @Test
        @DisplayName("未配置主密钥时保存密钥明确报错，而不是静默存明文")
        void savingKeyWithoutMasterSecretFails() {
            SecretCipher noKey = new SecretCipher("");
            noKey.init();
            ModelConfigServiceImpl noKeyService =
                    new ModelConfigServiceImpl(mapper, noKey, mock(AuditService.class));

            SaveModelConfigDTO dto = new SaveModelConfigDTO();
            dto.setCode("x");
            dto.setName("x");
            dto.setScope("TEXT");
            dto.setProvider("p");
            dto.setModelName("m");
            dto.setApiKey("sk-something");

            BusinessException error = assertThrows(BusinessException.class,
                    () -> noKeyService.create(dto));

            assertEquals(500, error.getCode());
            verify(mapper, never()).insert(any(ModelConfig.class));
        }

        private static ModelConfig config(Long id, String code, String scope, String plainKey) {
            ModelConfig c = new ModelConfig();
            c.setId(id);
            c.setCode(code);
            c.setScope(scope);
            c.setStatus("DISABLED");
            c.setIsDefault(Boolean.FALSE);
            if (plainKey != null) {
                SecretCipher helper = new SecretCipher("unit-test-secret-key-32-bytes-long!");
                helper.init();
                c.setApiKeyCipher(helper.encrypt(plainKey));
            }
            return c;
        }
    }

    // ============================================================ 模板保护规则

    @Nested
    @DisplayName("公共模板的资源保护")
    class DocTemplateGuards {

        private DocTemplateMapper mapper;
        private StorageWriter storageWriter;
        private FileStorage fileStorage;
        private DocTemplateServiceImpl service;

        @BeforeEach
        void setUp() {
            mapper = mock(DocTemplateMapper.class);
            storageWriter = mock(StorageWriter.class);
            fileStorage = mock(FileStorage.class);
            service = new DocTemplateServiceImpl(mapper, storageWriter, fileStorage, mock(AuditService.class));
        }

        @Test
        @DisplayName("没有文件的模板不能启用，否则用户选了也生成不出内容")
        void cannotEnableTemplateWithoutFile() {
            DocTemplate noFile = template(1L, "tpl-a", null);
            when(mapper.selectById(1L)).thenReturn(noFile);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.updateEnabled(1L, true));

            assertEquals(409, error.getCode());
        }

        @Test
        @DisplayName("启用中的模板不能删除，要求先停用")
        void cannotDeleteEnabledTemplate() {
            DocTemplate enabled = template(1L, "tpl-a", "2026/01/01/abc.docx");
            enabled.setEnabled(Boolean.TRUE);
            when(mapper.selectById(1L)).thenReturn(enabled);

            BusinessException error = assertThrows(BusinessException.class, () -> service.delete(1L));

            assertEquals(409, error.getCode());
            verify(mapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("模板标识重复时返回 409，并清理已落盘的文件避免孤儿")
        void duplicateCodeCleansUpStoredFile() {
            when(mapper.exists(any(Wrapper.class))).thenReturn(true);

            SaveDocTemplateDTO dto = new SaveDocTemplateDTO();
            dto.setCode("tpl-a");
            dto.setName("模板 A");

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.create(dto, mock(org.springframework.web.multipart.MultipartFile.class)));

            assertEquals(409, error.getCode());
            // 重名时不应该落盘，也就不需要清理
            verify(storageWriter, never()).store(any());
            verify(fileStorage, never()).delete(any());
        }

        @Test
        @DisplayName("模板不存在返回 404")
        void missingTemplateReturns404() {
            when(mapper.selectById(anyLong())).thenReturn(null);

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.detail(999L));

            assertEquals(404, error.getCode());
        }

        private static DocTemplate template(Long id, String code, String storageKey) {
            DocTemplate t = new DocTemplate();
            t.setId(id);
            t.setCode(code);
            t.setStorageKey(storageKey);
            t.setEnabled(Boolean.FALSE);
            t.setVersionNo(1);
            return t;
        }
    }
}
