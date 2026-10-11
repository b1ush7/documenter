package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.dto.AdminUserQueryDTO;
import com.documenter.entity.User;
import com.documenter.enums.AuditAction;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.UserMapper;
import com.documenter.service.AdminUserService;
import com.documenter.service.AuditService;
import com.documenter.util.JwtUtil;
import com.documenter.vo.AdminUserVO;
import com.documenter.vo.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 管理员账号管理实现。 */
@Service
public class AdminUserServiceImpl implements AdminUserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    public AdminUserServiceImpl(UserMapper userMapper, JwtUtil jwtUtil, AuditService auditService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.auditService = auditService;
    }

    @Override
    public PageResult<AdminUserVO> list(AdminUserQueryDTO query) {
        String keyword = trimToNull(query.getKeyword());
        String status = trimToNull(query.getStatus());
        String role = trimToNull(query.getRole());
        long offset = (query.getPage() - 1) * query.getSize();

        var conditions = Wrappers.<User>lambdaQuery()
                .and(keyword != null, wrapper -> wrapper
                        .like(User::getUsername, keyword)
                        .or()
                        .like(User::getPhone, keyword))
                .eq(status != null, User::getStatus, status)
                .eq(role != null, User::getRole, role);
        Long total = userMapper.selectCount(conditions);
        List<AdminUserVO> records = userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .and(keyword != null, wrapper -> wrapper
                                .like(User::getUsername, keyword)
                                .or()
                                .like(User::getPhone, keyword))
                        .eq(status != null, User::getStatus, status)
                        .eq(role != null, User::getRole, role)
                        .orderByDesc(User::getCreateTime)
                        .last("LIMIT " + query.getSize() + " OFFSET " + offset))
                .stream().map(AdminUserVO::from).toList();

        PageResult<AdminUserVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total == null ? 0 : total);
        result.setPage(query.getPage());
        result.setSize(query.getSize());
        result.setPages(PageResult.pagesOf(result.getTotal(), query.getSize()));
        return result;
    }

    @Override
    public AdminUserVO detail(Long userId) {
        return AdminUserVO.from(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateStatus(Long administratorId, Long userId, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException(400, "账号状态只支持 ENABLED、DISABLED");
        }
        User target = requireUser(userId);
        if (Objects.equals(administratorId, userId) && "DISABLED".equals(status)) {
            throw new BusinessException(409, "管理员不能禁用自己的账号");
        }
        if (status.equals(target.getStatus())) {
            return AdminUserVO.from(target);
        }

        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        if (userMapper.updateById(update) != 1) {
            throw new BusinessException(409, "账号状态已发生变化，请刷新后重试");
        }
        if ("DISABLED".equals(status)) {
            jwtUtil.revokeAllSessions(String.valueOf(userId), null);
            auditService.success(AuditAction.ADMIN_USER_DISABLE, "USER", userId, "禁用用户账号");
        } else {
            auditService.success(AuditAction.ADMIN_USER_ENABLE, "USER", userId, "恢复用户账号");
        }
        return AdminUserVO.from(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateRole(Long administratorId, Long userId, String role) {
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException(400, "用户角色只支持 USER、ADMIN");
        }
        User target = requireUser(userId);
        if (Objects.equals(administratorId, userId) && "USER".equals(role)) {
            throw new BusinessException(409, "管理员不能降低自己的角色");
        }
        if (role.equals(target.getRole())) {
            return AdminUserVO.from(target);
        }

        String previousRole = target.getRole();
        User update = new User();
        update.setId(userId);
        update.setRole(role);
        if (userMapper.updateById(update) != 1) {
            throw new BusinessException(409, "用户角色已发生变化，请刷新后重试");
        }
        // 已签发 Access Token 中的权限不会自动变化，撤销会话强制重新认证。
        jwtUtil.revokeAllSessions(String.valueOf(userId), null);
        auditService.success(AuditAction.ADMIN_ROLE_CHANGE, "USER", userId,
                "角色由 " + previousRole + " 调整为 " + role);
        return AdminUserVO.from(requireUser(userId));
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(400, "用户 ID 不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
