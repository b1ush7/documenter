package com.documenter.vo;

import java.util.List;

/** XLSX 中一个明确矩形区域的二维只读表示。 */
public record XlsxRangeVO(Long fileId, Integer versionNo, String sheetName, String range,
                          int firstRow, int firstColumn, int rowCount, int columnCount,
                          List<List<XlsxStructureVO.Cell>> rows) {

    public XlsxRangeVO {
        rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
    }
}
