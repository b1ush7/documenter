package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.dto.RenameFileDTO;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.PageResult;
import com.documenter.vo.VersionVO;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.List;

/**
 * 文件与版本接口（TODO 3.3）。
 *
 * <p>归属校验一律使用 {@link SecurityUtil#requireUserId()}，不接受任何由前端传入的用户 ID。
 * 所有响应都不包含 storageKey（见 FileAssetVO / VersionVO）。
 */
@RestController
@RequestMapping("/file")
public class FileController {

    private final FileService fileService;
    private final DocumentVersionService versionService;
    private final DocxDocumentService docxDocumentService;

    public FileController(FileService fileService, DocumentVersionService versionService,
                          DocxDocumentService docxDocumentService) {
        this.fileService = fileService;
        this.versionService = versionService;
        this.docxDocumentService = docxDocumentService;
    }

    // ---------------------------------------------------------------- 文件

    /** 上传。真实类型由服务端按内容识别，客户端声明的类型与扩展名一律忽略。 */
    @PostMapping("/upload")
    public ApiResponse<FileAssetVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success("上传成功", fileService.upload(SecurityUtil.requireUserId(), file));
    }

    @GetMapping("/list")
    public ApiResponse<PageResult<FileAssetVO>> list(@Valid @ModelAttribute FileQueryDTO query) {
        return ApiResponse.success(fileService.list(SecurityUtil.requireUserId(), query));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<FileAssetVO> detail(@PathVariable Long fileId) {
        return ApiResponse.success(fileService.detail(SecurityUtil.requireUserId(), fileId));
    }

    @PutMapping("/{fileId}/name")
    public ApiResponse<FileAssetVO> rename(@PathVariable Long fileId, @Valid @RequestBody RenameFileDTO dto) {
        return ApiResponse.success("重命名成功",
                fileService.rename(SecurityUtil.requireUserId(), fileId, dto.getDisplayName()));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable Long fileId) {
        fileService.delete(SecurityUtil.requireUserId(), fileId);
        return ApiResponse.success("删除成功", null);
    }

    /**
     * 下载当前版本。
     *
     * <p>通过内部文件 ID 定位资源，而不是接受路径参数，从根本上避免任意路径读取。
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long fileId) {
        return toResponse(fileService.download(SecurityUtil.requireUserId(), fileId));
    }

    // ---------------------------------------------------------------- 版本

    /** 版本历史，按版本号倒序。 */
    @GetMapping("/{fileId}/versions")
    public ApiResponse<List<VersionVO>> listVersions(@PathVariable Long fileId) {
        return ApiResponse.success(versionService.listVersions(SecurityUtil.requireUserId(), fileId));
    }

    @GetMapping("/{fileId}/versions/{versionNo}")
    public ApiResponse<VersionVO> getVersion(@PathVariable Long fileId, @PathVariable Integer versionNo) {
        return ApiResponse.success(versionService.getVersion(SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /**
     * 下载指定版本。
     *
     * <p>预览与下载必须来自同一版本，否则会出现「看到的是新版、下到的是旧版」的不一致，
     * 因此版本下载是独立端点而不是复用文件下载。
     */
    @GetMapping("/{fileId}/versions/{versionNo}/download")
    public ResponseEntity<Resource> downloadVersion(@PathVariable Long fileId,
                                                   @PathVariable Integer versionNo) {
        return toResponse(versionService.downloadVersion(SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /** 读取指定 DOCX 版本的段落、表格与图片结构，不修改原文件。 */
    @GetMapping("/{fileId}/versions/{versionNo}/structure")
    public ApiResponse<DocxStructureVO> readDocxStructure(@PathVariable Long fileId,
                                                          @PathVariable Integer versionNo) {
        return ApiResponse.success(docxDocumentService.readStructure(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /** 批量替换指定 DOCX 版本中的简单段落，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/paragraph-text")
    public ApiResponse<VersionVO> replaceDocxParagraphText(@PathVariable Long fileId,
                                                           @PathVariable Integer versionNo,
                                                           @Valid @RequestBody ReplaceDocxTextDTO request) {
        return ApiResponse.success("已生成新版本", docxDocumentService.replaceParagraphText(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 在指定 DOCX 版本中插入或删除段落，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/paragraph-edits")
    public ApiResponse<VersionVO> editDocxParagraphs(@PathVariable Long fileId,
                                                      @PathVariable Integer versionNo,
                                                      @Valid @RequestBody EditDocxParagraphsDTO request) {
        return ApiResponse.success("已生成新版本", docxDocumentService.editParagraphs(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 修改指定 DOCX 版本的单元格文本或表格行，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/table-edits")
    public ApiResponse<VersionVO> editDocxTable(@PathVariable Long fileId,
                                                @PathVariable Integer versionNo,
                                                @Valid @RequestBody EditDocxTableDTO request) {
        return ApiResponse.success("已生成新版本", docxDocumentService.editTable(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 设置指定 DOCX 版本中的标题、普通段落或列表格式。 */
    @PostMapping("/{fileId}/versions/{versionNo}/paragraph-formats")
    public ApiResponse<VersionVO> formatDocxParagraphs(@PathVariable Long fileId,
                                                       @PathVariable Integer versionNo,
                                                       @Valid @RequestBody FormatDocxParagraphsDTO request) {
        return ApiResponse.success("已生成新版本", docxDocumentService.formatParagraphs(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 将已有 PNG/JPEG 文件版本以内嵌方式追加到指定 DOCX 段落。 */
    @PostMapping("/{fileId}/versions/{versionNo}/inline-images")
    public ApiResponse<VersionVO> insertDocxImages(@PathVariable Long fileId,
                                                   @PathVariable Integer versionNo,
                                                   @Valid @RequestBody InsertDocxImagesDTO request) {
        return ApiResponse.success("已生成新版本", docxDocumentService.insertImages(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /**
     * 恢复到历史版本。
     *
     * <p>不覆盖历史：会新建一个 changeType=RESTORE 的版本，因此该操作本身也可被再次撤销。
     *
     * @param expectVersion 可选，传入当前最新版本号以避免并发覆盖
     */
    @PostMapping("/{fileId}/versions/{versionNo}/restore")
    public ApiResponse<VersionVO> restore(@PathVariable Long fileId,
                                          @PathVariable Integer versionNo,
                                          @RequestParam(required = false) Integer expectVersion) {
        return ApiResponse.success("已恢复",
                versionService.restore(SecurityUtil.requireUserId(), fileId, versionNo, expectVersion));
    }

    // ---------------------------------------------------------------- 内部

    private static ResponseEntity<Resource> toResponse(FileDownload download) {
        // filename* 使用 UTF-8 编码，避免中文文件名在部分客户端乱码
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
