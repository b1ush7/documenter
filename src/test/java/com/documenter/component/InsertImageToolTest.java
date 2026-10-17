package com.documenter.component;

import com.documenter.component.tool.InsertImageTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.VersionVO;
import com.documenter.vo.FileDownload;
import com.documenter.entity.FileAsset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ByteArrayResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsertImageToolTest {

    private final DocxDocumentService service = mock(DocxDocumentService.class);
    private final AiModelProperties properties = new AiModelProperties();
    private final DocumentVersionService versions = mock(DocumentVersionService.class);

    @BeforeEach
    void setUp() {
        when(versions.downloadVersion(7L, 20L, 2)).thenReturn(image("image/png"));
    }

    @Test
    void defaultConfirmationModeCreatesPendingImagePlanWithoutWritingVersion() {
        var tool = new InsertImageTool(service, properties, versions);

        var result = tool.execute(arguments(), context());

        assertNotNull(result.plan());
        assertEquals("INSERT_IMAGE", result.plan().operations().getFirst().type());
        assertEquals("body/p0", result.plan().operations().getFirst().target());
        assertEquals(20L, result.plan().operations().getFirst().image().fileId());
        assertEquals(2, result.plan().operations().getFirst().image().versionNo());
        assertEquals(400, result.plan().operations().getFirst().image().widthPixels());
        assertEquals(300, result.plan().operations().getFirst().image().heightPixels());
        verify(versions).downloadVersion(7L, 20L, 2);
        verifyNoInteractions(service);
    }

    @Test
    void explicitlyDisabledConfirmationPreservesOwnedVersionBinding() {
        properties.setRequireConfirmation(false);
        var version = new VersionVO();
        version.setVersionNo(4);
        when(service.insertImages(eq(7L), eq(10L), eq(3), any())).thenReturn(version);

        var result = new InsertImageTool(service, properties, versions).execute(arguments(), context());

        var request = ArgumentCaptor.forClass(InsertDocxImagesDTO.class);
        verify(service).insertImages(eq(7L), eq(10L), eq(3), request.capture());
        var image = request.getValue().getOperations().getFirst();
        assertEquals(20L, image.getImageFileId());
        assertEquals(2, image.getImageVersionNo());
        assertEquals("body/p0", image.getBlockId());
        assertTrue(result.text().contains("v4"));
    }

    @Test
    void jpegImageAndExplicitDisplaySizeProduceTheSameConfirmationContract() {
        when(versions.downloadVersion(7L, 20L, 2)).thenReturn(image("image/jpeg"));
        var args = new java.util.HashMap<>(arguments());
        args.put("widthPixels", 640);
        args.put("heightPixels", 480);
        args.put("reason", "illustration");

        var operation = new InsertImageTool(service, properties, versions).execute(args, context())
                .plan().operations().getFirst();

        assertEquals(640, operation.image().widthPixels());
        assertEquals(480, operation.image().heightPixels());
        assertEquals("illustration", operation.reason());
        verifyNoInteractions(service);
    }

    @Test
    void foreignOrMissingImageIsRejectedWithoutSavingAnything() {
        when(versions.downloadVersion(7L, 20L, 2)).thenThrow(new BusinessException(404, "图片不存在"));
        var tool = new InsertImageTool(service, properties, versions);

        var failure = assertThrows(BusinessException.class, () -> tool.execute(arguments(), context()));

        assertEquals(404, failure.getCode());
        verifyNoInteractions(service);
    }

    @Test
    void nonImageVersionCannotBeInserted() {
        when(versions.downloadVersion(7L, 20L, 2)).thenReturn(image("application/pdf"));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> new InsertImageTool(service, properties, versions).execute(arguments(), context())).getCode());
        verifyNoInteractions(service);
    }

    @Test
    void explicitInvalidDimensionsAreNotSilentlyChanged() {
        var tool = new InsertImageTool(service, properties, versions);
        for (int dimension : List.of(-1, 0, 10001)) {
            var args = new java.util.HashMap<>(arguments());
            args.put("widthPixels", dimension);
            assertThrows(BusinessException.class, () -> tool.execute(args, context()));
        }
        verifyNoInteractions(service, versions);
    }

    @Test
    void targetMustBeAnExistingParagraph() {
        var args = new java.util.HashMap<>(arguments());
        args.put("target", "body/p99");
        assertThrows(BusinessException.class,
                () -> new InsertImageTool(service, properties, versions).execute(args, context()));
        verifyNoInteractions(service, versions);
    }

    private static FileDownload image(String type) {
        var asset = new FileAsset();
        asset.setId(20L);
        asset.setExtension("png");
        return new FileDownload(asset, new ByteArrayResource(new byte[0]), 0, type, "image.png");
    }

    private static Map<String, Object> arguments() {
        return Map.of("target", "body/p0", "imageFileId", 20,
                "imageVersionNo", 2, "userId", 999, "versionNo", 99);
    }

    private static ToolExecutionContext context() {
        var block = new DocxStructureVO.Block("body/p0", "PARAGRAPH", "text", null,
                null, null, null, null, List.of());
        return ToolExecutionContext.forVersion(7L, 10L, 3, "DOCX",
                new DocxStructureVO(10L, 3, List.of(block)), null);
    }
}
