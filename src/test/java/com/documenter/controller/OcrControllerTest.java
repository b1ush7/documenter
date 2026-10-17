package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.OcrService;
import com.documenter.vo.OcrResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class OcrControllerTest {

    private final OcrService service = mock(OcrService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OcrController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recognizesSelectedCloudOcrPages() throws Exception {
        var result = new OcrResultVO(10L, 2, "cloud-vision", "zh+en",
                3, 1, false, List.of(new OcrResultVO.Page(
                2, "OCR", 100, 80, "标题", 93d, false,
                List.of(new OcrResultVO.Line("标题", 10, 12, 30, 16, 93, false)),
                false)));
        when(service.recognize(eq(7L), eq(10L), eq(2), any())).thenReturn(result);

        mvc.perform(post("/file/10/versions/2/ocr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"zh+en","pages":[2],"dpi":200}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pages[0].source").value("OCR"))
                .andExpect(jsonPath("$.data.pages[0].lines[0].confidence").value(93));
    }

    @Test
    void rejectsInvalidDpiBeforeCallingService() throws Exception {
        mvc.perform(post("/file/10/versions/2/ocr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dpi":50}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(service, never()).recognize(any(), any(), any(), any());
    }
}
