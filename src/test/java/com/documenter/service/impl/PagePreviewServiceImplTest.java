package com.documenter.service.impl;

import com.documenter.configuration.PreviewProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.OfficeRenderService;
import com.documenter.service.DocumentVersionService;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PdfPagePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页面版式预览测试（TODO 3.3「页面预览……绑定明确版本并执行同等鉴权」）。
 *
 * <p>本类专门验证 TODO 点名的两个硬性要求，以及未配置转换器时的降级行为：
 * <ol>
 *   <li><b>绑定明确版本</b>：versionNo 必须一路传到版本服务，
 *       不允许「不传版本就取最新版本」这种模糊语义——那会导致用户
 *       看到 v2 的预览却下载到 v3。</li>
 *   <li><b>同等鉴权</b>：归属校验走与下载<b>同一条路径</b>。
 *       这里断言版本服务被以当前 userId 调用，并验证越权时预览失败而非绕过。</li>
 *   <li><b>未配置转换器</b>：DOCX/XLSX 返回可操作的 503；PDF 与图片不受影响。</li>
 * </ol>
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败；用英文方法名更稳妥。
 */
class PagePreviewServiceImplTest {

    private static final Long OWNER = 1001L;
    private static final Long OTHER = 2002L;
    private static final Long FILE_ID = 10L;
    private static final Integer VERSION_NO = 3;

    private DocumentVersionService versionService;
    private OfficeRenderService renderService;
    private PagePreviewServiceImpl service;

    @BeforeEach
    void setUp() {
        versionService = mock(DocumentVersionService.class);
        renderService = mock(OfficeRenderService.class);

        PreviewProperties previewProperties = new PreviewProperties();
        previewProperties.setDefaultDpi(110);
        previewProperties.setMaxDpi(200);
        previewProperties.setMaxPagePixels(40_000_000L);
        previewProperties.setCacheEnabled(false);

        StorageProperties storageProperties = new StorageProperties();
        storageProperties.getLocal().setTemp(Path.of(System.getProperty("java.io.tmpdir"),
                "preview-test-tmp").toString());

        service = new PagePreviewServiceImpl(versionService, renderService, previewProperties, storageProperties);
    }

    // ============================================ 绑版本与同等鉴权

