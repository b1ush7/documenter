package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.AdminUpdateUserRoleDTO;
import com.documenter.dto.AdminUpdateUserStatusDTO;
import com.documenter.dto.AdminUserQueryDTO;
import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.service.AdminResourceService;
import com.documenter.service.AdminUserService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.AdminFileVO;
import com.documenter.vo.AdminTaskDetailVO;
import com.documenter.vo.AdminTaskVO;
import com.documenter.vo.AdminUserVO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import com.documenter.vo.VersionVO;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 管理员接口；账号只能通过数据库或受控部署流程初始化为 ADMIN。 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminUserService adminUserService;
    private final AdminResourceService adminResourceService;

    public AdminController(AdminUserService adminUserService, AdminResourceService adminResourceService) {
        this.adminUserService = adminUserService;
        this.adminResourceService = adminResourceService;
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

    @GetMapping("/files")
    public ApiResponse<PageResult<AdminFileVO>> listFiles(
            @RequestParam(required = false) Long userId,
            @Valid @ModelAttribute FileQueryDTO query) {
        return ApiResponse.success(adminResourceService.listFiles(userId, query));
    }

    @GetMapping("/files/{fileId}")
    public ApiResponse<AdminFileVO> fileDetail(@PathVariable Long fileId) {
        return ApiResponse.success(adminResourceService.fileDetail(fileId));
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileId) {
        return toResponse(adminResourceService.downloadFile(fileId));
    }

    @GetMapping("/files/{fileId}/versions")
    public ApiResponse<List<VersionVO>> listVersions(@PathVariable Long fileId) {
        return ApiResponse.success(adminResourceService.listVersions(fileId));
    }

    @GetMapping("/files/{fileId}/versions/{versionNo}")
    public ApiResponse<VersionVO> versionDetail(@PathVariable Long fileId,
                                                @PathVariable Integer versionNo) {
        return ApiResponse.success(adminResourceService.versionDetail(fileId, versionNo));
    }

    @GetMapping("/files/{fileId}/versions/{versionNo}/download")
    public ResponseEntity<Resource> downloadVersion(@PathVariable Long fileId,
                                                     @PathVariable Integer versionNo) {
        return toResponse(adminResourceService.downloadVersion(fileId, versionNo));
    }

    @GetMapping("/files/{fileId}/versions/{versionNo}/structure")
    public ApiResponse<DocxStructureVO> readDocxStructure(@PathVariable Long fileId,
                                                          @PathVariable Integer versionNo) {
        return ApiResponse.success(adminResourceService.readDocxStructure(fileId, versionNo));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<AdminTaskVO>> listTasks(
            @RequestParam(required = false) Long userId,
            @Valid @ModelAttribute TaskQueryDTO query) {
        return ApiResponse.success(adminResourceService.listTasks(userId, query));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AdminTaskDetailVO> taskDetail(@PathVariable Long taskId) {
        return ApiResponse.success(adminResourceService.taskDetail(taskId));
    }

    private static ResponseEntity<Resource> toResponse(FileDownload download) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.downloadFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.contentLength())
                .body(download.resource());
    }
}
