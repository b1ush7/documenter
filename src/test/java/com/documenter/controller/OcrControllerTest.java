package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.OcrService;
import com.documenter.service.OcrCorrectionService;
import com.documenter.vo.OcrCorrectionVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class OcrControllerTest {

    private final OcrService service = mock(OcrService.class);
    private final OcrCorrectionService corrections = mock(OcrCorrectionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OcrController(service, corrections))
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
                new OcrResultVO.Region(5, 8, 40, 30),
                List.of(),
                List.of(new OcrResultVO.Table(1, 1, 93, false,
                        List.of(new OcrResultVO.Cell(0, 0, 1, 1, "标题",
                                10, 12, 30, 16, 93, false)))),
                List.of(new OcrResultVO.Line("标题", 10, 12, 30, 16, 93, false)),
                false)), "0".repeat(64));
        when(service.recognize(eq(7L), eq(10L), eq(2), any())).thenReturn(result);

        mvc.perform(post("/file/10/versions/2/ocr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"zh+en","pages":[2],"dpi":200,
                                 "region":{"x":5,"y":8,"width":40,"height":30}}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pages[0].source").value("OCR"))
                .andExpect(jsonPath("$.data.pages[0].region.x").value(5))
                .andExpect(jsonPath("$.data.pages[0].tables[0].rows").value(1))
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

    @Test
    void exportsPagesAsUtf8PlainTextWithFormFeedSeparator() throws Exception {
        var result = new OcrResultVO(10L, 2, "cloud-vision", "zh",
                2, 0, false, List.of(
                nativePage(1, "第一页"),
                nativePage(2, "第二页")), "1".repeat(64));
        when(service.recognize(eq(7L), eq(10L), eq(2), any())).thenReturn(result);

        mvc.perform(post("/file/10/versions/2/ocr/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"zh\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Type",
                                org.hamcrest.Matchers.startsWith("text/plain")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("\f")));
    }

    @Test
    void savesAndReadsCorrectionsByOcrFingerprint() throws Exception {
        String fingerprint = "a".repeat(64);
        var corrected = new OcrCorrectionVO(10L, 2, fingerprint,
                List.of(new OcrCorrectionVO.Page(1, "人工校正")), null);
        when(corrections.save(eq(7L), eq(10L), eq(2), any())).thenReturn(corrected);
        when(corrections.get(7L, 10L, 2, fingerprint)).thenReturn(corrected);

        mvc.perform(put("/file/10/versions/2/ocr/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                 "pages":[{"pageNo":1,"text":"人工校正"}]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pages[0].text").value("人工校正"));
        mvc.perform(get("/file/10/versions/2/ocr/corrections/" + fingerprint))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.fingerprint").value(fingerprint));
        verify(corrections).save(eq(7L), eq(10L), eq(2), any());
        verify(corrections).get(7L, 10L, 2, fingerprint);
    }

    private static OcrResultVO.Page nativePage(int pageNo, String text) {
        return new OcrResultVO.Page(pageNo, "NATIVE", 0, 0, text,
                100d, false, null, List.of(), List.of(), List.of(), false);
    }
}
