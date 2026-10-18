package com.documenter.util;

import com.documenter.dto.OcrRequestDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.OcrEngine;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** 确定性 OCR 预处理；仅处理工作副本，并保存回到裁剪前坐标所需的信息。 */
public final class OcrImagePreprocessor {
    private OcrImagePreprocessor() {}

    public static void validate(OcrRequestDTO request) {
        if (request.getRotationDegrees() != 0 && request.getRotationDegrees() != 90
                && request.getRotationDegrees() != 180 && request.getRotationDegrees() != 270) {
            throw new BusinessException(400, "OCR 旋转角度仅支持 0、90、180、270");
        }
    }

    public static Prepared prepare(BufferedImage source, OcrRequestDTO request) {
        validate(request);
        BufferedImage image = source;
        if (request.isEnhance() || request.isDenoise() || request.isSharpen() || request.isLowLight()) {
            image = grayscale(source, request.isEnhance(), request.isLowLight());
            if (request.isDenoise()) image = filter(image, true);
            if (request.isSharpen()) image = filter(image, false);
        }
        int angle = request.getRotationDegrees();
        if (angle != 0) {
            int w = image.getWidth(), h = image.getHeight();
            BufferedImage rotated = new BufferedImage(angle == 180 ? w : h,
                    angle == 180 ? h : w, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int nx = angle == 90 ? h - 1 - y : angle == 180 ? w - 1 - x : y;
                    int ny = angle == 90 ? x : angle == 180 ? h - 1 - y : w - 1 - x;
                    rotated.setRGB(nx, ny, image.getRGB(x, y));
                }
            }
            image = rotated;
        }
        return new Prepared(image, source.getWidth(), source.getHeight(), angle);
    }

    public static BufferedImage grayscale(BufferedImage source, boolean contrast, boolean lowLight) {
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, w, h);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        int[] pixels = target.getRGB(0, 0, w, h, null, 0, w);
        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            pixels[i] = (((rgb >> 16) & 255) * 77 + ((rgb >> 8) & 255) * 150 + (rgb & 255) * 29) >> 8;
            histogram[pixels[i]]++;
        }
        int low = 0, high = 255;
        if (contrast) {
            int count = 0, tail = pixels.length / 100;
            while (low < 255 && (count += histogram[low]) <= tail) low++;
            count = 0;
            while (high > 0 && (count += histogram[high]) <= tail) high--;
            if (high <= low) { low = 0; high = 255; }
        }
        int[] lut = new int[256];
        for (int i = 0; i < lut.length; i++) {
            int value = clamp((int) Math.round((i - low) * 255d / (high - low)));
            if (lowLight) value = (int) Math.round(255 * Math.sqrt(value / 255d));
            lut[i] = value * 0x010101;
        }
        for (int i = 0; i < pixels.length; i++) pixels[i] = lut[pixels[i]];
        target.setRGB(0, 0, w, h, pixels, 0, w);
        return target;
    }

    private static BufferedImage filter(BufferedImage source, boolean median) {
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] neighbors = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int index = 0, sum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int v = source.getRGB(Math.max(0, Math.min(w - 1, x + dx)),
                                Math.max(0, Math.min(h - 1, y + dy))) & 255;
                        neighbors[index++] = v;
                        sum += v * (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                    }
                }
                int value;
                if (median) {
                    Arrays.sort(neighbors);
                    value = neighbors[4];
                } else {
                    int original = source.getRGB(x, y) & 255;
                    value = clamp((int) Math.round(original + 0.65 * (original - sum / 16d)));
                }
                result.setRGB(x, y, value * 0x010101);
            }
        }
        return result;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    public record Box(int left, int top, int width, int height) {}

    public record Prepared(BufferedImage image, int originalWidth, int originalHeight, int angle) {
        public Box originalBox(int x, int y, int width, int height) {
            return switch (angle) {
                case 90 -> new Box(y, originalHeight - x - width, height, width);
                case 180 -> new Box(originalWidth - x - width, originalHeight - y - height, width, height);
                case 270 -> new Box(originalWidth - y - height, x, height, width);
                default -> new Box(x, y, width, height);
            };
        }

        public OcrEngine.Result originalCoordinates(OcrEngine.Result result) {
            var lines = result.lines().stream().map(line -> {
                Box box = originalBox(line.left(), line.top(), line.width(), line.height());
                return new OcrEngine.Line(line.text(), box.left(), box.top(), box.width(), box.height(),
                        line.confidence(), line.needsReview());
            }).toList();
            var tables = result.tables().stream().map(table -> new OcrEngine.Table(table.rows(),
                    table.columns(), table.confidence(), table.needsReview(), table.cells().stream().map(cell -> {
                Box box = originalBox(cell.left(), cell.top(), cell.width(), cell.height());
                return new OcrEngine.Cell(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(),
                        cell.text(), box.left(), box.top(), box.width(), box.height(),
                        cell.confidence(), cell.needsReview());
            }).toList())).toList();
            return new OcrEngine.Result(lines, tables, result.truncated());
        }
    }
}
