package com.documenter.service.impl;

import com.documenter.entity.User;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.UserMapper;
import com.documenter.service.AuditService;
import com.documenter.util.JwtUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceImplTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AdminUserServiceImpl service = new AdminUserServiceImpl(userMapper, jwtUtil, auditService);

    @Test
    void disablingUserRevokesSessionsAndWritesAudit() {
        User enabled = user(2L, "USER", "ENABLED");
        User disabled = user(2L, "USER", "DISABLED");
        when(userMapper.selectById(2L)).thenReturn(enabled, disabled);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        var result = service.updateStatus(1L, 2L, "DISABLED");

        assertEquals("DISABLED", result.getStatus());
        verify(userMapper).updateById(any(User.class));
        verify(jwtUtil).revokeAllSessions("2", null);
        verify(auditService).success(AuditAction.ADMIN_USER_DISABLE, "USER", 2L, "禁用用户账号");
    }

    @Test
    void administratorCannotDisableOwnAccount() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "ADMIN", "ENABLED"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateStatus(1L, 1L, "DISABLED"));

        assertEquals(409, error.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void administratorCannotDemoteOwnRole() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "ADMIN", "ENABLED"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateRole(1L, 1L, "USER"));

        assertEquals(409, error.getCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    private static User user(Long id, String role, String status) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
