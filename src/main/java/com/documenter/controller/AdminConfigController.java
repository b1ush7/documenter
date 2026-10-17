package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.DocTemplateQueryDTO;
import com.documenter.dto.ModelConfigQueryDTO;
import com.documenter.dto.SaveDocTemplateDTO;
import com.documenter.dto.SaveModelConfigDTO;
import com.documenter.dto.TemplateVersionQueryDTO;
import com.documenter.dto.UpdateModelConfigStatusDTO;
import com.documenter.service.DocTemplateService;
import com.documenter.service.ModelConfigService;
import com.documenter.vo.DocTemplateVO;
import com.documenter.vo.DocTemplateVersionVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.ModelConfigVO;
import com.documenter.vo.PageResult;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 管理员配置接口（TODO 3.2「管理员可管理公共模板和模型配置；模型密钥不返回前端」）。
 *
 * <p>单独成文件而不是塞进 {@code AdminController}：那个类已经承载用户、文件、任务三大块，
 * 继续膨胀会让「某个接口忘了加 {@code @PreAuthorize}」这类问题更难发现。
 * 这里的 {@code @PreAuthorize} 加在类上，所有方法继承同一道防线。
 *
 * <p>密钥防线（两道）：
 * <ol>
 *   <li>出参类型 {@link ModelConfigVO} 根本没有密文/提示位字段，
 *       因此不存在「直接返回实体」把密钥泄露出去的可能；</li>
 *   <li>数据库里存的是 AES-GCM 密文，即使库被导出也无法直接使用。</li>
 * </ol>
 * 审计日志只记录「是否更新了密钥」，不记录密钥内容。
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConfigController {

    private final ModelConfigService modelConfigService;
    private final DocTemplateService docTemplateService;

    public AdminConfigController(ModelConfigService modelConfigService,
                                 DocTemplateService docTemplateService) {
        this.modelConfigService = modelConfigService;
        this.docTemplateService = docTemplateService;
    }

    // ------------------------------------------------------------ 模型配置

    @GetMapping("/model-configs")
    public ApiResponse<PageResult<ModelConfigVO>> listModelConfigs(@Valid @ModelAttribute ModelConfigQueryDTO query) {
        return ApiResponse.success(modelConfigService.list(query));
    }

    @GetMapping("/model-configs/{id}")
    public ApiResponse<ModelConfigVO> modelConfigDetail(@PathVariable Long id) {
        return ApiResponse.success(modelConfigService.detail(id));
    }

    /**
     * 新建模型配置。
     *
     * <p>新建后状态固定为 DISABLED：密钥还没验证就被任务自动选中会直接导致调用失败。
     * 需要显式调用启停接口启用。
     */
    @PostMapping("/model-configs")
    public ApiResponse<ModelConfigVO> createModelConfig(@Valid @RequestBody SaveModelConfigDTO dto) {
        return ApiResponse.success("模型配置已创建", modelConfigService.create(dto));
    }

    /** 更新配置；{@code apiKey} 留空表示保持原密钥不变。 */
    @PutMapping("/model-configs/{id}")
    public ApiResponse<ModelConfigVO> updateModelConfig(@PathVariable Long id,
                                                       @Valid @RequestBody SaveModelConfigDTO dto) {
        return ApiResponse.success("模型配置已更新", modelConfigService.update(id, dto));
    }

    @PutMapping("/model-configs/{id}/status")
    public ApiResponse<ModelConfigVO> updateModelConfigStatus(@PathVariable Long id,
                                                             @Valid @RequestBody UpdateModelConfigStatusDTO dto) {
        return ApiResponse.success("配置状态已更新", modelConfigService.updateStatus(id, dto.getStatus()));
    }

    /** 删除配置；默认配置不允许直接删除，需先指定替代者。 */
    @DeleteMapping("/model-configs/{id}")
    public ApiResponse<Void> deleteModelConfig(@PathVariable Long id) {
        modelConfigService.delete(id);
        return ApiResponse.success("模型配置已删除", null);
    }

    // ------------------------------------------------------------ 公共模板

    @GetMapping("/templates")
    public ApiResponse<PageResult<DocTemplateVO>> listTemplates(@Valid @ModelAttribute DocTemplateQueryDTO query) {
        return ApiResponse.success(docTemplateService.listForAdmin(query));
    }

    @GetMapping("/templates/{id}")
    public ApiResponse<DocTemplateVO> templateDetail(@PathVariable Long id) {
        return ApiResponse.success(docTemplateService.detail(id));
    }

    /**
     * 新建模板（multipart）。
     *
     * <p>模板文件与用户上传走同一套类型识别与结构安全检查（见 StorageWriter），
     * 因此不存在「模板上传绕过校验」的路径。
     */
    @PostMapping("/templates")
    public ApiResponse<DocTemplateVO> createTemplate(@Valid @ModelAttribute SaveDocTemplateDTO dto,
                                                     @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("模板已创建", docTemplateService.create(dto, file));
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<DocTemplateVO> updateTemplate(@PathVariable Long id,
                                                     @Valid @RequestBody SaveDocTemplateDTO dto) {
        return ApiResponse.success("模板已更新", docTemplateService.update(id, dto));
    }

    /** 替换模板内容，版本号 +1；同一事务保存历史文件索引。 */
    @PostMapping("/templates/{id}/content")
    public ApiResponse<DocTemplateVO> replaceTemplateContent(@PathVariable Long id,
                                                             @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("模板内容已替换", docTemplateService.replaceContent(id, file));
    }

    @PutMapping("/templates/{id}/enabled")
    public ApiResponse<DocTemplateVO> updateTemplateEnabled(@PathVariable Long id,
                                                            @RequestParam("enabled") boolean enabled) {
        return ApiResponse.success(enabled ? "模板已启用" : "模板已停用",
                docTemplateService.updateEnabled(id, enabled));
    }

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable Long id) {
        docTemplateService.delete(id);
        return ApiResponse.success("模板已删除", null);
    }

    @GetMapping("/templates/{id}/download")
    public ResponseEntity<Resource> downloadTemplate(@PathVariable Long id) {
        FileDownload download = docTemplateService.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.downloadFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.contentLength())
                .body(download.resource());
    }

    @GetMapping("/templates/{id}/versions")
    public ApiResponse<PageResult<DocTemplateVersionVO>> templateVersions(@PathVariable Long id,
            @Valid @ModelAttribute TemplateVersionQueryDTO query) {
        return ApiResponse.success(docTemplateService.listVersions(id, query));
    }

    @GetMapping("/templates/{id}/versions/{versionNo}/download")
    public ResponseEntity<Resource> downloadTemplateVersion(@PathVariable Long id, @PathVariable Integer versionNo) {
        FileDownload download = docTemplateService.downloadVersion(id, versionNo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.downloadFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.contentLength())
                .body(download.resource());
    }

    @PostMapping("/templates/{id}/versions/{versionNo}/restore")
    public ApiResponse<DocTemplateVO> restoreTemplateVersion(@PathVariable Long id, @PathVariable Integer versionNo,
            @RequestParam Integer expectVersion) {
        return ApiResponse.success("模板文件版本已恢复", docTemplateService.restoreVersion(id, versionNo, expectVersion));
    }
}
