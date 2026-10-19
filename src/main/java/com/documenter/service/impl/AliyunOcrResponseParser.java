package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.OcrEngine;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将阿里云文字块、行和合并单元格转换为项目统一结构。 */
final class AliyunOcrResponseParser {
    private AliyunOcrResponseParser() { }

    static OcrEngine.Result parse(JsonNode root, int width, int height, OcrProperties properties) {
        try {
            JsonNode data = root.path("Data");
            if (!data.isObject() || !data.path("SubImages").isArray()) throw invalid();
            List<OcrEngine.Line> lines = new ArrayList<>();
            List<OcrEngine.Table> tables = new ArrayList<>();
            Budget budget = new Budget(properties);
            int cells = 0;
            for (JsonNode sub : data.path("SubImages")) {
                if (!clean(sub.path("Code")).isEmpty()) {
                    throw new BusinessException(422, "阿里云 OCR 子图识别失败，请检查图片内容与识别模式");
                }
                Map<Integer, Block> blocks = new LinkedHashMap<>();
                for (JsonNode b : sub.path("BlockInfo").path("BlockDetails")) {
                    int id = b.path("BlockId").asInt(-1);
                    if (id < 0 || blocks.containsKey(id)) throw invalid();
                    blocks.put(id, new Block(clean(b.path("BlockContent")),
                            box(b.path("BlockPoints"), width, height), confidence(b.path("BlockConfidence"))));
                }
                Set<Integer> used = new HashSet<>();
                for (JsonNode row : sub.path("RowInfo").path("RowDetails")) {
                    List<Block> referenced = references(row.path("BlockList"), blocks);
                    for (JsonNode id : row.path("BlockList")) used.add(id.asInt(-1));
                    addLine(lines, clean(row.path("RowContent")), union(referenced), average(referenced), budget);
                }
                for (var entry : blocks.entrySet()) {
                    if (!used.contains(entry.getKey())) {
                        Block b = entry.getValue();
                        addLine(lines, b.text(), b.box(), b.confidence(), budget);
                    }
                }
                for (JsonNode table : sub.path("TableInfo").path("TableDetails")) {
                    int rows = table.path("RowCount").asInt(0), columns = table.path("ColumnCount").asInt(0);
                    if (rows <= 0 || columns <= 0) throw invalid();
                    if (rows > properties.getMaxTableRows() || columns > properties.getMaxTableColumns()) {
                        throw new BusinessException(413, "阿里云 OCR 表格行列数超过限制");
                    }
                    JsonNode details = table.path("CellDetails");
                    if (!details.isArray()) throw invalid();
                    if ((long) cells + details.size() > properties.getMaxTableCells()) {
                        throw new BusinessException(413, "阿里云 OCR 表格单元格总数超过限制");
                    }
                    cells += details.size();
                    boolean[][] occupied = new boolean[rows][columns];
                    List<OcrEngine.Cell> converted = new ArrayList<>();
                    for (JsonNode cell : details) {
                        int row = cell.path("RowStart").asInt(-1), column = cell.path("ColumnStart").asInt(-1);
                        int lastRow = cell.path("RowEnd").asInt(-1), lastColumn = cell.path("ColumnEnd").asInt(-1);
                        if (row < 0 || column < 0 || lastRow < row || lastColumn < column
                                || lastRow >= rows || lastColumn >= columns) throw invalid();
                        for (int r = row; r <= lastRow; r++) for (int c = column; c <= lastColumn; c++) {
                            if (occupied[r][c]) throw invalid();
                            occupied[r][c] = true;
                        }
                        List<Block> referenced = references(cell.path("BlockList"), blocks);
                        Box box = box(cell.path("CellPoints"), width, height);
                        if (!box.present()) box = union(referenced);
                        double confidence = average(referenced);
                        String content = budget.text(clean(cell.path("CellContent")));
                        converted.add(new OcrEngine.Cell(row, column, lastRow - row + 1, lastColumn - column + 1,
                                content, box.x(), box.y(), box.width(), box.height(), confidence,
                                !box.present() || confidence < properties.getLowConfidenceThreshold()));
                    }
                    double confidence = converted.stream().mapToDouble(OcrEngine.Cell::confidence).average().orElse(0);
                    tables.add(new OcrEngine.Table(rows, columns, confidence,
                            converted.isEmpty() || converted.stream().anyMatch(OcrEngine.Cell::needsReview), converted));
                }
            }
            if (lines.isEmpty()) {
                // 某些模式只返回全文。保持文字可用，但不编造位置或置信度。
                String content = clean(data.path("Content"));
                for (String line : content.split("\\R")) addLine(lines, line.trim(), Box.EMPTY, 0, budget);
            }
            return new OcrEngine.Result(lines, tables, budget.truncated);
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException malformed) { throw invalid(); }
    }

