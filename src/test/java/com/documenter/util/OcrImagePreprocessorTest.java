package com.documenter.util;

import com.documenter.dto.OcrRequestDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.OcrEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrImagePreprocessorTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void rotationPreservesEveryPixelAndInverseBoxes(int angle) {
        var source = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 5; y++) for (int x = 0; x < 7; x++) source.setRGB(x, y, 0xff000000 | (y * 7 + x));
        var request = new OcrRequestDTO();
        request.setRotationDegrees(angle);
        var prepared = OcrImagePreprocessor.prepare(source, request);
        assertEquals(angle % 180 == 0 ? 7 : 5, prepared.image().getWidth());
        assertEquals(angle % 180 == 0 ? 5 : 7, prepared.image().getHeight());
        for (int y = 0; y < prepared.image().getHeight(); y++) {
            for (int x = 0; x < prepared.image().getWidth(); x++) {
                var box = prepared.originalBox(x, y, 1, 1);
                assertEquals(source.getRGB(box.left(), box.top()), prepared.image().getRGB(x, y));
            }
        }
        var whole = prepared.originalBox(0, 0, prepared.image().getWidth(), prepared.image().getHeight());
        assertEquals(new OcrImagePreprocessor.Box(0, 0, 7, 5), whole);
    }

    @Test
    void mapsTableCellsAndLinesBackWithoutChangingLogicalTableStructure() {
        var request = new OcrRequestDTO();
        request.setRotationDegrees(90);
        var prepared = OcrImagePreprocessor.prepare(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), request);
        var result = prepared.originalCoordinates(new OcrEngine.Result(
                List.of(new OcrEngine.Line("中文", 10, 20, 30, 15, 82, false)),
                List.of(new OcrEngine.Table(1, 2, 82, false, List.of(
                        new OcrEngine.Cell(0, 0, 1, 2, "合并", 10, 20, 30, 15, 82, true)))), true));
        var line = result.lines().getFirst();
        assertEquals(new OcrEngine.Line("中文", 20, 20, 15, 30, 82, false), line);
        var cell = result.tables().getFirst().cells().getFirst();
        assertEquals(2, cell.columnSpan());
        assertEquals(20, cell.left());
        assertEquals(20, cell.top());
        assertEquals(15, cell.width());
        assertEquals(30, cell.height());
        assertTrue(cell.needsReview());
        assertTrue(result.truncated());
    }

    @Test
    void lowLightLiftsShadowsAndFlattensTransparencyToWhiteWithoutChangingOriginal() {
        var source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(36, 36, 36).getRGB());
        var request = new OcrRequestDTO();
        request.setLowLight(true);
        var processed = OcrImagePreprocessor.prepare(source, request).image();
        assertTrue((processed.getRGB(0, 0) & 255) > 80);
        assertEquals(255, processed.getRGB(1, 0) & 255);
        assertEquals(36, source.getRGB(0, 0) & 255);
        assertEquals(0, source.getRGB(1, 0));
    }

    @Test
    void medianRemovesIsolatedNoiseAndSharpenIncreasesSoftEdgeContrast() {
        var source = new BufferedImage(9, 9, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) source.setRGB(x, y, 0x777777);
        source.setRGB(4, 4, 0xffffff);
        var request = new OcrRequestDTO();
        request.setDenoise(true);
        var denoised = OcrImagePreprocessor.prepare(source, request).image();
        assertEquals(119, denoised.getRGB(4, 4) & 255);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) source.setRGB(x, y, (x < 4 ? 80 : 160) * 0x010101);
        request.setDenoise(false);
        request.setSharpen(true);
        var sharpened = OcrImagePreprocessor.prepare(source, request).image();
        assertTrue((sharpened.getRGB(4, 4) & 255) > 160);
        assertTrue((sharpened.getRGB(3, 4) & 255) < 80);
    }

    @Test
    void rejectsNonRightAngleAndHandlesSinglePixel() {
        var request = new OcrRequestDTO();
        request.setRotationDegrees(45);
        assertThrows(BusinessException.class, () -> OcrImagePreprocessor.prepare(new BufferedImage(1, 1, 1), request));
        request.setRotationDegrees(270);
        request.setEnhance(true);
        request.setLowLight(true);
        request.setDenoise(true);
        request.setSharpen(true);
        assertEquals(1, OcrImagePreprocessor.prepare(new BufferedImage(1, 1, 1), request).image().getWidth());
    }
}
