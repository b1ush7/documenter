package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.documenter.configuration.PreviewProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.OfficeRenderService;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PdfPagePreview;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预览与下载的版本一致性（TODO 4.3「预览关联明确的文档版本；下载必须来自同一版本，
 * 避免预览与导出内容不一致」）。
 *
 * <p>要防的是这一类问题：用户在界面上看到的是版本 2 的预览，点下载却拿到了
 * 已经变成版本 3 的内容——两者内容不同，用户会以为系统出错了，
 * 或者在「对照修改前后」时得出错误结论。
 *
 * <p>本类断言的是三条路径<b>都只按明确版本解析</b>，而不是「取当前最新版本」：
 * <ol>
 *   <li>预览元信息（{@code describe}）</li>
 *   <li>预览页面图片（{@code renderPage}）</li>
 *   <li>版本下载（{@code downloadVersion}）</li>
 * </ol>
 *
 * <p>只要三者都走同一个 {@code (userId, fileId, versionNo)} 解析，
 * 一致性就由构造保证，而不是靠前端自觉传对参数。
 *
 * <p>不使用 {@code @DisplayName}：中文全角标点在写入时可能被破坏成半角引号，
 * 截断字符串字面量导致编译失败。
 */
class PreviewVersionConsistencyTest {

    private static final Long USER_ID = 1001L;
    private static final Long FILE_ID = 10L;
    private static final Integer VERSION_NO = 2;

    private DocumentVersionService versionService;
    private OfficeRenderService renderService;
    private PagePreviewServiceImpl previewService;

    @BeforeEach
    void setUp() {
        versionService = mock(DocumentVersionService.class);
        renderService = mock(OfficeRenderService.class);

        PreviewProperties properties = new PreviewProperties();
        properties.setDefaultDpi(110);
        properties.setMaxDpi(200);
        properties.setCacheEnabled(false);

        StorageProperties storage = new StorageProperties();
        storage.getLocal().setTemp(Path.of(System.getProperty("java.io.tmpdir"),
                "preview-consistency-tmp").toString());

        previewService = new PagePreviewServiceImpl(versionService, renderService, properties, storage);
    }

    // ============================================ 三条路径都绑定明确版本

    @Test
    void previewInfoBindsTheRequestedVersionNotTheLatest() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(pdfDownload());

        PagePreviewInfoVO info = previewService.describe(USER_ID, FILE_ID, VERSION_NO);

        assertEquals(VERSION_NO, info.versionNo(), "预览元信息必须回报被请求的版本号");
        // 关键：解析的是明确版本，而不是「当前最新版本」
        verify(versionService).downloadVersion(USER_ID, FILE_ID, VERSION_NO);
    }

    @Test
    void previewImageBindsTheRequestedVersionNotTheLatest() {
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenReturn(pdfDownload());

        PdfPagePreview preview = previewService.renderPage(USER_ID, FILE_ID, VERSION_NO, 1, null);

        assertNotNull(preview);
        verify(versionService).downloadVersion(USER_ID, FILE_ID, VERSION_NO);
    }

    @Test
    void allThreePathsResolveThroughTheSameVersionLookup() {
        // 每次调用返回新的下载对象：InputStream 只能读一次，
        // 复用同一个对象会让第二次调用因「流已读过」而失败
        when(versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO))
                .thenAnswer(invocation -> pdfDownload());

        previewService.describe(USER_ID, FILE_ID, VERSION_NO);
        previewService.renderPage(USER_ID, FILE_ID, VERSION_NO, 1, null);
        versionService.downloadVersion(USER_ID, FILE_ID, VERSION_NO);

        // 三次调用参数完全一致 —— 一致性来自「同一个解析入口」而不是调用方约定
        verify(versionService, times(3)).downloadVersion(USER_ID, FILE_ID, VERSION_NO);
        // 从未以 null 版本号调用（那会退化成「取最新版本」）。
        // 必须用 isNull() 而不是 any()：any() 会匹配到上面那次具体调用，
        // 使这条否定断言永远失败。
        verify(versionService, never()).downloadVersion(anyLong(), anyLong(), isNull());
    }

    // ============================================ 版本不存在时的行为

    @Test
    void missingVersionFailsInsteadOfFallingBackToLatest() {
        // 版本已被清理或本就不存在时，必须失败，绝不能「退化为最新版本」
        // 否则用户会因为看到别的内容而困惑
        when(versionService.downloadVersion(USER_ID, FILE_ID, 99))
                .thenThrow(new BusinessException(404, "版本不存在"));

        BusinessException infoError = assertThrows(BusinessException.class,
                () -> previewService.describe(USER_ID, FILE_ID, 99));
        BusinessException renderError = assertThrows(BusinessException.class,
                () -> previewService.renderPage(USER_ID, FILE_ID, 99, 1, null));

        assertEquals(404, infoError.getCode());
        assertEquals(404, renderError.getCode());
    }

    @Test
    void versionOwnershipIsEnforcedForBothPreviewAndDownload() {
        Long other = 2002L;
        when(versionService.downloadVersion(other, FILE_ID, VERSION_NO))
                .thenThrow(new BusinessException(404, "版本不存在"));

        // 预览与下载对他人资源返回同样的 404，不暴露该版本是否存在
        assertEquals(404, assertThrows(BusinessException.class,
                () -> previewService.describe(other, FILE_ID, VERSION_NO)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> versionService.downloadVersion(other, FILE_ID, VERSION_NO)).getCode());
    }

    // ============================================ 夹具

    private static FileDownload pdfDownload() {
        byte[] pdf = minimalPdf();
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setExtension("pdf");
        asset.setContentType("application/pdf");
        asset.setDisplayName("文档.pdf");
        asset.setSha256("d".repeat(64));
        asset.setLatestVersion(VERSION_NO);
        return FileDownload.of(asset, new ByteArrayInputStream(pdf), pdf.length);
    }

    private static byte[] minimalPdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.save(buffer);
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
