package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.BusinessException;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.VersionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageEditControllerTest {
    private final ImageDocumentService images = mock(ImageDocumentService.class);
    private final XlsxDocumentService xlsx = mock(XlsxDocumentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(FileService.class),
                        mock(DocumentVersionService.class), mock(DocxDocumentService.class),
                        mock(PdfDocumentService.class), xlsx, images,
                        mock(PagePreviewService.class), mock(CompatibilityVerificationService.class),
                        mock(DocumentComparisonService.class), mock(DocumentExportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void insertsXlsxImagesUsingAuthenticatedOwnerAndPinnedImageVersion() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(2);
        when(xlsx.insertImages(eq(7L), eq(10L), eq(1), any())).thenReturn(version);

        mvc.perform(post("/file/10/versions/1/xlsx/images").contentType(MediaType.APPLICATION_JSON).content("""
                {"operations":[{"sheetName":"数据","cell":"B3","imageFileId":30,
                  "imageVersionNo":2,"widthPixels":80,"heightPixels":40}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        verify(xlsx).insertImages(eq(7L), eq(10L), eq(1), argThat(request -> {
            var operation = request.getOperations().getFirst();
            return "数据".equals(operation.getSheetName()) && "B3".equals(operation.getCell())
                    && operation.getImageFileId() == 30L && operation.getImageVersionNo() == 2
                    && operation.getWidthPixels() == 80 && operation.getHeightPixels() == 40;
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"operations\":[]}", "{\"operations\":[null]}",
            "{\"operations\":[{\"sheetName\":\"数据\",\"cell\":\"B3\",\"imageFileId\":30,\"imageVersionNo\":0,\"widthPixels\":80,\"heightPixels\":40}]}",
            "{\"operations\":[{\"sheetName\":\"数据\",\"cell\":\"B3\",\"imageFileId\":30,\"imageVersionNo\":2,\"widthPixels\":10001,\"heightPixels\":40}]}",
            "{\"operations\":[{\"sheetName\":\"数据\",\"cell\":\"B3\",\"imageFileId\":30,\"widthPixels\":80,\"heightPixels\":40}]}"
    })
    void invalidXlsxImageRequestIsRejectedBeforeServiceCall(String body) throws Exception {
        mvc.perform(post("/file/10/versions/1/xlsx/images").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(xlsx);
    }

    @Test
    void anonymousXlsxImageInsertionCannotReachService() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/file/10/versions/1/xlsx/images").contentType(MediaType.APPLICATION_JSON).content("""
                {"operations":[{"sheetName":"数据","cell":"B3","imageFileId":30,
                  "imageVersionNo":2,"widthPixels":80,"heightPixels":40}]}
                """))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(xlsx);
    }

    @Test
    void existingRouteAcceptsNewOperationsAndUsesAuthenticatedOwnerAndExplicitVersion() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(2);
        when(images.edit(eq(7L), eq(10L), eq(1), any())).thenReturn(version);

        mvc.perform(post("/file/10/versions/1/image-edits").contentType(MediaType.APPLICATION_JSON).content("""
                {"operations":[{"type":"BRIGHTNESS","factor":1.5},
                  {"type":"ADD_TEXT","text":"标题","x":10,"y":20,"fontSize":24,"color":"#112233"}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(2));

        verify(images).edit(eq(7L), eq(10L), eq(1), argThat(request ->
                request.getOperations().size() == 2 && request.getOperations().getFirst().getFactor() == 1.5
                        && "ADD_TEXT".equals(request.getOperations().get(1).getType())
                        && "标题".equals(request.getOperations().get(1).getText())
                        && request.getOperations().get(1).getFontSize() == 24
                        && "#112233".equals(request.getOperations().get(1).getColor())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"BRIGHTNESS\",\"factor\":-1}",
            "{\"type\":\"BRIGHTNESS\",\"factor\":4.1}",
            "{\"type\":\"ADD_TEXT\",\"text\":\"A\",\"x\":0,\"y\":0,\"fontSize\":513}",
            "{\"type\":\"ADD_TEXT\",\"text\":\"A\",\"x\":0,\"y\":0,\"color\":\"red\"}",
            "{\"type\":\"ADD_TEXT\",\"text\":\"A\",\"x\":-1,\"y\":0}"
    })
    void invalidRangesAreRejectedBeforeServiceCall(String operation) throws Exception {
        mvc.perform(post("/file/10/versions/1/image-edits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[" + operation + "]}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(images);
    }

    @Test
    void renderingFailureReturnsBusinessErrorWithoutPretendingAVersionWasCreated() throws Exception {
        when(images.edit(eq(7L), eq(10L), eq(1), any()))
                .thenThrow(new BusinessException(422, "当前系统字体无法显示部分文字"));
        mvc.perform(post("/file/10/versions/1/image-edits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"type\":\"ADD_TEXT\",\"text\":\"A\",\"x\":0,\"y\":0}]}"))
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.msg").value("当前系统字体无法显示部分文字"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void anonymousEditCannotReachService() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/file/10/versions/1/image-edits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"type\":\"BRIGHTNESS\",\"factor\":1.5}]}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(images);
    }
}
