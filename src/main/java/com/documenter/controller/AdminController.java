package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.AdminUpdateUserRoleDTO;
import com.documenter.dto.AdminUpdateUserStatusDTO;
import com.documenter.dto.AdminUserQueryDTO;
import com.documenter.service.AdminUserService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.AdminUserVO;
import com.documenter.vo.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员接口；账号只能通过数据库或受控部署流程初始化为 ADMIN。 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService adminUserService;

    public AdminController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/users")
    public ApiResponse<PageResult<AdminUserVO>> listUsers(@Valid @ModelAttribute AdminUserQueryDTO query) {
        return ApiResponse.success(adminUserService.list(query));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<AdminUserVO> userDetail(@PathVariable Long userId) {
        return ApiResponse.success(adminUserService.detail(userId));
    }

    @PutMapping("/users/{userId}/status")
    public ApiResponse<AdminUserVO> updateUserStatus(@PathVariable Long userId,
                                                      @Valid @RequestBody AdminUpdateUserStatusDTO request) {
        return ApiResponse.success("账号状态已更新", adminUserService.updateStatus(
                SecurityUtil.requireUserId(), userId, request.getStatus()));
    }

    @PutMapping("/users/{userId}/role")
    public ApiResponse<AdminUserVO> updateUserRole(@PathVariable Long userId,
                                                    @Valid @RequestBody AdminUpdateUserRoleDTO request) {
        return ApiResponse.success("用户角色已更新", adminUserService.updateRole(
                SecurityUtil.requireUserId(), userId, request.getRole()));
    }
}
