package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.EditXlsxStructureDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.service.XlsxDocumentService;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxStructureVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Apache POI 驱动的 XLSX 结构读取与受控单元格编辑。 */
@Slf4j
@Service
public class XlsxDocumentServiceImpl implements XlsxDocumentService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern UNSAFE_FORMULA_FUNCTION = Pattern.compile(
            "(?i)(?:^|[^A-Z0-9_.])(?:WEBSERVICE|HYPERLINK|RTD|DDE|CALL|REGISTER\\.ID)\\s*\\(");
    private static final Pattern EXTERNAL_WORKBOOK_REFERENCE = Pattern.compile(
            "(?i)\\[[^]\\r\\n]+\\][^!\\r\\n]*!");

    private final DocumentVersionService versionService;
    private final FileStorage fileStorage;
    private final VersionWriter versionWriter;
    private final StorageProperties properties;

    public XlsxDocumentServiceImpl(DocumentVersionService versionService,
                                   FileStorage fileStorage,
                                   VersionWriter versionWriter,
                                   StorageProperties properties) {
        this.versionService = versionService;
        this.fileStorage = fileStorage;
        this.versionWriter = versionWriter;
        this.properties = properties;
    }

    @Override
    public XlsxStructureVO readStructure(Long userId, Long fileId, Integer versionNo) {
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        if (!isXlsx(version)) {
            throw new BusinessException(400, "当前版本不是 XLSX 文档");
        }

        try (InputStream input = version.resource().getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            return readWorkbook(fileId, versionNo, workbook);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("读取 XLSX 结构失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(422, "XLSX 文档无法解析或内容已损坏");
        }
    }

    @Override
    public XlsxRangeVO readRange(Long userId, Long fileId, Integer versionNo,
                                 String sheetName, String range) {
        CellRangeAddress requested = parseRange(range);
        long area = (long) (requested.getLastRow() - requested.getFirstRow() + 1)
                * (requested.getLastColumn() - requested.getFirstColumn() + 1);
        if (area > properties.getXlsx().getMaxReadCells()) {
            throw new BusinessException(413, "XLSX 区域单元格数量超过读取限制");
        }
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        if (!isXlsx(version)) {
            throw new BusinessException(400, "当前版本不是 XLSX 文档");
        }

        try (InputStream input = version.resource().getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            XSSFSheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + sheetName);
            }
            List<List<XlsxStructureVO.Cell>> rows = new ArrayList<>();
            for (int rowIndex = requested.getFirstRow(); rowIndex <= requested.getLastRow(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<XlsxStructureVO.Cell> cells = new ArrayList<>();
                for (int columnIndex = requested.getFirstColumn();
                     columnIndex <= requested.getLastColumn(); columnIndex++) {
                    Cell cell = row == null ? null : row.getCell(columnIndex);
                    cells.add(cell == null ? emptyCell(rowIndex, columnIndex) : readCell(workbook, cell));
                }
                rows.add(cells);
            }
            return new XlsxRangeVO(fileId, versionNo, sheet.getSheetName(),
                    requested.formatAsString(), requested.getFirstRow(), requested.getFirstColumn(),
                    requested.getLastRow() - requested.getFirstRow() + 1,
                    requested.getLastColumn() - requested.getFirstColumn() + 1, rows);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("读取 XLSX 区域失败, fileId={}, versionNo={}, range={}",
                    fileId, versionNo, range, e);
            throw new BusinessException(422, "XLSX 文档无法解析或内容已损坏");
        }
    }

    @Override
    public VersionVO editCells(Long userId, Long fileId, Integer sourceVersion,
                               EditXlsxCellsDTO request) {
        int affectedCells = countAffectedCells(request.getOperations());
        if (affectedCells > properties.getXlsx().getMaxEditOperations()) {
            throw new BusinessException(413, "单次单元格编辑数量超过处理限制");
        }
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "编辑 " + affectedCells + " 个 XLSX 单元格",
                workbook -> applyOperations(workbook, request.getOperations()));
    }

    @Override
    public VersionVO editStructure(Long userId, Long fileId, Integer sourceVersion,
                                   EditXlsxStructureDTO request) {
        long structuralUnits = request.getOperations().stream()
                .filter(java.util.Objects::nonNull)
                .map(EditXlsxStructureDTO.Operation::getCount)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
        if (structuralUnits > properties.getXlsx().getMaxStructuralUnits()) {
            throw new BusinessException(413, "单次行列编辑数量超过处理限制");
        }
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "执行 " + request.getOperations().size() + " 个 XLSX 行列编辑操作",
                workbook -> applyStructureOperations(workbook, request.getOperations()));
    }

    private XlsxStructureVO readWorkbook(Long fileId, Integer versionNo, XSSFWorkbook workbook) {
        int maxCells = properties.getXlsx().getMaxReadCells();
        List<XlsxStructureVO.Sheet> sheets = new ArrayList<>(workbook.getNumberOfSheets());
        int includedCells = 0;
        boolean truncated = false;

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            List<XlsxStructureVO.Cell> cells = new ArrayList<>();
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (includedCells < maxCells) {
                        cells.add(readCell(workbook, cell));
                        includedCells++;
                    } else {
                        truncated = true;
                    }
                }
            }
            Integer firstRow = sheet.getPhysicalNumberOfRows() == 0 ? null : sheet.getFirstRowNum();
            Integer lastRow = sheet.getPhysicalNumberOfRows() == 0 ? null : sheet.getLastRowNum();
            List<String> mergedRegions = sheet.getMergedRegions().stream()
                    .map(CellRangeAddress::formatAsString)
                    .toList();
            sheets.add(new XlsxStructureVO.Sheet(sheetIndex, sheet.getSheetName(), firstRow, lastRow,
                    mergedRegions, readCharts(sheet), cells));
        }
        return new XlsxStructureVO(fileId, versionNo, workbook.getNumberOfSheets(),
                includedCells, truncated, sheets);
    }

    private static XlsxStructureVO.Cell readCell(XSSFWorkbook workbook, Cell cell) {
        CellType type = cell.getCellType();
        String cachedType = type == CellType.FORMULA
                ? cell.getCachedFormulaResultType().name() : null;
        String formula = type == CellType.FORMULA ? cell.getCellFormula() : null;
        CellType valueType = type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
        String value = cellValue(cell, valueType);
        return new XlsxStructureVO.Cell(cell.getAddress().formatAsString(), cell.getRowIndex(),
                cell.getColumnIndex(), type.name(), cachedType, value, formula,
                readStyle(workbook, cell.getCellStyle()));
    }

    private static XlsxStructureVO.Cell emptyCell(int rowIndex, int columnIndex) {
        return new XlsxStructureVO.Cell(new CellAddress(rowIndex, columnIndex).formatAsString(),
                rowIndex, columnIndex, "BLANK", null, "", null, null);
    }

    private static String cellValue(Cell cell, CellType valueType) {
        return switch (valueType) {
            case STRING -> cell.getRichStringCellValue().getString();
            case NUMERIC -> numericValue(cell);
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> formulaError(cell.getErrorCellValue());
            case BLANK, _NONE -> "";
            case FORMULA -> "";
        };
    }

    private static String numericValue(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeException | IllegalStateException ignored) {
                // 非法日期序列仍按原始数字返回，避免整份工作簿读取失败。
            }
        }
        return NumberToTextConverter.toText(cell.getNumericCellValue());
    }

    private static String formulaError(byte code) {
        try {
            return FormulaError.forInt(code).getString();
        } catch (IllegalArgumentException ignored) {
            return Byte.toString(code);
        }
    }

    private static XlsxStructureVO.Style readStyle(XSSFWorkbook workbook, CellStyle style) {
        Font font = workbook.getFontAt(style.getFontIndex());
        return new XlsxStructureVO.Style(style.getIndex(), style.getDataFormat(),
                style.getDataFormatString(), style.getAlignment().name(),
                style.getVerticalAlignment().name(), style.getWrapText(), style.getLocked(),
                style.getHidden(), style.getFillPattern().name(), style.getFillForegroundColor(),
                style.getBorderTop().name(), style.getBorderRight().name(),
                style.getBorderBottom().name(), style.getBorderLeft().name(),
                font.getFontName(), font.getFontHeightInPoints(), font.getBold(), font.getItalic());
    }

    private static List<XlsxStructureVO.Chart> readCharts(XSSFSheet sheet) {
        if (!(sheet.getDrawingPatriarch() instanceof XSSFDrawing drawing)) {
            return List.of();
        }
        List<XlsxStructureVO.Chart> charts = new ArrayList<>();
        List<XSSFChart> source = drawing.getCharts();
        for (int index = 0; index < source.size(); index++) {
            XSSFChart chart = source.get(index);
            String title = chartTitle(chart);
            List<String> types;
            try {
                types = chart.getChartSeries().stream()
                        .map(data -> data.getClass().getSimpleName())
                        .map(name -> name.replaceFirst("^XDDF", "").replaceFirst("ChartData$", ""))
                        .map(name -> name.toUpperCase(Locale.ROOT))
                        .distinct()
                        .toList();
            } catch (RuntimeException ignored) {
                types = List.of("UNKNOWN");
            }
            charts.add(new XlsxStructureVO.Chart(index, title, chartTitleFormula(chart), types));
        }
        return charts;
    }

    private static String chartTitle(XSSFChart chart) {
        try {
            return chart.getTitleText() == null ? null : chart.getTitleText().getString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String chartTitleFormula(XSSFChart chart) {
        try {
            return chart.getTitleFormula();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int countAffectedCells(List<EditXlsxCellsDTO.Operation> operations) {
        long count = 0;
        for (EditXlsxCellsDTO.Operation operation : operations) {
            if (operation != null && "SET_RANGE".equals(operation.getType())) {
                CellRangeAddress range = parseRange(operation.getRange());
                count += (long) (range.getLastRow() - range.getFirstRow() + 1)
                        * (range.getLastColumn() - range.getFirstColumn() + 1);
            } else {
                count++;
            }
            if (count > Integer.MAX_VALUE) {
                throw new BusinessException(413, "单次单元格编辑数量超过处理限制");
            }
        }
        return (int) count;
    }

    private static void applyOperations(XSSFWorkbook workbook,
                                        List<EditXlsxCellsDTO.Operation> operations) {
        Set<String> targets = new HashSet<>();
        for (EditXlsxCellsDTO.Operation operation : operations) {
            if (operation == null || operation.getType() == null) {
                throw new BusinessException(400, "单元格编辑操作不能为空");
            }
            XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
            }
            requireWritableSheet(sheet);
            validateOperation(operation);
            if ("SET_RANGE".equals(operation.getType())) {
                applyRange(workbook, sheet, operation, targets);
                continue;
            }
            CellAddress address = parseAddress(operation.getCell());
            applySingleCell(workbook, sheet, address, operation, targets);
        }
    }

    private static void applySingleCell(XSSFWorkbook workbook, XSSFSheet sheet,
                                        CellAddress address, EditXlsxCellsDTO.Operation operation,
                                        Set<String> targets) {
            String target = sheet.getSheetName().toLowerCase(Locale.ROOT) + "!" + address.formatAsString();
            if (!targets.add(target)) {
                throw new BusinessException(400, "同一请求不能重复编辑单元格：" + target);
            }
            requireEditableMergedCell(sheet, address);

            Row row = sheet.getRow(address.getRow());
            Cell existing = row == null ? null : row.getCell(address.getColumn());
            if (existing != null && existing.isPartOfArrayFormulaGroup()) {
                throw new BusinessException(422, "暂不支持修改数组公式单元格：" + target);
            }
            if ("CLEAR".equals(operation.getType())) {
                if (existing != null) {
                    existing.setBlank();
                }
                return;
            }
            if (row == null) {
                row = sheet.createRow(address.getRow());
            }
            Cell cell = existing == null ? row.createCell(address.getColumn()) : existing;
            if ("SET_FORMULA".equals(operation.getType())) {
                setFormula(cell, operation.getFormula(), target);
            } else {
                setValue(workbook, cell, operation, target);
            }
    }

    private static void applyRange(XSSFWorkbook workbook, XSSFSheet sheet,
                                   EditXlsxCellsDTO.Operation operation, Set<String> targets) {
        CellRangeAddress range = parseRange(operation.getRange());
        int expectedRows = range.getLastRow() - range.getFirstRow() + 1;
        int expectedColumns = range.getLastColumn() - range.getFirstColumn() + 1;
        List<List<EditXlsxCellsDTO.RangeValue>> values = operation.getValues();
        if (values == null || values.size() != expectedRows) {
            throw new BusinessException(400, "SET_RANGE 的 values 行数必须与区域一致");
        }
        for (int rowOffset = 0; rowOffset < expectedRows; rowOffset++) {
            List<EditXlsxCellsDTO.RangeValue> rowValues = values.get(rowOffset);
            if (rowValues == null || rowValues.size() != expectedColumns) {
                throw new BusinessException(400, "SET_RANGE 的 values 每行列数必须与区域一致");
            }
            for (int columnOffset = 0; columnOffset < expectedColumns; columnOffset++) {
                int rowIndex = range.getFirstRow() + rowOffset;
                int columnIndex = range.getFirstColumn() + columnOffset;
                CellAddress address = new CellAddress(rowIndex, columnIndex);
                String target = sheet.getSheetName().toLowerCase(Locale.ROOT)
                        + "!" + address.formatAsString();
                if (!targets.add(target)) {
                    throw new BusinessException(400, "同一请求不能重复编辑单元格：" + target);
                }
                requireEditableMergedCell(sheet, address);
                EditXlsxCellsDTO.RangeValue rangeValue = rowValues.get(columnOffset);
                if (rangeValue == null || rangeValue.getValueType() == null) {
                    throw new BusinessException(400, "区域单元格值及其类型不能为空：" + target);
                }
                Row row = sheet.getRow(rowIndex);
                Cell cell = row == null ? null : row.getCell(columnIndex);
                if (cell != null && cell.isPartOfArrayFormulaGroup()) {
                    throw new BusinessException(422, "暂不支持修改数组公式单元格：" + target);
                }
                if ("BLANK".equals(rangeValue.getValueType())) {
                    if (rangeValue.getValue() != null) {
                        throw new BusinessException(400, "BLANK 类型不能提供 value：" + target);
                    }
                    if (cell != null) {
                        cell.setBlank();
                    }
                    continue;
                }
                if (rangeValue.getValue() == null) {
                    throw new BusinessException(400, "区域单元格值不能为空：" + target);
                }
                if (row == null) {
                    row = sheet.createRow(rowIndex);
                }
                if (cell == null) {
                    cell = row.createCell(columnIndex);
                }
                if ("FORMULA".equals(rangeValue.getValueType())) {
                    setFormula(cell, rangeValue.getValue(), target);
                } else {
                    setTypedValue(workbook, cell, rangeValue.getValueType(),
                            rangeValue.getValue(), target);
                }
            }
        }
    }

    private static CellAddress parseAddress(String rawAddress) {
        if (rawAddress == null) {
            throw new BusinessException(400, "单元格地址不能为空");
        }
        String normalized = rawAddress.replace("$", "").toUpperCase(Locale.ROOT);
        CellReference reference;
        try {
            reference = new CellReference(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "单元格地址无效：" + rawAddress);
        }
        if (reference.getRow() < 0 || reference.getRow() > SpreadsheetVersion.EXCEL2007.getLastRowIndex()
                || reference.getCol() < 0
                || reference.getCol() > SpreadsheetVersion.EXCEL2007.getLastColumnIndex()) {
            throw new BusinessException(400, "单元格地址超出 XLSX 范围：" + rawAddress);
        }
        return new CellAddress(reference.getRow(), reference.getCol());
    }

    private static CellRangeAddress parseRange(String rawRange) {
        if (rawRange == null) {
            throw new BusinessException(400, "单元格区域不能为空");
        }
        String normalized = rawRange.replace("$", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z]{1,3}[1-9][0-9]{0,6}:[A-Z]{1,3}[1-9][0-9]{0,6}$")) {
            throw new BusinessException(400, "单元格区域必须使用 A1:C3 格式");
        }
        String[] endpoints = normalized.split(":", 2);
        CellAddress first = parseAddress(endpoints[0]);
        CellAddress last = parseAddress(endpoints[1]);
        if (first.getRow() > last.getRow() || first.getColumn() > last.getColumn()) {
            throw new BusinessException(400, "单元格区域起点不能位于终点之后");
        }
        return new CellRangeAddress(first.getRow(), last.getRow(),
                first.getColumn(), last.getColumn());
    }

    private static void requireWritableSheet(XSSFSheet sheet) {
        if (sheet.getProtect()) {
            throw new BusinessException(422, "暂不支持修改受保护的工作表：" + sheet.getSheetName());
        }
    }

    private static void requireEditableMergedCell(XSSFSheet sheet, CellAddress address) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(address.getRow(), address.getColumn())
                    && (range.getFirstRow() != address.getRow()
                    || range.getFirstColumn() != address.getColumn())) {
                throw new BusinessException(400, "合并区域只能编辑左上角单元格：" + range.formatAsString());
            }
        }
    }

    private static void validateOperation(EditXlsxCellsDTO.Operation operation) {
        switch (operation.getType()) {
            case "SET_VALUE" -> {
                if (operation.getCell() == null || operation.getValueType() == null
                        || operation.getValue() == null) {
                    throw new BusinessException(400, "SET_VALUE 必须提供 valueType 和 value");
                }
                if (operation.getFormula() != null || operation.getRange() != null
                        || operation.getValues() != null) {
                    throw new BusinessException(400, "SET_VALUE 包含不适用的字段");
                }
            }
            case "SET_FORMULA" -> {
                if (operation.getCell() == null || operation.getFormula() == null
                        || operation.getFormula().isBlank()) {
                    throw new BusinessException(400, "SET_FORMULA 必须提供非空 formula");
                }
                if (operation.getValueType() != null || operation.getValue() != null
                        || operation.getRange() != null || operation.getValues() != null) {
                    throw new BusinessException(400, "SET_FORMULA 包含不适用的字段");
                }
            }
            case "CLEAR" -> {
                if (operation.getCell() == null || operation.getValueType() != null
                        || operation.getValue() != null || operation.getFormula() != null
                        || operation.getRange() != null || operation.getValues() != null) {
                    throw new BusinessException(400, "CLEAR 包含不适用或缺失的字段");
                }
            }
            case "SET_RANGE" -> {
                if (operation.getRange() == null || operation.getValues() == null
                        || operation.getCell() != null || operation.getValueType() != null
                        || operation.getValue() != null || operation.getFormula() != null) {
                    throw new BusinessException(400, "SET_RANGE 必须且只能提供 range 和 values");
                }
            }
            default -> throw new BusinessException(400, "不支持的单元格操作");
        }
    }

    private static void setValue(XSSFWorkbook workbook, Cell cell,
                                 EditXlsxCellsDTO.Operation operation, String target) {
        setTypedValue(workbook, cell, operation.getValueType(), operation.getValue(), target);
    }

    private static void setTypedValue(XSSFWorkbook workbook, Cell cell, String valueType,
                                      String value, String target) {
        switch (valueType) {
            case "STRING" -> cell.setCellValue(value);
            case "NUMBER" -> {
                try {
                    double number = new BigDecimal(value.trim()).doubleValue();
                    if (!Double.isFinite(number)) {
                        throw new NumberFormatException();
                    }
                    cell.setCellValue(number);
                } catch (NumberFormatException e) {
                    throw new BusinessException(400, "数字格式无效：" + target);
                }
            }
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BusinessException(400, "布尔值只能是 true 或 false：" + target);
                }
                cell.setCellValue(Boolean.parseBoolean(value));
            }
            case "DATE" -> setDateValue(workbook, cell, value, target);
            default -> throw new BusinessException(400, "不支持的单元格值类型");
        }
    }

    private static void applyStructureOperations(XSSFWorkbook workbook,
                                                 List<EditXlsxStructureDTO.Operation> operations) {
        for (EditXlsxStructureDTO.Operation operation : operations) {
            if (operation == null || operation.getType() == null
                    || operation.getStart() == null || operation.getCount() == null) {
                throw new BusinessException(400, "XLSX 结构编辑操作字段不完整");
            }
            if (operation.getStart() <= 0 || operation.getCount() <= 0
                    || operation.getCount() > 1000) {
                throw new BusinessException(400, "结构编辑的起始位置或数量无效");
            }
            XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
            }
            requireWritableSheet(sheet);
            if (!sheet.getTables().isEmpty()) {
                throw new BusinessException(422, "暂不支持在包含 Excel 表对象的工作表中增删行列");
            }
            int startIndex = operation.getStart() - 1;
            switch (operation.getType()) {
                case "INSERT_ROWS" -> insertRows(sheet, startIndex, operation.getCount());
                case "DELETE_ROWS" -> deleteRows(sheet, startIndex, operation.getCount());
                case "INSERT_COLUMNS" -> insertColumns(sheet, startIndex, operation.getCount());
                case "DELETE_COLUMNS" -> deleteColumns(sheet, startIndex, operation.getCount());
                default -> throw new BusinessException(400, "不支持的 XLSX 结构操作");
            }
        }
    }

    private static void insertRows(XSSFSheet sheet, int start, int count) {
        int maxRow = SpreadsheetVersion.EXCEL2007.getLastRowIndex();
        int lastRow = sheet.getPhysicalNumberOfRows() == 0 ? -1 : sheet.getLastRowNum();
        if (start > lastRow + 1) {
            throw new BusinessException(400, "插入行的起始位置不能越过已有数据末尾");
        }
        if ((long) Math.max(lastRow, start - 1) + count > maxRow) {
            throw new BusinessException(400, "插入后行号超出 XLSX 范围");
        }
        validateMergedInsertion(sheet, start, true);
        requireNoShiftedArrayFormula(sheet, start, true);
        if (start <= lastRow) {
            sheet.shiftRows(start, lastRow, count, true, false);
        }
        Row template = start > 0 ? sheet.getRow(start - 1) : sheet.getRow(start + count);
        for (int rowIndex = start; rowIndex < start + count; rowIndex++) {
            Row inserted = sheet.getRow(rowIndex);
            if (inserted == null) {
                inserted = sheet.createRow(rowIndex);
            }
            copyRowFormatting(template, inserted);
        }
    }

    private static void deleteRows(XSSFSheet sheet, int start, int count) {
        int lastRow = sheet.getPhysicalNumberOfRows() == 0 ? -1 : sheet.getLastRowNum();
        long endLong = (long) start + count - 1;
        if (start < 0 || endLong > lastRow) {
            throw new BusinessException(400, "删除行的范围超出已有数据");
        }
        int end = (int) endLong;
        validateAndRemoveMergedDeletion(sheet, start, end, true);
        requireNoShiftedArrayFormula(sheet, start, true);
        for (int rowIndex = start; rowIndex <= end; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                sheet.removeRow(row);
            }
        }
        if (end < lastRow) {
            sheet.shiftRows(end + 1, lastRow, -count, true, false);
        }
    }

    private static void insertColumns(XSSFSheet sheet, int start, int count) {
        int maxColumn = SpreadsheetVersion.EXCEL2007.getLastColumnIndex();
        int lastColumn = lastUsedColumn(sheet);
        if (start > lastColumn + 1) {
            throw new BusinessException(400, "插入列的起始位置不能越过已有数据末尾");
        }
        if ((long) Math.max(lastColumn, start - 1) + count > maxColumn) {
            throw new BusinessException(400, "插入后列号超出 XLSX 范围");
        }
        validateMergedInsertion(sheet, start, false);
        requireNoShiftedArrayFormula(sheet, start, false);
        List<ColumnFormat> shiftedFormats = snapshotColumnFormats(sheet, start, lastColumn);
        ColumnFormat insertedFormat = columnFormat(sheet,
                start > 0 ? start - 1 : Math.min(start, Math.max(lastColumn, 0)));
        if (start <= lastColumn) {
            sheet.shiftColumns(start, lastColumn, count);
            for (int offset = 0; offset < shiftedFormats.size(); offset++) {
                applyColumnFormat(sheet, start + count + offset, shiftedFormats.get(offset));
            }
        }
        for (int column = start; column < start + count; column++) {
            applyColumnFormat(sheet, column, insertedFormat);
        }
    }

    private static void deleteColumns(XSSFSheet sheet, int start, int count) {
        int lastColumn = lastUsedColumn(sheet);
        long endLong = (long) start + count - 1;
        if (start < 0 || endLong > lastColumn) {
            throw new BusinessException(400, "删除列的范围超出已有数据");
        }
        int end = (int) endLong;
        validateAndRemoveMergedDeletion(sheet, start, end, false);
        requireNoShiftedArrayFormula(sheet, start, false);
        List<ColumnFormat> shiftedFormats = snapshotColumnFormats(sheet, end + 1, lastColumn);
        for (Row row : sheet) {
            for (int column = start; column <= end; column++) {
                Cell cell = row.getCell(column);
                if (cell != null) {
                    row.removeCell(cell);
                }
            }
        }
        if (end < lastColumn) {
            sheet.shiftColumns(end + 1, lastColumn, -count);
            for (int offset = 0; offset < shiftedFormats.size(); offset++) {
                applyColumnFormat(sheet, start + offset, shiftedFormats.get(offset));
            }
        }
        ColumnFormat defaultFormat = new ColumnFormat(sheet.getDefaultColumnWidth() * 256,
                false, sheet.getWorkbook().getCellStyleAt(0));
        for (int column = Math.max(start, lastColumn - count + 1);
             column <= lastColumn; column++) {
            applyColumnFormat(sheet, column, defaultFormat);
        }
    }

    private static void validateMergedInsertion(XSSFSheet sheet, int start, boolean rows) {
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            int first = rows ? range.getFirstRow() : range.getFirstColumn();
            int last = rows ? range.getLastRow() : range.getLastColumn();
            if (first < start && start <= last) {
                throw new BusinessException(400, "插入位置不能切开合并区域：" + range.formatAsString());
            }
        }
    }

    private static void validateAndRemoveMergedDeletion(XSSFSheet sheet, int start, int end,
                                                         boolean rows) {
        List<Integer> remove = new ArrayList<>();
        for (int index = 0; index < sheet.getNumMergedRegions(); index++) {
            CellRangeAddress range = sheet.getMergedRegion(index);
            int first = rows ? range.getFirstRow() : range.getFirstColumn();
            int last = rows ? range.getLastRow() : range.getLastColumn();
            if (start <= last && end >= first) {
                if (start <= first && end >= last) {
                    remove.add(index);
                } else {
                    throw new BusinessException(400,
                            "删除范围不能只覆盖合并区域的一部分：" + range.formatAsString());
                }
            }
        }
        remove.sort(java.util.Comparator.reverseOrder());
        remove.forEach(sheet::removeMergedRegion);
    }

    private static void copyRowFormatting(Row source, Row target) {
        if (source == null) {
            return;
        }
        target.setHeight(source.getHeight());
        target.setZeroHeight(source.getZeroHeight());
        if (source.isFormatted()) {
            target.setRowStyle(source.getRowStyle());
        }
        for (Cell sourceCell : source) {
            Cell targetCell = target.getCell(sourceCell.getColumnIndex());
            if (targetCell == null) {
                targetCell = target.createCell(sourceCell.getColumnIndex(), CellType.BLANK);
            }
            targetCell.setCellStyle(sourceCell.getCellStyle());
        }
    }

    private static void requireNoShiftedArrayFormula(XSSFSheet sheet, int start, boolean rows) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                int position = rows ? cell.getRowIndex() : cell.getColumnIndex();
                if (position >= start && cell.isPartOfArrayFormulaGroup()) {
                    throw new BusinessException(422,
                            "暂不支持移动数组公式单元格：" + cell.getAddress().formatAsString());
                }
            }
        }
    }

    private static int lastUsedColumn(XSSFSheet sheet) {
        int last = -1;
        for (Row row : sheet) {
            if (row.getLastCellNum() > 0) {
                last = Math.max(last, row.getLastCellNum() - 1);
            }
        }
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            last = Math.max(last, range.getLastColumn());
        }
        return last;
    }

    private static List<ColumnFormat> snapshotColumnFormats(XSSFSheet sheet, int first, int last) {
        if (first > last) {
            return List.of();
        }
        List<ColumnFormat> formats = new ArrayList<>(last - first + 1);
        for (int column = first; column <= last; column++) {
            formats.add(columnFormat(sheet, column));
        }
        return formats;
    }

    private static ColumnFormat columnFormat(XSSFSheet sheet, int column) {
        CellStyle style = sheet.getColumnStyle(column);
        if (style == null) {
            style = sheet.getWorkbook().getCellStyleAt(0);
        }
        return new ColumnFormat(sheet.getColumnWidth(column), sheet.isColumnHidden(column), style);
    }

    private static void applyColumnFormat(XSSFSheet sheet, int column, ColumnFormat format) {
        sheet.setColumnWidth(column, format.width());
        sheet.setColumnHidden(column, format.hidden());
        sheet.setDefaultColumnStyle(column, format.style());
    }

    private static void setDateValue(XSSFWorkbook workbook, Cell cell, String value, String target) {
        try {
            if (value.contains("T")) {
                cell.setCellValue(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                applyDateFormatIfGeneral(workbook, cell, "yyyy-mm-dd hh:mm:ss");
            } else {
                cell.setCellValue(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE));
                applyDateFormatIfGeneral(workbook, cell, "yyyy-mm-dd");
            }
        } catch (DateTimeException e) {
            throw new BusinessException(400, "日期必须使用 yyyy-MM-dd 或 ISO 本地日期时间：" + target);
        }
    }

    private static void applyDateFormatIfGeneral(XSSFWorkbook workbook, Cell cell, String format) {
        if (cell.getCellStyle().getDataFormat() != 0) {
            return;
        }
        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.cloneStyleFrom(cell.getCellStyle());
        dateStyle.setDataFormat(workbook.createDataFormat().getFormat(format));
        cell.setCellStyle(dateStyle);
    }

    private static void setFormula(Cell cell, String rawFormula, String target) {
        String formula = rawFormula.trim();
        if (formula.startsWith("=")) {
            formula = formula.substring(1).trim();
        }
        if (formula.isEmpty()) {
            throw new BusinessException(400, "公式不能为空：" + target);
        }
        if (formula.length() > 8192) {
            throw new BusinessException(400, "公式长度不能超过 8192：" + target);
        }
        if (formula.contains("://") || formula.contains("|")
                || EXTERNAL_WORKBOOK_REFERENCE.matcher(formula).find()
                || UNSAFE_FORMULA_FUNCTION.matcher(formula).find()) {
            throw new BusinessException(400, "公式包含外部引用或高风险函数：" + target);
        }
        try {
            cell.setCellFormula(formula);
        } catch (FormulaParseException | IllegalArgumentException | IllegalStateException e) {
            throw new BusinessException(400, "公式语法无效：" + target);
        }
    }

    private VersionVO editAndSave(Long userId, Long fileId, Integer sourceVersion,
                                  String instruction, String summary,
                                  Consumer<XSSFWorkbook> editor) {
        FileDownload version = versionService.downloadVersion(userId, fileId, sourceVersion);
        if (!isXlsx(version)) {
            throw new BusinessException(400, "当前版本不是 XLSX 文档");
        }

        byte[] edited;
        try (InputStream input = version.resource().getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            editor.accept(workbook);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(output);
            edited = output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("编辑 XLSX 失败, fileId={}, sourceVersion={}", fileId, sourceVersion, e);
            throw new BusinessException(422, "XLSX 文档无法编辑或内容已损坏");
        }
        if (edited.length > properties.getUpload().getMaxSizeBytes()) {
            throw new BusinessException(413, "编辑后的 XLSX 超过文件大小限制");
        }

        String storageKey = fileStorage.store(edited, "xlsx");
        try {
            DocumentVersion created = versionWriter.appendVersion(
                    fileId, userId,
                    new VersionWriter.VersionContent(storageKey, edited.length, Digests.sha256Hex(edited)),
                    ChangeType.MANUAL_EDIT, instruction, summary, sourceVersion);
            return VersionVO.from(created);
        } catch (RuntimeException e) {
            if (!fileStorage.delete(storageKey)) {
                log.warn("新增版本失败且无法清理 XLSX 编辑产物, storageKey={}", storageKey);
            }
            throw e;
        }
    }

    private static boolean isXlsx(FileDownload version) {
        if (XLSX_CONTENT_TYPE.equalsIgnoreCase(version.contentType())) {
            return true;
        }
        String extension = version.asset().getExtension();
        return extension != null && "xlsx".equals(extension.toLowerCase(Locale.ROOT));
    }

    private record ColumnFormat(int width, boolean hidden, CellStyle style) {
    }
}
