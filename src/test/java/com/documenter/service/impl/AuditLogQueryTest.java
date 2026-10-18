package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.documenter.dto.AuditQueryDTO;
import com.documenter.entity.AuditLog;
import com.documenter.mapper.AdminStatisticsMapper;
import com.documenter.mapper.AuditLogMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.service.AuditService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.PagePreviewService;
import com.documenter.service.PdfDocumentService;
import com.documenter.service.TaskService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.AuditLogVO;
import com.documenter.vo.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志查询测试（TODO 4.3「管理员基础页面」）。
 *
 * <p>为什么值得单独测试：审计此前的状态是「只写不读」——
 * 有实体、有 Mapper、有写入路径，但没有任何查询入口。
 * 这导致两件事无法成立：
 * <ol>
 *   <li>「管理员查看用户文档会留痕」这句话无法被验证，因为看不到记录；</li>
 *   <li>留痕本身是否正确（用户名快照有没有存、请求链路 ID 有没有写）
 *       也没有任何环节会发现字段缺失。</li>
 * </ol>
 *
 * <p>因此本类不仅验证分页与过滤，还验证<b>查询结果完整保留了写入时的字段</b>，
 * 包括容易漏掉的 {@code username} 快照与 {@code requestId}。
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class AuditLogQueryTest {

    private AuditLogMapper auditLogMapper;
    private AdminResourceServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogMapper = mock(AuditLogMapper.class);
        service = new AdminResourceServiceImpl(
                mock(FileAssetMapper.class),
                mock(ProcessingTaskMapper.class),
                mock(AdminStatisticsMapper.class),
                auditLogMapper,
                mock(FileService.class),
                mock(DocumentVersionService.class),
                mock(DocxDocumentService.class),
                mock(PdfDocumentService.class),
                mock(XlsxDocumentService.class),
                mock(PagePreviewService.class),
                mock(TaskService.class),
                mock(AuditService.class));
    }

    @Test
    void auditFieldsArePreservedIncludingUsernameSnapshotAndRequestId() {
        AuditLog log = new AuditLog();
        log.setId(7L);
        log.setUserId(1L);
        // 用户名快照：用户改名或删除后仍可追溯，必须原样带出
        log.setUsername("admin_snapshot");
        log.setAction("ADMIN_FILE_VIEW");
        log.setTargetType("FILE");
        log.setTargetId("10");
        log.setDetail("查看版本 2 的页面预览元信息");
        log.setResult("SUCCESS");
        log.setRequestIp("127.0.0.1");
        // 请求链路 ID：用于与后端日志对齐排查，容易在映射时漏掉
        log.setRequestId("req-abc-123");
        log.setCreateTime(LocalDateTime.of(2026, 10, 15, 20, 30));

        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        PageResult<AuditLogVO> result = service.listAuditLogs(new AuditQueryDTO());

        assertEquals(1, result.getRecords().size());
        AuditLogVO vo = result.getRecords().get(0);
        assertEquals(7L, vo.id());
        assertEquals("admin_snapshot", vo.username());
        assertEquals("ADMIN_FILE_VIEW", vo.action());
        assertEquals("FILE", vo.targetType());
        assertEquals("10", vo.targetId());
        assertEquals("查看版本 2 的页面预览元信息", vo.detail());
        assertEquals("SUCCESS", vo.result());
        assertEquals("127.0.0.1", vo.requestIp());
        assertEquals("req-abc-123", vo.requestId());
        assertEquals(LocalDateTime.of(2026, 10, 15, 20, 30), vo.createTime());
    }

    @Test
    void paginationMetadataIsComputedConsistently() {
        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(45L);
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AuditQueryDTO query = new AuditQueryDTO();
        query.setPage(3);
        query.setSize(20);

        PageResult<AuditLogVO> result = service.listAuditLogs(query);

        assertEquals(3, result.getPage());
        assertEquals(20, result.getSize());
        assertEquals(45, result.getTotal());
        // 45 条按每页 20 条应为 3 页
        assertEquals(3, result.getPages());
    }

    @Test
    void emptyResultIsReturnedAsEmptyPageNotAnError() {
        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        PageResult<AuditLogVO> result = service.listAuditLogs(new AuditQueryDTO());

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(1, result.getPage());
    }

    @Test
    void systemOperationsWithNullUserIdAreReturned() {
        // 系统操作（如登录失败记录）没有 userId，只有 username 快照
        AuditLog systemLog = new AuditLog();
        systemLog.setId(8L);
        systemLog.setUserId(null);
        systemLog.setUsername("someone");
        systemLog.setAction("USER_LOGIN_FAILED");
        systemLog.setResult("FAILURE");

        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(systemLog));

        PageResult<AuditLogVO> result = service.listAuditLogs(new AuditQueryDTO());

        // userId 为空是合法状态，不能因为「看起来缺字段」就被丢弃或报错
        assertNull(result.getRecords().get(0).userId());
        assertEquals("someone", result.getRecords().get(0).username());
        assertEquals("FAILURE", result.getRecords().get(0).result());
    }

    @Test
    void queryFiltersAreAcceptedWithoutError() {
        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(auditLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AuditQueryDTO query = new AuditQueryDTO();
        query.setUserId(1L);
        query.setAction("FILE_DELETE");
        query.setTargetType("FILE");
        query.setTargetId("10");
        query.setResult("FAILURE");
        query.setRequestId("req-1");
        query.setFrom(LocalDateTime.of(2026, 10, 1, 0, 0));
        query.setTo(LocalDateTime.of(2026, 10, 31, 23, 59));

        service.listAuditLogs(query);

        // 计数与列表各查一次；具体条件由 SQL 片段承载，
        // 这里确认全条件组合不会抛异常（例如时间范围绑定失败）
        verify(auditLogMapper).selectCount(any(Wrapper.class));
        verify(auditLogMapper).selectList(any(Wrapper.class));
    }
}