    private static void addLine(List<OcrEngine.Line> lines, String text, Box box, double confidence, Budget budget) {
        if (text.isEmpty()) return;
        if (lines.size() >= budget.properties.getMaxWords()) { budget.truncated = true; return; }
        String limited = budget.text(text);
        if (limited.isEmpty()) return;
        lines.add(new OcrEngine.Line(limited, box.x(), box.y(), box.width(), box.height(), confidence,
                !box.present() || confidence < budget.properties.getLowConfidenceThreshold()));
    }

    private static List<Block> references(JsonNode ids, Map<Integer, Block> blocks) {
        List<Block> result = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode id : ids) {
            Block block = blocks.get(id.asInt(-1));
            if (seen.add(id.asInt(-1))) {
                result.add(block == null ? new Block("", Box.EMPTY, 0) : block);
            }
        }
        return result;
    }

    private static double average(List<Block> blocks) {
        return blocks.stream().mapToDouble(Block::confidence).average().orElse(0);
    }

    private static Box union(List<Block> blocks) {
        List<Box> boxes = blocks.stream().map(Block::box).filter(Box::present).toList();
        // 不完整坐标不能用于声称准确定位整行。
        if (boxes.isEmpty() || boxes.size() != blocks.size()) return Box.EMPTY;
        int x = boxes.stream().mapToInt(Box::x).min().orElse(0);
        int y = boxes.stream().mapToInt(Box::y).min().orElse(0);
        int right = boxes.stream().mapToInt(b -> b.x() + b.width()).max().orElse(x);
        int bottom = boxes.stream().mapToInt(b -> b.y() + b.height()).max().orElse(y);
        return new Box(x, y, right - x, bottom - y);
    }

    private static Box box(JsonNode points, int width, int height) {
        if (!points.isArray() || points.size() < 4) return Box.EMPTY;
        int x = width, y = height, right = 0, bottom = 0;
        for (JsonNode point : points) {
            if (!point.path("X").isNumber() || !point.path("Y").isNumber()) return Box.EMPTY;
            int px = Math.max(0, Math.min(width, point.path("X").asInt()));
            int py = Math.max(0, Math.min(height, point.path("Y").asInt()));
            x = Math.min(x, px); y = Math.min(y, py);
            right = Math.max(right, px); bottom = Math.max(bottom, py);
        }
        return new Box(x, y, right - x, bottom - y);
    }

    private static String clean(JsonNode node) { return node.asText("").replace("\u0000", "").trim(); }
    private static double confidence(JsonNode node) {
        double value = node.asDouble(0);
        return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
    }
    private static BusinessException invalid() { return new BusinessException(502, "阿里云 OCR 返回结构不合法"); }
    private record Block(String text, Box box, double confidence) { }
    private record Box(int x, int y, int width, int height) {
        static final Box EMPTY = new Box(0, 0, 0, 0);
        boolean present() { return width > 0 && height > 0; }
    }
    private static final class Budget {
        private final OcrProperties properties;
        private int characters;
        private boolean truncated;
        Budget(OcrProperties properties) { this.properties = properties; }
        String text(String value) {
            int count = value.codePointCount(0, value.length());
            int remaining = Math.max(0, properties.getMaxOutputChars() - characters);
            if (count > remaining) {
                value = value.substring(0, value.offsetByCodePoints(0, remaining));
                truncated = true;
            }
            characters += Math.min(count, remaining);
            return value;
        }
    }
}