    @Test
    void describeAlwaysBindsExplicitVersion() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("png", "image/png"), minimalPng()));

        service.describe(OWNER, FILE_ID, VERSION_NO);

        // 关键：明确的版本号必须被传下去，而不是「取最新版本」
        verify(versionService).downloadVersion(OWNER, FILE_ID, VERSION_NO);
    }

    @Test
    void renderPageAlwaysBindsExplicitVersion() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("png", "image/png"), minimalPng()));

        service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, null);

        verify(versionService).downloadVersion(OWNER, FILE_ID, VERSION_NO);
    }

    @Test
    void ownershipCheckIsDelegatedToDownloadPath() {
        // 版本服务对他人文件抛 404，这正是下载路径的归属校验行为
        when(versionService.downloadVersion(OTHER, FILE_ID, VERSION_NO))
                .thenThrow(new BusinessException(404, "file not found"));

        BusinessException infoError = assertThrows(BusinessException.class,
                () -> service.describe(OTHER, FILE_ID, VERSION_NO));
        BusinessException renderError = assertThrows(BusinessException.class,
                () -> service.renderPage(OTHER, FILE_ID, VERSION_NO, 1, null));

        // 他人资源统一 404，不暴露「该文件真实存在」
        assertEquals(404, infoError.getCode());
        assertEquals(404, renderError.getCode());
    }

    @Test
    void describeSurfacesVersionNotFound() {
        when(versionService.downloadVersion(OWNER, FILE_ID, 99))
                .thenThrow(new BusinessException(404, "version not found"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.describe(OWNER, FILE_ID, 99));

        assertEquals(404, error.getCode());
    }

    // ============================================ 未配置转换器时的降级

    @Test
    void docxWithoutOfficeBackendIsReportedAsNotPreviewable() {
        when(renderService.available()).thenReturn(false);
        when(renderService.configurationHint())
                .thenReturn("请配置 res.preview.office-backend=kit 并指向 office-kit-cli");
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_CONTENT_TYPE), new byte[]{1, 2, 3}));

        PagePreviewInfoVO info = service.describe(OWNER, FILE_ID, VERSION_NO);

        // 元信息接口不抛异常：前端需要知道「为什么不能预览」以及怎么修
        assertFalse(info.previewable());
        assertTrue(info.requiresConversion());
        assertNull(info.pageCount());
        assertTrue(info.message().contains("office-backend")
                        || info.message().contains("office-kit"),
                "提示应可操作，实际: " + info.message());
    }

    @Test
    void renderingDocxWithoutOfficeBackendReturns503AndDoesNotRender() {
        when(renderService.available()).thenReturn(false);
        when(renderService.configurationHint()).thenReturn("请配置 res.preview.office-backend=kit");
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("docx", DOCX_CONTENT_TYPE), new byte[]{1, 2, 3}));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, null));

        // 503 而非 500：这是「部署未配置」，运维应去接渲染后端而不是查代码
        assertEquals(503, error.getCode());
        // 未接入时绝不能尝试渲染
        verify(renderService, never()).render(any(), anyString(), any(), any(), anyInt());
    }

    @Test
    void pdfIsPreviewableWithoutOfficeBackend() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf"), minimalPdf()));

        PagePreviewInfoVO info = service.describe(OWNER, FILE_ID, VERSION_NO);

        // PDF 就地渲染，不应因为缺少外部渲染后端而受影响
        assertTrue(info.previewable());
        assertFalse(info.requiresConversion());
        assertEquals("native", info.engine());
        assertEquals(1, info.pageCount());
        // 完全不触碰渲染后端
        verify(renderService, never()).available();
        verify(renderService, never()).render(any(), anyString(), any(), any(), anyInt());
    }

    @Test
    void pdfPageIsRenderedAsPng() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf"), minimalPdf()));

        PdfPagePreview preview = service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, null);

        assertEquals(1, preview.pageNo());
        assertEquals(110, preview.dpi());
        assertTrue(preview.widthPixels() > 0);
        assertTrue(preview.heightPixels() > 0);
        assertTrue(preview.content().length > 0, "应产出 PNG 内容");
        assertEquals("page-1.png", preview.fileName());
    }

    @Test
    void imagePreviewReturnsOriginalBytesAndDimensions() {
        byte[] png = minimalPng();
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("png", "image/png"), png));

        PdfPagePreview preview = service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, null);

        assertEquals(1, preview.pageNo());
        assertEquals(1, preview.widthPixels());
        assertEquals(1, preview.heightPixels());
        // 图片原样回传，不做栅格化，保留原始格式与透明通道
        assertEquals(png.length, preview.content().length);
    }

    @Test
    void imageVersionRejectsSecondPage() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("png", "image/png"), minimalPng()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 2, null));

        assertEquals(400, error.getCode());
    }

    // ============================================ 参数校验

    @Test
    void invalidPageNumberRejectedBeforeTouchingVersionService() {
        assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 0, null));
        assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, -1, null));
        assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, null, null));

        // 参数不合法时不应触达版本服务，避免无谓的鉴权与 IO
        verify(versionService, never()).downloadVersion(anyLong(), anyLong(), any());
    }

    @Test
    void dpiOutsideRangeRejected() {
        BusinessException tooLow = assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, 10));
        BusinessException tooHigh = assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 1, 9999));

        assertEquals(400, tooLow.getCode());
        assertEquals(400, tooHigh.getCode());
        verify(versionService, never()).downloadVersion(anyLong(), anyLong(), any());
    }

    @Test
    void pageBeyondDocumentRangeReturns404() {
        when(versionService.downloadVersion(OWNER, FILE_ID, VERSION_NO))
                .thenReturn(download(asset("pdf", "application/pdf"), minimalPdf()));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.renderPage(OWNER, FILE_ID, VERSION_NO, 5, null));

        assertEquals(404, error.getCode());
    }

    // ============================================ 夹具

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static FileAsset asset(String extension, String contentType) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(OWNER);
        asset.setExtension(extension);
        asset.setContentType(contentType);
        asset.setDisplayName("示例文件." + extension);
        asset.setSha256("a".repeat(64));
        asset.setLatestVersion(VERSION_NO);
        return asset;
    }

    private static FileDownload download(FileAsset asset, byte[] content) {
        return FileDownload.of(asset, new ByteArrayInputStream(content), content.length);
    }

    /** 1×1 PNG，足够让 ImageIO 解出尺寸。 */
    private static byte[] minimalPng() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0xFFFFFF);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 最小单页 PDF。 */
    private static byte[] minimalPdf() {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     new org.apache.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
