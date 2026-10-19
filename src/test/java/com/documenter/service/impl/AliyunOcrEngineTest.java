package com.documenter.service.impl;

import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponseBody;
import com.aliyun.tea.TeaException;
import com.documenter.configuration.OcrProperties;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.OcrEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AliyunOcrEngineTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void uploadsBinaryWithOriginalCoordinatesAndNoRetriesAndUsesCache() throws Exception {
        var p = properties();
        p.getAliyun().setSecurityToken("temporary-token");
        AtomicInteger calls = new AtomicInteger();
        byte[] image = png(200, 120);
        var engine = engine(p, (config, request, runtime) -> {
            calls.incrementAndGet();
            assertEquals("Advanced", request.getType());
            assertNull(request.getUrl());
            assertArrayEquals(image, request.getBody().readAllBytes());
            assertEquals("points", request.getOutputCoordinate());
            assertTrue(request.getOutputOricoord());
            assertTrue(request.getAdvancedConfig().getOutputRow());
            assertTrue(request.getAdvancedConfig().getOutputTable());
            assertFalse(request.getAdvancedConfig().getOutputCharInfo());
            assertEquals("temporary-token", config.getSecurityToken());
            assertEquals("HTTPS", config.getProtocol());
            assertFalse(runtime.getAutoretry());
            assertEquals(1, runtime.getMaxAttempts().intValue());
            assertEquals(60000, runtime.getReadTimeout().intValue());
            // 经过官方 SDK 响应模型 round-trip，验证实际 toMap 字段名与嵌套结构。
            return RecognizeAllTextResponseBody.build(response()).toMap();
        });
        var first = engine.recognize(image, "zh+en");
        assertEquals(first, engine.recognize(image, "zh+en"));
        assertEquals(1, calls.get());
        assertEquals("aliyun-Advanced", engine.name());
        assertTrue(engine.available());
        try (var files = Files.walk(temp)) {
            Path file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertFalse(file.getFileName().toString().contains("secret"));
        }
        p.getAliyun().setType("General");
        var general = engine(p, (config, request, runtime) -> {
            calls.incrementAndGet();
            assertEquals("General", request.getType());
            assertNull(request.getAdvancedConfig());
            assertNull(request.getOutputCoordinate());
            return response();
        });
        general.recognize(image, "zh+en");
        assertEquals(2, calls.get(), "识别模式变更应使缓存失效");
    }

    @Test
    void mapsRowsAndInclusiveMergedCellsAndConfidence() {
        var result = parse(response(), properties());
        assertEquals(1, result.lines().size(), "行文字不能再重复输出文字块");
        OcrEngine.Line line = result.lines().getFirst();
        assertEquals("合同 A", line.text());
        assertEquals(10, line.left());
        assertEquals(20, line.top());
        assertEquals(80, line.width());
        assertEquals(20, line.height());
        assertEquals(75, line.confidence());
        assertFalse(line.needsReview());
        var cell = result.tables().getFirst().cells().getFirst();
        assertEquals(0, cell.row());
        assertEquals(2, cell.columnSpan());
        assertEquals(1, cell.rowSpan());
        assertEquals(75, cell.confidence());
        assertFalse(result.truncated());
    }

    @Test
    void preservesOnePercentConfidenceAndFlagsMissingCoordinates() {
        var body = Map.<String, Object>of("Data", Map.of("SubImages", List.of(Map.of("BlockInfo", Map.of(
                "BlockDetails", List.of(Map.of("BlockId", 0, "BlockContent", "原文", "BlockConfidence", 1)))))));
        var line = parse(body, properties()).lines().getFirst();
        assertEquals(1, line.confidence(), "供应商置信度已经是百分比，1 不应放大为 100");
        assertEquals(0, line.width());
        assertTrue(line.needsReview());
    }

    @Test
    void appliesUnicodeBudgetAndTableSafetyLimits() {
        var p = properties();
        p.setMaxOutputChars(3);
        var body = Map.<String, Object>of("Data", Map.of("Content", "文😀测试", "SubImages", List.of()));
        var result = parse(body, p);
        assertEquals("文😀测", result.lines().getFirst().text());
        assertTrue(result.truncated());
        p.setMaxTableColumns(1);
        assertEquals(413, assertThrows(BusinessException.class, () -> parse(response(), p)).getCode());
    }

    @Test
    void rejectsMissingStructureChildErrorsAndOverlappingCells() {
        assertEquals(502, assertThrows(BusinessException.class, () -> parse(Map.of("Data", Map.of()), properties())).getCode());
        var error = Map.<String, Object>of("Data", Map.of("SubImages", List.of(Map.of("Code", "400", "Message", "secret upstream"))));
        BusinessException ex = assertThrows(BusinessException.class, () -> parse(error, properties()));
        assertEquals(422, ex.getCode());
        assertFalse(ex.getMessage().contains("secret upstream"));
        var root = mapper.valueToTree(response());
        var details = (tools.jackson.databind.node.ArrayNode) root.path("Data").path("SubImages").path(0)
                .path("TableInfo").path("TableDetails").path(0).path("CellDetails");
        details.add(details.get(0).deepCopy());
        assertEquals(502, assertThrows(BusinessException.class, () ->
                AliyunOcrResponseParser.parse(root, 200, 120, properties())).getCode());
    }

    @Test
    void rejectsInvalidInputsBeforePaidCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var p = properties();
        var engine = engine(p, (c, r, o) -> { calls.incrementAndGet(); return response(); });
        assertEquals(400, assertThrows(BusinessException.class, () -> engine.recognize(null, "auto")).getCode());
        assertEquals(422, assertThrows(BusinessException.class, () -> engine.recognize(new byte[]{1, 2}, "auto")).getCode());
        byte[] tiny = png(15, 50);
        assertEquals(413, assertThrows(BusinessException.class, () -> engine.recognize(tiny, "auto")).getCode());
        byte[] wide = png(1600, 20);
        assertEquals(413, assertThrows(BusinessException.class, () -> engine.recognize(wide, "auto")).getCode());
        byte[] image = png(200, 120);
        assertEquals(400, assertThrows(BusinessException.class, () -> engine.recognize(image, "ja")).getCode());
        p.setMaxImageBytes(10);
        assertEquals(413, assertThrows(BusinessException.class, () -> engine.recognize(image, "auto")).getCode());
        assertEquals(0, calls.get());
    }

    @Test
    void staysUnavailableUntilEnabledAndCredentialed() throws Exception {
        var p = properties();
        var engine = engine(p, (c, r, o) -> { fail("不应调用上游"); return null; });
        p.setEnabled(false);
        assertFalse(engine.available());
        byte[] image = png(200, 120);
        assertEquals(503, assertThrows(BusinessException.class, () -> engine.recognize(image, "auto")).getCode());
        p.setEnabled(true);
        p.getAliyun().setAccessKeySecret("");
        assertFalse(engine.available());
        p.getAliyun().setAccessKeySecret("secret");
        p.getAliyun().setEndpoint("https://attacker.test");
        assertFalse(engine.available());
    }

    @Test
    void mapsUpstreamFailuresWithoutCachingOrLeakingDetails() throws Exception {
        byte[] image = png(200, 120);
        List<Exception> failures = new ArrayList<>();
        failures.add(new TeaException(Map.of("code", "Throttling.User", "statusCode", 400, "message", "secret")));
        failures.add(new TeaException(Map.of("code", "InvalidAccessKeyId.NotFound", "statusCode", 404, "message", "secret")));
        failures.add(new java.net.SocketTimeoutException("secret"));
        failures.add(new IOExceptionForTest());
        int[] expected = {429, 503, 504, 502};
        for (int i = 0; i < failures.size(); i++) {
            Exception failure = failures.get(i);
            var engine = engine(properties(), (c, r, o) -> { throw failure; });
            BusinessException error = assertThrows(BusinessException.class, () -> engine.recognize(image, "auto"));
            assertEquals(expected[i], error.getCode());
            assertFalse(error.getMessage().contains("secret"));
        }
        try (var files = Files.walk(temp)) { assertEquals(0, files.filter(Files::isRegularFile).count()); }
    }

    @Test
    void rejectsOversizedResponsesAndRecoversFromCorruptCache() throws Exception {
        var p = properties();
        AtomicInteger calls = new AtomicInteger();
        var engine = engine(p, (c, r, o) -> { calls.incrementAndGet(); return response(); });
        byte[] image = png(200, 120);
        engine.recognize(image, "auto");
        try (var files = Files.walk(temp)) {
            Files.writeString(files.filter(Files::isRegularFile).findFirst().orElseThrow(), "bad json");
        }
        engine.recognize(image, "auto");
        assertEquals(2, calls.get());
        p.setMaxResponseBytes(10);
        assertEquals(502, assertThrows(BusinessException.class, () -> engine.recognize(image, "en")).getCode());
    }

    private OcrEngine.Result parse(Map<String, Object> body, OcrProperties p) {
        return AliyunOcrResponseParser.parse(mapper.valueToTree(body), 200, 120, p);
    }
    private OcrProperties properties() {
        var p = new OcrProperties();
        p.setEnabled(true);
        p.setProvider("aliyun");
        p.getAliyun().setAccessKeyId("test-id");
        p.getAliyun().setAccessKeySecret("test-secret");
        return p;
    }
    private AliyunOcrEngine engine(OcrProperties p, AliyunOcrEngine.Gateway gateway) {
        var storage = new StorageProperties();
        storage.getLocal().setTemp(temp.toString());
        return new AliyunOcrEngine(p, storage, mapper, gateway);
    }
    private static byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    private static List<Map<String, Integer>> points(int x, int y, int w, int h) {
        return List.of(Map.of("X", x, "Y", y), Map.of("X", x + w, "Y", y),
                Map.of("X", x + w, "Y", y + h), Map.of("X", x, "Y", y + h));
    }
    private static Map<String, Object> response() {
        var blocks = List.of(Map.of("BlockId", 0, "BlockContent", "合同", "BlockConfidence", 95,
                        "BlockPoints", points(10, 20, 40, 20)),
                Map.of("BlockId", 1, "BlockContent", "A", "BlockConfidence", 55,
                        "BlockPoints", points(60, 20, 30, 20)));
        var cell = Map.of("RowStart", 0, "RowEnd", 0, "ColumnStart", 0, "ColumnEnd", 1,
                "CellContent", "合同 A", "BlockList", List.of(0, 1), "CellPoints", points(5, 15, 100, 30));
        return Map.of("RequestId", "request-id", "Data", Map.of("Content", "合同 A", "SubImages", List.of(Map.of(
                "BlockInfo", Map.of("BlockDetails", blocks),
                "RowInfo", Map.of("RowDetails", List.of(Map.of("RowContent", "合同 A", "BlockList", List.of(0, 1)))),
                "TableInfo", Map.of("TableDetails", List.of(Map.of("RowCount", 1, "ColumnCount", 2, "CellDetails", List.of(cell))))))));
    }
    private static class IOExceptionForTest extends java.io.IOException { IOExceptionForTest() { super("secret"); } }
}
