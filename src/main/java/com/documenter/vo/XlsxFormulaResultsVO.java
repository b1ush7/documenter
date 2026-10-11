package com.documenter.vo;

import java.util.List;

/** 指定 XLSX 区域内公式的即时计算结果，不使用文件中的旧缓存。 */
public record XlsxFormulaResultsVO(Long fileId, Integer versionNo, String sheetName,
                                   String range, List<Result> results) {

    public XlsxFormulaResultsVO {
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** status: OK / ERROR / UNSUPPORTED / EVALUATION_FAILED。 */
    public record Result(String address, String formula, String status,
                          String valueType, String value, String message) {
    }
}
