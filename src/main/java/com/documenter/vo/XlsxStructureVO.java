package com.documenter.vo;

import java.util.List;

/** 一个明确 XLSX 版本的结构化只读表示。 */
public record XlsxStructureVO(Long fileId, Integer versionNo, int totalSheets,
                              int includedCells, boolean truncated, List<Sheet> sheets) {

    public XlsxStructureVO {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }

    public record Sheet(int index, String name, Integer firstRow, Integer lastRow,
                        List<String> mergedRegions, List<Chart> charts, List<Cell> cells) {

        public Sheet {
            mergedRegions = mergedRegions == null ? List.of() : List.copyOf(mergedRegions);
            charts = charts == null ? List.of() : List.copyOf(charts);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /** 图表以工作表内的顺序定位；types 例如 BAR、LINE、PIE。 */
    public record Chart(int index, String title, String titleFormula, List<String> types) {

        public Chart {
            types = types == null ? List.of() : List.copyOf(types);
        }
    }

    /** 行列下标均从 0 开始，address 使用 A1 格式。 */
    public record Cell(String address, int rowIndex, int columnIndex, String type,
                       String cachedValueType, String value, String formula, Style style) {
    }

    public record Style(int styleIndex, int dataFormat, String dataFormatString,
                        String horizontalAlignment, String verticalAlignment,
                        boolean wrapText, boolean locked, boolean hidden,
                        String fillPattern, int fillForegroundColor,
                        String borderTop, String borderRight, String borderBottom, String borderLeft,
                        String fontName, double fontSize, boolean bold, boolean italic) {
    }
}
