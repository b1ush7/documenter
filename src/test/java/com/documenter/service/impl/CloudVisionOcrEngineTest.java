package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ModelConfigService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudVisionOcrEngineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void callsCompatibleCloudEndpointAndCachesByImageAndModel() throws Exception {
        String content = """
                ```json
                {"lines":[
                  {"text":"第二行","left":10,"top":30,"width":80,"height":20,"confidence":0.55},
                  {"text":"First line","left":10,"top":10,"width":120,"height":18,"confidence":0.96}
                ],
                 "tables":[{"rows":2,"columns":2,"cells":[
                   {"row":0,"column":0,"rowSpan":1,"columnSpan":1,"text":"表头","left":0,"top":0,"width":50,"height":20,"confidence":0.95},
                   {"row":0,"column":1,"rowSpan":1,"columnSpan":1,"text":"值","left":50,"top":0,"width":50,"height":20,"confidence":0.94},
                   {"row":1,"column":0,"rowSpan":1,"columnSpan":1,"text":"A","left":0,"top":20,"width":50,"height":20,"confidence":0.93},
                   {"row":1,"column":1,"rowSpan":1,"columnSpan":1,"text":"1","left":50,"top":20,"width":50,"height":20,"confidence":0.92}
                 ]}]}
                ```
                """;
        String responseBody = new ObjectMapper().writeValueAsString(
                Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(
                responseBody.getBytes(StandardCharsets.UTF_8)));
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(response);

        OcrProperties properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setLowConfidenceThreshold(60);
        StorageProperties storage = new StorageProperties();
        storage.getLocal().setTemp(temporaryDirectory.toString());
        ModelConfigService configs = mock(ModelConfigService.class);
        when(configs.requireDefaultRuntimeConfig("OCR")).thenReturn(
                new ModelConfigService.ModelRuntimeConfig("ocr-default", "openai",
                        "https://cloud.example.test/v1", "vision-ocr",
                        "secret-key", 15, 2048, BigDecimal.ZERO));
        var engine = new CloudVisionOcrEngine(properties, storage, configs,
                new ObjectMapper(), client);
        byte[] png = png();

        var first = engine.recognize(png, "zh+en");
        List<String> cacheFiles;
        try (var paths = java.nio.file.Files.walk(temporaryDirectory)) {
            cacheFiles = paths.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).toList();
        }
        assertEquals(1, cacheFiles.size(), cacheFiles.toString());
        var cached = engine.recognize(png, "zh+en");
        try (var paths = java.nio.file.Files.walk(temporaryDirectory)) {
            cacheFiles = paths.filter(java.nio.file.Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).toList();
        }
        assertEquals(1, cacheFiles.size(), cacheFiles.toString());

        verify(client, times(1)).send(any(HttpRequest.class), any());
        assertEquals(2, first.lines().size());
        assertEquals("First line", first.lines().get(0).text());
        assertEquals(96, first.lines().get(0).confidence());
        assertFalse(first.lines().get(0).needsReview());
        assertEquals("第二行", first.lines().get(1).text());
        assertEquals(55, first.lines().get(1).confidence());
        assertTrue(first.lines().get(1).needsReview());
        assertEquals(1, first.tables().size());
        assertEquals(2, first.tables().getFirst().rows());
        assertEquals(4, first.tables().getFirst().cells().size());
        assertEquals(first, cached);
    }

    @Test
    void rejectsMalformedCloudResult() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> CloudVisionOcrEngine.parseResponse(
                        "{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}"
                                .getBytes(StandardCharsets.UTF_8),
                        100, 100, 60, 100, 1000, 200, 100, 5000));

        assertEquals(502, error.getCode());
    }

    @Test
    void rejectsOverlappingTableCells() {
        String content = """
                {"lines":[],"tables":[{"rows":2,"columns":2,"cells":[
                {"row":0,"column":0,"rowSpan":1,"columnSpan":2,"text":"A"},
                {"row":0,"column":1,"rowSpan":1,"columnSpan":1,"text":"B"},
                {"row":1,"column":0,"rowSpan":1,"columnSpan":1,"text":"C"},
                {"row":1,"column":1,"rowSpan":1,"columnSpan":1,"text":"D"}
                ]}]}
                """;
        String response;
        try {
            response = new ObjectMapper().writeValueAsString(
                    Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }

        BusinessException error = assertThrows(BusinessException.class,
                () -> CloudVisionOcrEngine.parseResponse(
                        response.getBytes(StandardCharsets.UTF_8),
                        100, 100, 60, 100, 1000, 200, 100, 5000));

        assertEquals(502, error.getCode());
        assertTrue(error.getMessage().contains("重叠"));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
