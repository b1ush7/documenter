package com.documenter.util;

import com.documenter.exception.BusinessException;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.CellReference;

import java.util.Locale;

/** 编辑计划内部使用的「原始工作表名称!规范单元格」定位；工作表名称本身允许包含 !。 */
public record XlsxFormulaTarget(String sheetName, String cell) {
    public static XlsxFormulaTarget parse(String target) {
        int separator = target == null ? -1 : target.lastIndexOf('!');
        if (separator < 1) throw new BusinessException(422, "公式目标必须使用 工作表!A1 格式");
        return of(target.substring(0, separator), target.substring(separator + 1));
    }

    public static XlsxFormulaTarget of(String sheetName, String cell) {
        if (sheetName == null || sheetName.isBlank() || cell == null
                || !cell.matches("^\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}$")) {
            throw new BusinessException(422, "公式目标必须指定工作表与单个 A1 单元格");
        }
        var reference = new CellReference(cell);
        if (reference.getCol() > SpreadsheetVersion.EXCEL2007.getLastColumnIndex()
                || reference.getRow() > SpreadsheetVersion.EXCEL2007.getLastRowIndex()) {
            throw new BusinessException(422, "公式目标超出 XLSX 行列范围");
        }
        return new XlsxFormulaTarget(sheetName, cell.replace("$", "").toUpperCase(Locale.ROOT));
    }

    public String target() { return sheetName + "!" + cell; }
    public String key() { return sheetName.toLowerCase(Locale.ROOT) + "!" + cell; }
}
