package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditXlsxCellsDTO;
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
    public VersionVO editCells(Long userId, Long fileId, Integer sourceVersion,
                               EditXlsxCellsDTO request) {
        if (request.getOperations().size() > properties.getXlsx().getMaxEditOperations()) {
            throw new BusinessException(413, "单次单元格编辑数量超过处理限制");
        }
        FileDownload version = versionService.downloadVersion(userId, fileId, sourceVersion);
        if (!isXlsx(version)) {
            throw new BusinessException(400, "当前版本不是 XLSX 文档");
        }

        byte[] edited;
        try (InputStream input = version.resource().getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            applyOperations(workbook, request.getOperations());
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
                    ChangeType.MANUAL_EDIT,
                    request.getInstruction(),
                    "编辑 " + request.getOperations().size() + " 个 XLSX 单元格",
                    sourceVersion);
            return VersionVO.from(created);
        } catch (RuntimeException e) {
            if (!fileStorage.delete(storageKey)) {
                log.warn("新增版本失败且无法清理 XLSX 编辑产物, storageKey={}", storageKey);
            }
            throw e;
        }
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

    private static void applyOperations(XSSFWorkbook workbook,
                                        List<EditXlsxCellsDTO.Operation> operations) {
        Set<String> targets = new HashSet<>();
        for (EditXlsxCellsDTO.Operation operation : operations) {
            XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
            }
            CellAddress address = parseAddress(operation.getCell());
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
            validateOperation(operation);
            if ("CLEAR".equals(operation.getType())) {
                if (existing != null) {
                    existing.setBlank();
                }
                continue;
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
    }

    private static CellAddress parseAddress(String rawAddress) {
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
                if (operation.getValueType() == null || operation.getValue() == null) {
                    throw new BusinessException(400, "SET_VALUE 必须提供 valueType 和 value");
                }
                if (operation.getFormula() != null) {
                    throw new BusinessException(400, "SET_VALUE 不能同时提供 formula");
                }
            }
            case "SET_FORMULA" -> {
                if (operation.getFormula() == null || operation.getFormula().isBlank()) {
                    throw new BusinessException(400, "SET_FORMULA 必须提供非空 formula");
                }
                if (operation.getValueType() != null || operation.getValue() != null) {
                    throw new BusinessException(400, "SET_FORMULA 不能同时提供 valueType 或 value");
                }
            }
            case "CLEAR" -> {
                if (operation.getValueType() != null || operation.getValue() != null
                        || operation.getFormula() != null) {
                    throw new BusinessException(400, "CLEAR 不能提供 valueType、value 或 formula");
                }
            }
            default -> throw new BusinessException(400, "不支持的单元格操作");
        }
    }

    private static void setValue(XSSFWorkbook workbook, Cell cell,
                                 EditXlsxCellsDTO.Operation operation, String target) {
        String value = operation.getValue();
        switch (operation.getValueType()) {
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

    private static boolean isXlsx(FileDownload version) {
        if (XLSX_CONTENT_TYPE.equalsIgnoreCase(version.contentType())) {
            return true;
        }
        String extension = version.asset().getExtension();
        return extension != null && "xlsx".equals(extension.toLowerCase(Locale.ROOT));
    }
}
