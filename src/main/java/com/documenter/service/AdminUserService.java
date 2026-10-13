package com.documenter.service;

import com.documenter.dto.AdminUserQueryDTO;
import com.documenter.vo.AdminUserVO;
import com.documenter.vo.PageResult;

/** 管理员账号管理。 */
public interface AdminUserService {

    PageResult<AdminUserVO> list(AdminUserQueryDTO query);

    AdminUserVO detail(Long userId);

    AdminUserVO updateStatus(Long administratorId, Long userId, String status);

    AdminUserVO updateRole(Long administratorId, Long userId, String role);
}
