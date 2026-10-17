package com.documenter.controller;

import com.documenter.constant.ApiResponse;
import com.documenter.dto.ConvertPdfToDocxDTO;
import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.EditImageDTO;
import com.documenter.dto.EditXlsxDataDTO;
import com.documenter.dto.EditXlsxSheetsDTO;
import com.documenter.dto.EditXlsxStructureDTO;
import com.documenter.dto.FormatXlsxCellsDTO;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.dto.GenerateDocxDTO;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.dto.InsertXlsxImagesDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.dto.RenameFileDTO;
import com.documenter.dto.UpdateDocxHeaderFooterLinkDTO;
import com.documenter.service.CompatibilityVerificationService;
import com.documenter.service.DocumentComparisonService;
import com.documenter.service.DocumentExportService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileService;
import com.documenter.service.PagePreviewService;
import com.documenter.service.PdfDocumentService;
import com.documenter.service.PdfToDocxService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.service.ImageDocumentService;
import com.documenter.util.PreviewResponses;
import com.documenter.util.SecurityUtil;
import com.documenter.vo.FileAssetVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.DocxCompatibilityReportVO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.DocxHeaderFooterLinksVO;
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PageResult;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import com.documenter.vo.VersionComparisonVO;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxStructureVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxFormulaResultsVO;
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
    private final PdfDocumentService pdfDocumentService;
    private final PdfToDocxService pdfToDocxService;
    private final XlsxDocumentService xlsxDocumentService;
    private final ImageDocumentService imageDocumentService;
    private final PagePreviewService pagePreviewService;
    private final CompatibilityVerificationService compatibilityVerificationService;
    private final DocumentComparisonService comparisonService;
    private final DocumentExportService exportService;

    public FileController(FileService fileService, DocumentVersionService versionService,
                          DocxDocumentService docxDocumentService,
                          PdfDocumentService pdfDocumentService,
                          PdfToDocxService pdfToDocxService,
                          XlsxDocumentService xlsxDocumentService,
                          ImageDocumentService imageDocumentService,
                          PagePreviewService pagePreviewService,
                          CompatibilityVerificationService compatibilityVerificationService,
                          DocumentComparisonService comparisonService,
                          DocumentExportService exportService) {
        this.fileService = fileService;
        this.versionService = versionService;
        this.docxDocumentService = docxDocumentService;
        this.pdfDocumentService = pdfDocumentService;
        this.pdfToDocxService = pdfToDocxService;
        this.xlsxDocumentService = xlsxDocumentService;
        this.imageDocumentService = imageDocumentService;
        this.pagePreviewService = pagePreviewService;
        this.compatibilityVerificationService = compatibilityVerificationService;
        this.comparisonService = comparisonService;
        this.exportService = exportService;
    }

    // ---------------------------------------------------------------- 文件

    /** 使用结构块或启用的 DOCX 模板生成新文件，两种模式不能混用。 */
    @PostMapping("/docx/generate")
    public ApiResponse<FileAssetVO> generateDocx(@Valid @RequestBody GenerateDocxDTO request) {
        return ApiResponse.success("生成成功", docxDocumentService.generate(
                SecurityUtil.requireUserId(), request));
    }

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

    /** 提取 PDF 原生文本并标记需要 OCR 的扫描页，不执行 OCR。 */
    @GetMapping("/{fileId}/versions/{versionNo}/pdf/content")
    public ApiResponse<PdfContentVO> readPdfContent(@PathVariable Long fileId,
                                                    @PathVariable Integer versionNo) {
        return ApiResponse.success(pdfDocumentService.readContent(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /** 将明确 PDF 版本重建为可编辑 DOCX，并保存为同一文件的新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/pdf/convert-to-docx")
    public ApiResponse<VersionVO> convertPdfToDocx(@PathVariable Long fileId,
                                                   @PathVariable Integer versionNo,
                                                   @Valid @RequestBody ConvertPdfToDocxDTO request) {
        return ApiResponse.success("已生成可编辑 DOCX 版本", pdfToDocxService.convert(
                SecurityUtil.requireUserId(), fileId, versionNo, request, null, null));
    }

    /** 将一个明确 PDF 版本的指定页渲染为 PNG。 */
    @GetMapping("/{fileId}/versions/{versionNo}/pdf/pages/{pageNo}/preview")
    public ResponseEntity<byte[]> previewPdfPage(@PathVariable Long fileId,
                                                 @PathVariable Integer versionNo,
                                                 @PathVariable Integer pageNo,
                                                 @RequestParam(required = false) Integer dpi) {
        return PreviewResponses.png(pdfDocumentService.renderPage(
                SecurityUtil.requireUserId(), fileId, versionNo, pageNo, dpi));
    }

    /**
     * 通用页面版式预览元信息（TODO 3.3）。
     *
     * <p>前端先调本接口拿到页数与可预览性，再按页请求图片。
     * DOCX / XLSX 需要外部转换器，未配置时 {@code previewable=false} 且 {@code message} 给出配置方法。
     */
    @GetMapping("/{fileId}/versions/{versionNo}/preview")
    public ApiResponse<PagePreviewInfoVO> previewInfo(@PathVariable Long fileId,
                                                      @PathVariable Integer versionNo) {
        return ApiResponse.success(pagePreviewService.describe(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /**
     * 通用页面版式预览图片（TODO 3.3）。
     *
     * <p>与 PDF 专用预览接口的区别：本接口接受任意受支持格式，内部按需转换。
     * 两个接口都<b>绑定明确版本</b>并复用下载路径做归属校验，不存在鉴权差异。
     */
    @GetMapping("/{fileId}/versions/{versionNo}/pages/{pageNo}/preview")
    public ResponseEntity<byte[]> previewPage(@PathVariable Long fileId,
                                              @PathVariable Integer versionNo,
                                              @PathVariable Integer pageNo,
                                              @RequestParam(required = false) Integer dpi) {
        Long userId = SecurityUtil.requireUserId();
        PagePreviewInfoVO info = pagePreviewService.describe(userId, fileId, versionNo);
        PdfPagePreview preview = pagePreviewService.renderPage(userId, fileId, versionNo, pageNo, dpi);

        // 图片版本：原样回传以保留原始格式与透明通道。
        // 判断依据用版本自身的扩展名，而不是渲染结果的文件名后缀——后者是内部构造值，
        // 一旦命名规则调整就会静默退化成 PNG。
        if (isImageExtension(info.extension())) {
            return PreviewResponses.originalImage(preview,
                    MediaType.parseMediaType(info.contentType()));
        }
        return PreviewResponses.png(preview);
    }

    /** 读取指定 XLSX 版本的工作表、单元格、样式、公式、合并区域与图表结构。 */
    @GetMapping("/{fileId}/versions/{versionNo}/xlsx/structure")
    public ApiResponse<XlsxStructureVO> readXlsxStructure(@PathVariable Long fileId,
                                                           @PathVariable Integer versionNo) {
        return ApiResponse.success(xlsxDocumentService.readStructure(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /** 读取指定 XLSX 版本中的一个矩形区域，包含区域内未创建的空白位置。 */
    @GetMapping("/{fileId}/versions/{versionNo}/xlsx/range")
    public ApiResponse<XlsxRangeVO> readXlsxRange(@PathVariable Long fileId,
                                                  @PathVariable Integer versionNo,
                                                  @RequestParam String sheetName,
                                                  @RequestParam String range) {
        return ApiResponse.success(xlsxDocumentService.readRange(
                SecurityUtil.requireUserId(), fileId, versionNo, sheetName, range));
    }

    /** 即时计算指定 XLSX 区域内的公式，返回结果或错误，不保存新版本。 */
    @GetMapping("/{fileId}/versions/{versionNo}/xlsx/formula-results")
    public ApiResponse<XlsxFormulaResultsVO> calculateXlsxFormulas(@PathVariable Long fileId,
                                                                  @PathVariable Integer versionNo,
                                                                  @RequestParam String sheetName,
                                                                  @RequestParam String range) {
        return ApiResponse.success(xlsxDocumentService.calculateFormulas(
                SecurityUtil.requireUserId(), fileId, versionNo, sheetName, range));
    }

    /** 修改指定 XLSX 版本中的单元格值或公式，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/cell-edits")
    public ApiResponse<VersionVO> editXlsxCells(@PathVariable Long fileId,
                                                @PathVariable Integer versionNo,
                                                @Valid @RequestBody EditXlsxCellsDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.editCells(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 内嵌当前用户图片的明确版本，源版本同时作为乐观锁期望值。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/images")
    public ApiResponse<VersionVO> insertXlsxImages(@PathVariable Long fileId,
                                                  @PathVariable Integer versionNo,
                                                  @Valid @RequestBody InsertXlsxImagesDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.insertImages(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 插入或删除指定 XLSX 版本中的行列，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/structure-edits")
    public ApiResponse<VersionVO> editXlsxStructure(@PathVariable Long fileId,
                                                    @PathVariable Integer versionNo,
                                                    @Valid @RequestBody EditXlsxStructureDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.editStructure(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 新增、重命名或删除工作表，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/sheet-edits")
    public ApiResponse<VersionVO> editXlsxSheets(@PathVariable Long fileId,
                                                 @PathVariable Integer versionNo,
                                                 @Valid @RequestBody EditXlsxSheetsDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.editSheets(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 对安全的纯值区域排序，或设置、清除工作表自动筛选，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/data-edits")
    public ApiResponse<VersionVO> editXlsxData(@PathVariable Long fileId,
                                               @PathVariable Integer versionNo,
                                               @Valid @RequestBody EditXlsxDataDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.editData(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 设置单元格或矩形区域的基础格式，并保存为新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/xlsx/formats")
    public ApiResponse<VersionVO> formatXlsxCells(@PathVariable Long fileId,
                                                  @PathVariable Integer versionNo,
                                                  @Valid @RequestBody FormatXlsxCellsDTO request) {
        return ApiResponse.success("已生成新版本", xlsxDocumentService.formatCells(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 编辑 PNG/JPEG 像素或转换格式，保存为带独立格式信息的新版本。 */
    @PostMapping("/{fileId}/versions/{versionNo}/image-edits")
    public ApiResponse<VersionVO> editImage(@PathVariable Long fileId,
                                           @PathVariable Integer versionNo,
                                           @Valid @RequestBody EditImageDTO request) {
        return ApiResponse.success("已生成新版本", imageDocumentService.edit(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /**
     * 批量替换指定 DOCX 版本中的简单段落，并保存为新版本。
     *
     * <p><b>路径中的 {@code versionNo} 同时是编辑基准版本与乐观锁的期望版本</b>（TODO 4.1）。
     * 也就是说这次编辑声明的是「我在 {@code versionNo} 的基础上修改」：
     * <ul>
     *   <li>若该文件当前版本已不是 {@code versionNo}（期间其他任务或人工编辑推进了版本），
     *       返回 <b>409</b>，不会覆盖别人的改动；</li>
     *   <li>前端应提示用户刷新后基于最新版本重做这次编辑，而不是自动重试 ——
     *       自动重试会把用户的修改悄悄应用到另一个版本上。</li>
     * </ul>
     */
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

    /** 设置指定 DOCX 版本中的标题、普通段落、列表格式或正文段前分页。 */
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

    /** 设置指定分节页眉或页脚是否链接到上一节。 */
    @PostMapping("/{fileId}/versions/{versionNo}/header-footer-links")
    public ApiResponse<VersionVO> updateHeaderFooterLink(
            @PathVariable Long fileId,
            @PathVariable Integer versionNo,
            @Valid @RequestBody UpdateDocxHeaderFooterLinkDTO request) {
        return ApiResponse.success("页眉页脚链接已更新", docxDocumentService.updateHeaderFooterLink(
                SecurityUtil.requireUserId(), fileId, versionNo, request));
    }

    /** 查询各分节页眉页脚是否链接到上一节。 */
    @GetMapping("/{fileId}/versions/{versionNo}/header-footer-links")
    public ApiResponse<DocxHeaderFooterLinksVO> readHeaderFooterLinks(
            @PathVariable Long fileId, @PathVariable Integer versionNo) {
        return ApiResponse.success(docxDocumentService.readHeaderFooterLinks(
                SecurityUtil.requireUserId(), fileId, versionNo));
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

    /**
     * DOCX 应用级兼容性验收（TODO 4.1）。
     *
     * <p>让 LibreOffice 真正打开并排版该版本，检查页数、缺失字体与页面边缘裁切。
     * 与预览接口一样<b>绑定明确版本</b>并复用下载路径做归属校验。
     *
     * <p>结论包含 ERROR 与 WARNING 两级：ERROR 表示无法正常打开或排不出内容；
     * WARNING 表示可用但版式存在偏差风险（如字体被替换），需人工确认。
     */
    @PostMapping("/{fileId}/versions/{versionNo}/compatibility-check")
    public ApiResponse<DocxCompatibilityReportVO> checkCompatibility(@PathVariable Long fileId,
                                                                     @PathVariable Integer versionNo) {
        return ApiResponse.success(compatibilityVerificationService.verify(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /**
     * 比较同一文件两个版本的修改前后差异（TODO 4.3）。
     *
     * <p>两个版本都必须明确指定 —— 没有「和当前版本比」的语义，
     * 否则用户看到的差异会随着别人编辑而改变。两个版本都经下载路径校验归属，
     * 因此无法用「自己的旧版本 vs 他人的新版本」探测他人内容。
     *
     * <p>比较粒度是<b>块/单元格</b>而不是字符：用户关心「第二段被改了」，
     * 字符级 diff 会把表格与段落改动变成难以阅读的增删碎片。
     */
    @GetMapping("/{fileId}/versions/{fromVersion}/compare/{toVersion}")
    public ApiResponse<VersionComparisonVO> compareVersions(@PathVariable Long fileId,
                                                            @PathVariable Integer fromVersion,
                                                            @PathVariable Integer toVersion) {
        return ApiResponse.success(comparisonService.compare(
                SecurityUtil.requireUserId(), fileId, fromVersion, toVersion));
    }

    /**
     * 将指定版本导出为 PDF（TODO 4.3）。
     *
     * <p>导出与预览共用同一个渲染后端，因此不会出现「预览正常但导出报不支持」。
     * 版本本身已是 PDF 时直接返回原内容，不做无意义的再转换。
     */
    @GetMapping("/{fileId}/versions/{versionNo}/export/pdf")
    public ResponseEntity<Resource> exportPdf(@PathVariable Long fileId,
                                              @PathVariable Integer versionNo) {
        return toResponse(exportService.exportPdf(
                SecurityUtil.requireUserId(), fileId, versionNo));
    }

    /** 与 PagePreviewServiceImpl 的图片扩展名集合保持一致。 */
    private static final java.util.Set<String> PREVIEW_IMAGE_EXTENSIONS =
            java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private static boolean isImageExtension(String extension) {
        return extension != null && PREVIEW_IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }
}
