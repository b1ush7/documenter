package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.enums.AuditAction;
import com.documenter.service.AuditService;
import com.documenter.service.impl.StorageRecycleService;
import com.documenter.vo.StorageRecycleReport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/storage")
@PreAuthorize("hasRole('ADMIN')")
public class StorageRecycleController {
    private final StorageRecycleService service;
    private final AuditService audit;

    public StorageRecycleController(StorageRecycleService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/recycle")
    public ApiResponse<StorageRecycleReport> recycle(@RequestParam(defaultValue = "true") boolean dryRun) {
        try {
            var report = service.recycle(dryRun);
            audit.success(AuditAction.ADMIN_STORAGE_RECYCLE, "STORAGE", null,
                    "dryRun=" + report.dryRun() + ", expiredFiles=" + report.expiredFiles()
                            + ", deletedBlobs=" + report.deletedBlobs() + ", deletedBytes=" + report.deletedBytes());
            return ApiResponse.success(report);
        } catch (RuntimeException failure) {
            audit.record(AuditAction.ADMIN_STORAGE_RECYCLE, "STORAGE", null,
                    "dryRun=" + dryRun + ", 回收未完成，详见服务端日志", false);
            throw failure;
        }
    }
}
