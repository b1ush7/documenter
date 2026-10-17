package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.ModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                ]}
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
        assertEquals(first, cached);
    }

    @Test
    void rejectsMalformedCloudResult() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> CloudVisionOcrEngine.parseResponse(
                        "{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}"
                                .getBytes(StandardCharsets.UTF_8),
                        100, 100, 60, 100, 1000));

        assertEquals(502, error.getCode());
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
