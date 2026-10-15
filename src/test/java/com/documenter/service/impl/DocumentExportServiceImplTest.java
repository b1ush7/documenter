package com.documenter.service.impl;

import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.FileDownload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出 PDF 测试（TODO 4.3「分别下载原文件、修改版 DOCX 和导出的 PDF」）。
 *
 * <p>验证要点：
 * <ol>
 *   <li>绑定明确版本、复用下载路径鉴权；</li>
 *   <li>已是 PDF 的版本直接返回原内容，不做无意义的再转换；</li>
 *   <li><b>转换后的响应元数据必须改成 PDF</b> —— 只改文件名不改 MIME 时，
 *       浏览器会按 Word 文档处理一个实际是 PDF 的字节流，表现为「下载的文件打不开」，
 *       而服务端看起来一切正常；</li>
 *   <li>未接入渲染后端时返回 503，而不是 500。</li>
 * </ol>
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class DocumentExportServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 10L;
    private static final Integer VERSION_NO = 2;

    private DocumentVersionService versionService;
    private OfficeRenderService renderService;
    private DocumentExportServiceImpl service;

    /** 转换桩创建的目录，测试结束后统一清理。 */
    private final List<Path> createdDirectories = new ArrayList<>();

    @BeforeEach
    void setUp() {
        versionService = mock(DocumentVersionService.class);
        renderService = mock(OfficeRenderService.class);
        service = new DocumentExportServiceImpl(versionService, renderService);
    }

    @AfterEach
    void tearDown() {
        createdDirectories.forEach(DocumentExportServiceImplTest::deleteRecursively);
        createdDirectories.clear();
    }

    // ============================================ 已是 PDF

    @Test
    void pdfVersionIsReturnedAsIsWithoutConversion() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf", "报告.pdf"), new byte[]{1, 2, 3}));

        FileDownload result = service.exportPdf(USER_ID, FILE_ID, VERSION_NO);

        assertEquals("application/pdf", result.contentType());
        // 已经是 PDF 就不应再走转换
        verify(renderService, never()).toPdf(any(), anyString(), any());
    }

    // ============================================ DOCX 转换

    @Test
    void docxIsConvertedAndMetadataReflectsPdf() throws IOException {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_TYPE, "报告.docx"), new byte[]{1, 2, 3}));
        when(renderService.available()).thenReturn(true);
        // toPdf 会把产物写到传入目录下，这里真实落一个文件；
        // 记录创建的目录以便测试结束时清理，避免测试自身泄漏临时文件
        when(renderService.toPdf(any(), eq("docx"), any())).thenAnswer(invocation -> {
            Path outputDir = invocation.getArgument(2);
            createdDirectories.add(outputDir);
            Files.createDirectories(outputDir);
            Path pdf = outputDir.resolve("out.pdf");
            Files.write(pdf, new byte[]{37, 80, 68, 70}); // %PDF
            return pdf;
        });

        FileDownload result = service.exportPdf(USER_ID, FILE_ID, VERSION_NO);

        // 关键：MIME 必须变成 PDF，否则浏览器会按 Word 文档处理 PDF 字节流
        assertEquals("application/pdf", result.contentType());
        assertEquals("pdf", result.asset().getExtension());
        // 关键：下载文件名后缀也必须同步改成 pdf
        assertEquals("报告.pdf", result.downloadFileName());
        assertEquals(4, result.contentLength());
        try (var input = result.resource().getInputStream()) {
            assertEquals(4, input.readAllBytes().length);
        }
    }

    @Test
    void exportBindsExplicitVersion() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf", "报告.pdf"), new byte[]{1}));

        service.exportPdf(USER_ID, FILE_ID, VERSION_NO);

        // 导出与预览一样必须绑定明确版本，否则会与用户正在查看的版本不一致
        verify(versionService).downloadVersion(USER_ID, FILE_ID, VERSION_NO);
    }

    @Test
    void ownershipIsEnforcedThroughTheDownloadPath() {
        Long other = 2002L;
        when(versionService.downloadVersion(other, FILE_ID, VERSION_NO))
                .thenThrow(new BusinessException(404, "版本不存在"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.exportPdf(other, FILE_ID, VERSION_NO));

        assertEquals(404, error.getCode());
        verify(renderService, never()).toPdf(any(), anyString(), any());
    }

    // ============================================ 失败路径

    @Test
    void unavailableRendererReturns503() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_TYPE, "报告.docx"), new byte[]{1, 2, 3}));
        when(renderService.available()).thenReturn(false);
        when(renderService.configurationHint()).thenReturn("请配置 res.preview.office-backend=kit");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.exportPdf(USER_ID, FILE_ID, VERSION_NO));

        // 503 而非 500：这是部署未配置，运维应去接渲染后端而不是查代码
        assertEquals(503, error.getCode());
        assertTrue(error.getMessage().contains("office-backend"));
    }

    @Test
    void unknownFormatIsRejectedWith400() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("bin", "application/octet-stream", "未知.bin"),
                        new byte[]{1, 2, 3}));
        when(renderService.available()).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.exportPdf(USER_ID, FILE_ID, VERSION_NO));

        assertEquals(400, error.getCode());
    }

    @Test
    void conversionFailureIsWrappedAsBusinessError() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_TYPE, "报告.docx"), new byte[]{1, 2, 3}));
        when(renderService.available()).thenReturn(true);
        when(renderService.toPdf(any(), anyString(), any()))
                .thenThrow(new BusinessException(422, "文档结构无效"));

        // 渲染层的业务异常应原样透出，而不是被包成 500
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.exportPdf(USER_ID, FILE_ID, VERSION_NO));

        assertEquals(422, error.getCode());
    }

    // ============================================ 夹具

    private static final String DOCX_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static FileAsset asset(String extension, String contentType, String displayName) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setExtension(extension);
        asset.setContentType(contentType);
        asset.setDisplayName(displayName);
        asset.setOriginalName(displayName);
        asset.setSha256("e".repeat(64));
        asset.setStatus("READY");
        asset.setSource("UPLOAD");
        asset.setLatestVersion(VERSION_NO);
        return asset;
    }

    private static FileDownload download(FileAsset asset, byte[] content) {
        return FileDownload.of(asset, new ByteArrayInputStream(content), content.length);
    }

    /** 递归删除测试产物目录。 */
    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
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
}
