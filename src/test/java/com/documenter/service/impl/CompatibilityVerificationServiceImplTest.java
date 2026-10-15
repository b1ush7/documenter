package com.documenter.service.impl;

import com.documenter.component.LibreOfficeKitRenderService;
import com.documenter.configuration.PreviewProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.DocxCompatibilityReportVO;
import com.documenter.vo.DocxCompatibilityReportVO.Issue;
import com.documenter.vo.DocxCompatibilityReportVO.Severity;
import com.documenter.vo.DocumentRenderManifest;
import com.documenter.vo.FileDownload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOCX 应用级兼容性验收测试（TODO 4.1）。
 *
 * <p>验证的要点：
 * <ol>
 *   <li>与预览一致：<b>绑定明确版本</b> + <b>同等鉴权</b>（复用下载路径）；</li>
 *   <li>无法渲染是 ERROR（验收不通过），缺失字体与边缘内容只是 WARNING；</li>
 *   <li>页数为 0 能识别出「可解析但排不出内容」这种隐性故障；</li>
 *   <li>未接入渲染后端时返回 503 与可操作提示，而不是假装通过。</li>
 * </ol>
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class CompatibilityVerificationServiceImplTest {

    private static final Long OWNER = 1001L;
    private static final Long OTHER = 2002L;
    private static final Long FILE_ID = 10L;
    private static final Integer VERSION_NO = 3;

    private DocumentVersionService versionService;
    private OfficeRenderService renderService;
    private CompatibilityVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        versionService = mock(DocumentVersionService.class);
        renderService = mock(OfficeRenderService.class);

        PreviewProperties properties = new PreviewProperties();
        properties.setCompatibilityMaxPages(5);
        properties.setCompatibilityDpi(110);
        properties.setClippingMarginPixels(2);

        service = new CompatibilityVerificationServiceImpl(versionService, renderService, properties);
    }

    // ==================================================== 绑版本与鉴权

    @Test
    void verificationBindsExplicitVersion() {
        stubDocxVersion(OWNER);
        when(renderService.available()).thenReturn(true);
        when(renderService.backendName()).thenReturn("libreoffice-kit");
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(manifest(1, List.of()));

        service.verify(OWNER, FILE_ID, VERSION_NO);

        verify(versionService).downloadVersion(OWNER, FILE_ID, VERSION_NO);
    }

    @Test
    void ownershipCheckIsDelegatedToDownloadPath() {
        when(versionService.downloadVersion(OTHER, FILE_ID, VERSION_NO))
                .thenThrow(new BusinessException(404, "file not found"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.verify(OTHER, FILE_ID, VERSION_NO));

        // 他人资源统一 404，不暴露「该文件真实存在」
        assertEquals(404, error.getCode());
        // 越权时绝不能触达渲染
        verify(renderService, never()).render(any(), any(), any(), any(), anyInt());
    }

    @Test
    void unavailableRendererReturns503WithHint() {
        stubDocxVersion(OWNER);
        when(renderService.available()).thenReturn(false);
        when(renderService.configurationHint()).thenReturn("请设置 res.preview.office-backend=kit");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.verify(OWNER, FILE_ID, VERSION_NO));

        // 未接入后端时如实报错，而不是返回「验收通过」
        assertEquals(503, error.getCode());
        assertTrue(error.getMessage().contains("office-backend"));
    }

    @Test
    void nonDocxVersionIsRejected() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                        new byte[]{1, 2, 3}));
        when(renderService.available()).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.verify(OWNER, FILE_ID, VERSION_NO));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("XLSX"), "应说明实际类型: " + error.getMessage());
    }

    // ==================================================== 报告结论

    @Test
    void cleanDocumentIsCompatible() {
        stubDocxVersion(OWNER);
        stubRenderer();
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(manifest(2, List.of()));

        DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

        assertTrue(report.compatible());
        assertFalse(report.hasError());
        assertEquals(2, report.pageCount());
        assertTrue(report.issues().isEmpty(), "干净文档不应有问题: " + report.issues());
    }

    @Test
    void zeroPagesIsAnError() {
        stubDocxVersion(OWNER);
        stubRenderer();
        // 能渲染但排不出页面：典型表现是文档结构可解析、内容却显示不出来
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(new DocumentRenderManifest("libreoffice-kit", 0, List.of(), List.of()));

        DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

        assertFalse(report.compatible());
        assertTrue(report.hasError());
        assertTrue(hasIssue(report, "NO_PAGES"));
    }

    @Test
    void missingFontsIsWarningNotError() {
        stubDocxVersion(OWNER);
        stubRenderer();
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(manifest(1, List.of("Courier New", "宋体")));

        DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

        // 字体被替换会让版式偏，但文档仍可用，因此不阻断验收
        assertTrue(report.compatible());
        assertTrue(hasIssue(report, "MISSING_FONTS"));
        assertEquals(2, report.missingFonts().size());
        assertTrue(report.issues().stream()
                .anyMatch(i -> "MISSING_FONTS".equals(i.code()) && Severity.WARNING == i.severity()));
    }

    @Test
    void pageCountBeyondLimitIsReportedAsTruncated() {
        stubDocxVersion(OWNER);
        stubRenderer();
        // 上限设为 5，文档有 20 页
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(manifest(20, List.of()));

        DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

        // 必须如实说明「只检查了前几页」，否则调用方会误以为整篇都验收过
        assertTrue(hasIssue(report, "PAGES_TRUNCATED"));
        assertEquals(20, report.pageCount());
    }

    @Test
    void contentTouchingBorderIsReportedAsWarning() throws IOException {
        stubDocxVersion(OWNER);
        stubRenderer();

        Path directory = Files.createTempDirectory("compat-test-");
        try {
            // 造一张「内容贴到边缘」的图：左上角有黑色像素
            Path imageWithInk = directory.resolve("ink.png");
            writeImage(imageWithInk, true);
            // 再一张全白的图：边缘无墨迹
            Path imageClean = directory.resolve("clean.png");
            writeImage(imageClean, false);

            when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                    .thenReturn(new DocumentRenderManifest("libreoffice-kit", 2,
                            List.of(
                                    new DocumentRenderManifest.Page(1, imageWithInk.toString(), 100, 100, 100L),
                                    new DocumentRenderManifest.Page(2, imageClean.toString(), 100, 100, 100L)),
                            List.of()));

            DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

            assertTrue(hasIssue(report, "CONTENT_NEAR_BORDER"));
            // 只有第 1 页有问题
            List<Issue> borderIssues = report.issues().stream()
                    .filter(i -> "CONTENT_NEAR_BORDER".equals(i.code()))
                    .toList();
            assertEquals(1, borderIssues.size());
            assertTrue(borderIssues.get(0).message().contains("第 1 页"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void clippingDetectionCanBeDisabled() throws IOException {
        stubDocxVersion(OWNER);
        stubRenderer();

        PreviewProperties disabled = new PreviewProperties();
        disabled.setCompatibilityMaxPages(5);
        disabled.setClippingMarginPixels(0);
        CompatibilityVerificationServiceImpl noClip =
                new CompatibilityVerificationServiceImpl(versionService, renderService, disabled);

        Path directory = Files.createTempDirectory("compat-noclip-");
        try {
            Path imageWithInk = directory.resolve("ink.png");
            writeImage(imageWithInk, true);
            when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                    .thenReturn(new DocumentRenderManifest("libreoffice-kit", 1,
                            List.of(new DocumentRenderManifest.Page(1, imageWithInk.toString(),
                                    100, 100, 100L)), List.of()));

            DocxCompatibilityReportVO report = noClip.verify(OWNER, FILE_ID, VERSION_NO);

            assertFalse(hasIssue(report, "CONTENT_NEAR_BORDER"));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void reportIncludesEngineAndRenderedPageCount() {
        stubDocxVersion(OWNER);
        stubRenderer();
        when(renderService.render(any(), eq("docx"), any(), any(), anyInt()))
                .thenReturn(manifest(3, List.of()));

        DocxCompatibilityReportVO report = service.verify(OWNER, FILE_ID, VERSION_NO);

        assertEquals("libreoffice-kit", report.engine());
        assertEquals(3, report.pageCount());
        assertEquals(3, report.renderedPages());
        assertEquals(FILE_ID, report.fileId());
        assertEquals(VERSION_NO, report.versionNo());
    }

    // ==================================================== 真实渲染后端验收

    /**
     * 用真实的 Office 渲染后端（LibreOffice kit）验收 Documenter 自己生成的 DOCX（TODO 4.1）。
     *
     * <p>这是「应用级打开与版式检查」的自动化落地：
     * 前面的用例都在验证报告逻辑，这一条验证的是<b>渲染链路真的能打开我们的产物</b>。
     *
     * <p>为什么必须用我们自己生成的文档而不是外部样本：POI 能重新打开只说明 ZIP 与 XML 没问题，
     * 而这里要回答的是「办公软件能不能把它排出正确的版面」。
     *
     * <p>渲染后端不可用时跳过而不是失败：CI 或精简容器里可能没有 kit，
     * 那种环境下本用例没有意义，报失败会变成噪音。
     */
    @Test
    void generatedDocumentActuallyRendersInOfficeBackend() throws IOException {
        LibreOfficeKitRenderService renderService = kitRenderService();
        Assumptions.assumeTrue(renderService.available(),
                "Office kit 不可用，跳过真实渲染验收");

        byte[] docx = buildSampleDocx();
        Path workspace = Files.createTempDirectory("compat-real-");
        try {
            Path source = workspace.resolve("sample.docx");
            Files.write(source, docx);

            // 让后端真正打开并排版
            DocumentRenderManifest manifest = renderService.render(source, "docx",
                    workspace.resolve("render"), List.of(1), 110);

            assertTrue(manifest.pageCount() >= 1,
                    "生成的 DOCX 应至少排出 1 页，实际 " + manifest.pageCount());
            assertFalse(manifest.pages().isEmpty(), "应产出页面图片");
            DocumentRenderManifest.Page firstPage = manifest.pages().get(0);
            assertTrue(firstPage.width() > 0 && firstPage.height() > 0, "页面应有像素尺寸");
            assertTrue(Files.isRegularFile(Path.of(firstPage.path())), "页面图片应真实存在");
            assertTrue(firstPage.byteLength() > 0, "页面图片不应为空");

            // 缺失字体是版式偏差的信号，这里只记录不判定失败：
            // 渲染环境缺 Courier 等字体是常见情况，不代表文档有问题
            if (!manifest.missingFonts().isEmpty()) {
                System.out.println("[兼容性验收] 渲染时缺失字体: " + manifest.missingFonts());
            }

            // 同一份内容转 PDF 也必须成功，这是后续版式预览的基础
            Path pdf = renderService.toPdf(source, "docx", workspace.resolve("pdf"));
            assertTrue(Files.size(pdf) > 0, "转换出的 PDF 不应为空");

            // 损坏内容必须被识别，而不是产出一个空白的「成功」结果
            Path broken = workspace.resolve("broken.docx");
            Files.write(broken, new byte[]{1, 2, 3, 4, 5});
            BusinessException error = assertThrows(BusinessException.class,
                    () -> renderService.render(broken, "docx", workspace.resolve("bad"),
                            List.of(1), 110));
            assertEquals(422, error.getCode(),
                    "损坏文档应映射为 422（文档问题）而不是 5xx（服务端问题）");
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** 构造一个包含标题、段落、表格与分页的 DOCX，与真实生成路径使用同一套 POI。 */
    private static byte[] buildSampleDocx() throws IOException {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {

            org.apache.poi.xwpf.usermodel.XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Documenter 兼容性验收样本");

            document.createParagraph().createRun().setText("这是由 Documenter 生成的段落，用于验收排版。");

            org.apache.poi.xwpf.usermodel.XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("列 A");
            table.getRow(0).getCell(1).setText("列 B");
            table.getRow(1).getCell(0).setText("1");
            table.getRow(1).getCell(1).setText("2");

            document.createParagraph().createRun().addBreak(
                    org.apache.poi.xwpf.usermodel.BreakType.PAGE);
            document.createParagraph().createRun().setText("第二页内容");

            document.write(buffer);
            return buffer.toByteArray();
        }
    }

    /** 按与生产配置相同的方式构造 kit 后端，确保验收走的是真实链路。 */
    private static LibreOfficeKitRenderService kitRenderService() {
        PreviewProperties properties = new PreviewProperties();
        properties.setOfficeBackend("kit");
        properties.setOfficeTimeout(java.time.Duration.ofSeconds(120));
        properties.setOfficeMaxInputBytes(50L * 1024 * 1024);

        LibreOfficeKitRenderService service = new LibreOfficeKitRenderService(properties);
        try {
            java.lang.reflect.Method init =
                    LibreOfficeKitRenderService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法初始化渲染后端", e);
        }
        return service;
    }

    // ==================================================== 夹具

    private void stubDocxVersion(Long userId) {
        when(versionService.downloadVersion(userId, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_CONTENT_TYPE), new byte[]{1, 2, 3}));
    }

    private void stubRenderer() {
        when(renderService.available()).thenReturn(true);
        when(renderService.backendName()).thenReturn("libreoffice-kit");
        when(renderService.configurationHint()).thenReturn("hint");
    }

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** 造一个页数与图片都真实存在的清单：全白页面，因此不应触发边缘裁切告警。 */
    private static DocumentRenderManifest manifest(int pageCount, List<String> missingFonts) {
        try {
            List<DocumentRenderManifest.Page> pages = new ArrayList<>();
            if (pageCount > 0) {
                Path directory = Files.createTempDirectory("compat-manifest-");
                for (int page = 1; page <= pageCount; page++) {
                    Path image = directory.resolve("page-" + page + ".png");
                    writeImage(image, false);
                    pages.add(new DocumentRenderManifest.Page(page, image.toString(), 100, 100, 100L));
                }
            }
            return new DocumentRenderManifest("libreoffice-kit", pageCount, pages, missingFonts);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** withInk=true 时在左上角画黑点，模拟内容贴边。 */
    private static void writeImage(Path path, boolean withInk) throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 100; x++) {
            for (int y = 0; y < 100; y++) {
                image.setRGB(x, y, 0xFFFFFF);
            }
        }
        if (withInk) {
            image.setRGB(0, 0, 0x000000);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        Files.write(path, buffer.toByteArray());
    }

    private static boolean hasIssue(DocxCompatibilityReportVO report, String code) {
        return report.issues().stream().anyMatch(issue -> code.equals(issue.code()));
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 测试清理失败无需处理
                }
            });
        } catch (IOException ignored) {
            // 测试清理失败无需处理
        }
    }

    private static FileAsset asset(String extension, String contentType) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(OWNER);
        asset.setExtension(extension);
        asset.setContentType(contentType);
        asset.setDisplayName("样本." + extension);
        asset.setSha256("c".repeat(64));
        asset.setLatestVersion(VERSION_NO);
        return asset;
    }

    private static FileDownload download(FileAsset asset, byte[] content) {
        return FileDownload.of(asset, new ByteArrayInputStream(content), content.length);
    }
}
