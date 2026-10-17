package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.EditXlsxDataDTO;
import com.documenter.dto.EditXlsxSheetsDTO;
import com.documenter.dto.EditXlsxStructureDTO;
import com.documenter.dto.FormatXlsxCellsDTO;
import com.documenter.dto.InsertXlsxImagesDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.service.XlsxDocumentService;
import com.documenter.util.Digests;
import com.documenter.util.XlsxFormulaTarget;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxFormulaResultsVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxStructureVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaRenderer;
import org.apache.poi.ss.formula.FormulaShifter;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.eval.NotImplementedException;
import org.apache.poi.ss.formula.ptg.AreaPtgBase;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.Pxg;
import org.apache.poi.ss.formula.ptg.Pxg3D;
import org.apache.poi.ss.formula.ptg.RefPtgBase;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    public XlsxFormulaResultsVO calculateFormulas(Long userId, Long fileId, Integer versionNo,
                                                 String sheetName, String rawRange) {
        CellRangeAddress range = parseArea(rawRange);
        if (area(range) > properties.getXlsx().getMaxEditOperations()) {
            throw new BusinessException(413, "单次公式计算区域超过处理限制");
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
            requireCalculableWorkbook(workbook);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<XlsxFormulaResultsVO.Result> results = new ArrayList<>();
            for (int rowIndex = range.getFirstRow(); rowIndex <= range.getLastRow(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
                    Cell cell = row.getCell(column);
                    if (cell != null && cell.getCellType() == CellType.FORMULA) {
                        results.add(evaluateFormula(workbook, evaluator, cell));
                    }
                }
            }
            return new XlsxFormulaResultsVO(fileId, versionNo, sheet.getSheetName(),
                    range.formatAsString(), results);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("计算 XLSX 公式失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(422, "XLSX 文档无法计算或内容已损坏");
        }
    }

    private void requireCalculableWorkbook(XSSFWorkbook workbook) {
        // POI 部分日期函数按 1900 日期系统计算，1904 工作簿会产生日期偏差。
        if (workbook.isDate1904()) {
            throw new BusinessException(422, "当前计算引擎暂不支持 1904 日期系统，请在办公软件中重算");
        }
        int cells = 0;
        int formulas = 0;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (++cells > properties.getXlsx().getMaxReadCells()) {
                        throw new BusinessException(413, "工作簿单元格数量超过公式计算限制");
                    }
                    if (cell.getCellType() == CellType.FORMULA) {
                        if (++formulas > properties.getXlsx().getMaxEditOperations()) {
                            throw new BusinessException(413, "工作簿公式数量超过计算限制");
                        }
                        requireSafeFormula(cell.getCellFormula(), cell.getAddress().formatAsString());
                    }
                }
            }
        }
        for (var name : workbook.getAllNames()) {
            if (name.getRefersToFormula() != null) {
                requireSafeFormula(name.getRefersToFormula(), name.getNameName());
            }
        }
    }

    private static XlsxFormulaResultsVO.Result evaluateFormula(XSSFWorkbook workbook,
                                                               FormulaEvaluator evaluator,
                                                               Cell cell) {
        String address = cell.getAddress().formatAsString();
        String formula = cell.getCellFormula();
        try {
            CellValue result = evaluator.evaluate(cell);
            if (result == null) {
                return new XlsxFormulaResultsVO.Result(address, formula, "EVALUATION_FAILED",
                        null, null, "计算引擎未返回结果，请在办公软件中重算");
            }
            String value = switch (result.getCellType()) {
                case STRING -> result.getStringValue();
                case BOOLEAN -> Boolean.toString(result.getBooleanValue());
                case ERROR -> formulaError(result.getErrorValue());
                case NUMERIC -> DateUtil.isADateFormat(cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString())
                        && DateUtil.isValidExcelDate(result.getNumberValue())
                        ? DateUtil.getLocalDateTime(result.getNumberValue(), workbook.isDate1904())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        : NumberToTextConverter.toText(result.getNumberValue());
                case BLANK, _NONE -> "";
                case FORMULA -> throw new IllegalStateException("unexpected formula result");
            };
            String status = result.getCellType() == CellType.ERROR ? "ERROR" : "OK";
            return new XlsxFormulaResultsVO.Result(address, formula, status,
                    result.getCellType().name(), value, null);
        } catch (NotImplementedException e) {
            return new XlsxFormulaResultsVO.Result(address, formula, "UNSUPPORTED",
                    null, null, "当前计算引擎不支持此公式或其依赖，请在办公软件中重算");
        } catch (RuntimeException e) {
            return new XlsxFormulaResultsVO.Result(address, formula, "EVALUATION_FAILED",
                    null, null, "此公式或其依赖无法计算，请在办公软件中重算");
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
                "编辑 " + affectedCells + " 个 XLSX 单元格"
                        + (request.isRecalculateFormulas() ? "，已重算公式缓存" : ""),
                workbook -> {
                    applyOperations(workbook, request.getOperations());
                    if (request.isRecalculateFormulas()) {
                        recalculateFormulaCaches(workbook);
                    }
                });
    }

    private void recalculateFormulaCaches(XSSFWorkbook workbook) {
        requireCalculableWorkbook(workbook);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            XSSFSheet sheet = workbook.getSheetAt(sheetIndex);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) {
                        continue;
                    }
                    String target = sheet.getSheetName() + "!" + cell.getAddress().formatAsString();
                    try {
                        // 保留公式，同时将数字、文本、布尔或 Excel 错误结果保存进单元格缓存。
                        evaluator.evaluateFormulaCell(cell);
                    } catch (NotImplementedException e) {
                        throw new BusinessException(422, "公式缓存重算不支持此公式或其依赖：" + target
                                + "；本次编辑未保存，可关闭 recalculateFormulas 后在办公软件中重算");
                    } catch (RuntimeException e) {
                        throw new BusinessException(422, "公式缓存重算失败：" + target
                                + "；本次编辑未保存，可关闭 recalculateFormulas 后在办公软件中重算");
                    }
                }
            }
        }
    }

    @Override
    public Map<String, XlsxStructureVO.Cell> validateFormulaPlan(Long userId, Long fileId, Integer sourceVersion,
                                                                DocumentEditPlan plan) {
        var operations = formulaOperations(plan);
        var source = versionService.downloadVersion(userId, fileId, sourceVersion);
        if (!isXlsx(source)) throw new BusinessException(400, "当前版本不是 XLSX 文档");
        try (InputStream input = source.resource().getInputStream(); var workbook = new XSSFWorkbook(input)) {
            Map<String, XlsxStructureVO.Cell> before = new HashMap<>();
            for (var operation : operations) {
                var sheet = workbook.getSheet(operation.getSheetName());
                if (sheet == null) throw new BusinessException(404, "公式目标工作表不存在");
                var address = new CellAddress(operation.getCell());
                var row = sheet.getRow(address.getRow());
                var cell = row == null ? null : row.getCell(address.getColumn());
                var target = XlsxFormulaTarget.of(operation.getSheetName(), operation.getCell());
                before.put(target.key(), cell == null ? emptyCell(address.getRow(), address.getColumn()) : readCell(workbook, cell));
            }
            applyOperations(workbook, operations);
            return Map.copyOf(before);
        } catch (BusinessException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new BusinessException(422, "XLSX 公式计划无法校验或内容已损坏");
        }
    }

    @Override
    public VersionVO editFormulaPlan(Long userId, Long fileId, Integer sourceVersion, DocumentEditPlan plan, String instruction) {
        var operations = formulaOperations(plan);
        return editAndSave(userId, fileId, sourceVersion, instruction,
                "AI 设置 " + operations.size() + " 个 XLSX 公式", ChangeType.AI_EDIT,
                workbook -> applyOperations(workbook, operations));
    }

    private List<EditXlsxCellsDTO.Operation> formulaOperations(DocumentEditPlan plan) {
        if (plan == null || plan.isEmpty() || plan.operations().size() > 50
                || plan.operations().size() > properties.getXlsx().getMaxEditOperations()) {
            throw new BusinessException(422, "公式计划必须包含 1 到处理上限以内的操作");
        }
        return plan.operations().stream().map(item -> {
            if (item.operationType() != DocumentEditPlan.OperationType.SET_FORMULA || item.content() == null
                    || item.content().isBlank() || item.content().length() > 8192 || item.image() != null || item.format() != null) {
                throw new BusinessException(422, "XLSX 计划仅支持受限的 SET_FORMULA 操作");
            }
            var target = XlsxFormulaTarget.parse(item.target());
            var operation = new EditXlsxCellsDTO.Operation();
            operation.setType("SET_FORMULA");
            operation.setSheetName(target.sheetName());
            operation.setCell(target.cell());
            operation.setFormula(item.content());
            return operation;
        }).toList();
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

    @Override
    public VersionVO editSheets(Long userId, Long fileId, Integer sourceVersion,
                                EditXlsxSheetsDTO request) {
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "执行 " + request.getOperations().size() + " 个 XLSX 工作表操作",
                workbook -> applySheetOperations(workbook, request.getOperations()));
    }

    @Override
    public VersionVO editData(Long userId, Long fileId, Integer sourceVersion,
                              EditXlsxDataDTO request) {
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "执行 " + request.getOperations().size() + " 个 XLSX 数据操作",
                workbook -> applyDataOperations(workbook, request.getOperations(),
                        properties.getXlsx().getMaxSortableCells()));
    }

    @Override
    public VersionVO formatCells(Long userId, Long fileId, Integer sourceVersion,
                                 FormatXlsxCellsDTO request) {
        long affectedCells = countFormattedCells(request.getOperations());
        if (affectedCells > properties.getXlsx().getMaxEditOperations()) {
            throw new BusinessException(413, "单次格式化单元格数量超过处理限制");
        }
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "格式化 " + affectedCells + " 个 XLSX 单元格",
                workbook -> applyFormatOperations(workbook, request.getOperations()));
    }

    private static long countFormattedCells(List<FormatXlsxCellsDTO.Operation> operations) {
        long count = 0;
        for (FormatXlsxCellsDTO.Operation operation : operations) {
            if (operation == null || operation.getRange() == null) {
                throw new BusinessException(400, "XLSX 格式操作字段不完整");
            }
            count += area(parseArea(operation.getRange()));
        }
        return count;
    }

    @Override
    public VersionVO insertImages(Long userId, Long fileId, Integer sourceVersion, InsertXlsxImagesDTO request) {
        validateImageRequest(request);
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "插入 " + request.getOperations().size() + " 张 XLSX 图片", workbook -> {
                    long imageBytes = 0;
                    for (var operation : request.getOperations()) {
                        XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
                        if (sheet == null) throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
                        requireWritableSheet(sheet);
                        XSSFClientAnchor anchor = imageAnchor(sheet, operation);
                        EmbeddedImage image = loadImage(userId, operation,
                                properties.getUpload().getMaxSizeBytes() - imageBytes);
                        imageBytes += image.bytes().length;
                        int pictureIndex = workbook.addPicture(image.bytes(), image.pictureType());
                        var picture = sheet.createDrawingPatriarch().createPicture(anchor, pictureIndex);
                        picture.getCTPicture().getSpPr().getXfrm().getExt().setCx(Units.pixelToEMU(operation.getWidthPixels()));
                        picture.getCTPicture().getSpPr().getXfrm().getExt().setCy(Units.pixelToEMU(operation.getHeightPixels()));
                        picture.getCTPicture().getNvPicPr().getCNvPr().setDescr(
                                "Documenter imageFileId=" + operation.getImageFileId()
                                        + "; imageVersionNo=" + operation.getImageVersionNo());
                    }
                });
    }

    private static void validateImageRequest(InsertXlsxImagesDTO request) {
        if (request == null || request.getOperations() == null || request.getOperations().isEmpty()
                || request.getOperations().size() > 20
                || (request.getInstruction() != null && request.getInstruction().length() > 2000)) {
            throw new BusinessException(400, "图片插入请求无效，单次需要 1 到 20 张图片");
        }
        for (var operation : request.getOperations()) {
            if (operation == null || operation.getSheetName() == null || operation.getSheetName().isBlank()
                    || operation.getImageFileId() == null || operation.getImageFileId() <= 0
                    || operation.getImageVersionNo() == null || operation.getImageVersionNo() <= 0
                    || operation.getWidthPixels() == null || operation.getWidthPixels() < 1 || operation.getWidthPixels() > 10000
                    || operation.getHeightPixels() == null || operation.getHeightPixels() < 1 || operation.getHeightPixels() > 10000) {
                throw new BusinessException(400, "必须指定工作表、正数图片 ID 与版本，以及 1 到 10000 像素的显示尺寸");
            }
            parseAddress(operation.getCell());
        }
    }

    private EmbeddedImage loadImage(Long userId, InsertXlsxImagesDTO.Operation operation, long remainingBytes) {
        FileDownload image = versionService.downloadVersion(userId, operation.getImageFileId(), operation.getImageVersionNo());
        String expected = "image/png".equalsIgnoreCase(image.contentType()) ? "PNG"
                : "image/jpeg".equalsIgnoreCase(image.contentType()) ? "JPEG" : null;
        if (expected == null) throw new BusinessException(400, "XLSX 图片插入仅支持 PNG 和 JPEG");
        if (remainingBytes <= 0 || image.contentLength() > remainingBytes) {
            throw new BusinessException(413, "插入图片的总字节数超过文件大小限制");
        }
        try (InputStream input = image.resource().getInputStream()) {
            int bound = (int) Math.min(remainingBytes, Integer.MAX_VALUE - 1L);
            byte[] bytes = input.readNBytes(bound + 1);
            if (bytes.length > bound) throw new BusinessException(413, "插入图片的总字节数超过文件大小限制");
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw new BusinessException(422, "图片内容无法解码");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    if (!expected.equalsIgnoreCase(reader.getFormatName())) {
                        throw new BusinessException(422, "图片实际格式与版本元数据不一致");
                    }
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    long limit = properties.getUpload().getMaxImagePixels();
                    if (pixels <= 0 || (limit > 0 && pixels > limit)) {
                        throw new BusinessException(413, "图片像素超过处理限制");
                    }
                    var decoded = reader.read(0);
                    if (decoded == null) throw new BusinessException(422, "图片解码结果为空");
                    decoded.flush();
                } finally {
                    reader.dispose();
                }
            }
            return new EmbeddedImage(bytes, "PNG".equals(expected) ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG);
        } catch (IOException e) {
            throw new BusinessException(422, "图片内容无法读取或已损坏");
        }
    }

    private static XSSFClientAnchor imageAnchor(XSSFSheet sheet, InsertXlsxImagesDTO.Operation operation) {
        CellAddress start = parseAddress(operation.getCell());
        AnchorEnd column = imageEnd(sheet, start.getColumn(), Units.pixelToEMU(operation.getWidthPixels()), true);
        AnchorEnd row = imageEnd(sheet, start.getRow(), Units.pixelToEMU(operation.getHeightPixels()), false);
        var anchor = new XSSFClientAnchor(0, 0, column.offset(), row.offset(),
                start.getColumn(), start.getRow(), column.index(), row.index());
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        return anchor;
    }

    private static AnchorEnd imageEnd(XSSFSheet sheet, int start, long remaining, boolean column) {
        int last = column ? SpreadsheetVersion.EXCEL2007.getLastColumnIndex() : SpreadsheetVersion.EXCEL2007.getLastRowIndex();
        for (int index = start; index <= last; index++) {
            long size;
            if (column) {
                size = sheet.isColumnHidden(index) ? 0 : Math.round(sheet.getColumnWidthInPixels(index) * Units.EMU_PER_PIXEL);
            } else {
                Row source = sheet.getRow(index);
                size = source != null && source.getZeroHeight() ? 0
                        : Units.toEMU(source == null ? sheet.getDefaultRowHeightInPoints() : source.getHeightInPoints());
            }
            if (remaining <= size) return new AnchorEnd(index, (int) remaining);
            remaining -= size;
        }
        throw new BusinessException(400, "图片显示范围超出 XLSX 行列边界");
    }

    private record AnchorEnd(int index, int offset) { }
    private record EmbeddedImage(byte[] bytes, int pictureType) { }

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
            if (operation != null && ("SET_RANGE".equals(operation.getType())
                    || "FILL_FORMULA".equals(operation.getType()))) {
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
            if ("FILL_FORMULA".equals(operation.getType())) {
                applyFormulaFill(workbook, sheet, operation, targets);
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

    private static void applyFormulaFill(XSSFWorkbook workbook, XSSFSheet sheet,
                                          EditXlsxCellsDTO.Operation operation, Set<String> targets) {
        CellRangeAddress range = parseRange(operation.getRange());
        String template = normalizeFormula(operation.getFormula(), range.formatAsString());
        int sheetIndex = workbook.getSheetIndex(sheet);
        XSSFEvaluationWorkbook evaluationWorkbook = XSSFEvaluationWorkbook.create(workbook);
        // 每个目标从原始模板重新解析，防止上一个目标的位移累积到下一个目标。
        for (int row = range.getFirstRow(); row <= range.getLastRow(); row++) {
            for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
                Ptg[] tokens = parseFormula(workbook, sheetIndex, range.getFirstRow(), template);
                int rowOffset = row - range.getFirstRow();
                int columnOffset = column - range.getFirstColumn();
                if (rowOffset != 0) {
                    FormulaShifter.createForRowCopy(sheetIndex, sheet.getSheetName(),
                            range.getFirstRow(), range.getFirstRow(), rowOffset,
                            SpreadsheetVersion.EXCEL2007).adjustFormula(tokens, sheetIndex);
                }
                if (columnOffset != 0) {
                    FormulaShifter.createForColumnCopy(sheetIndex, sheet.getSheetName(),
                            range.getFirstColumn(), range.getFirstColumn(), columnOffset,
                            SpreadsheetVersion.EXCEL2007).adjustFormula(tokens, sheetIndex);
                }
                validateFormulaReferences(workbook, tokens);
                EditXlsxCellsDTO.Operation target = new EditXlsxCellsDTO.Operation();
                target.setType("SET_FORMULA");
                target.setFormula(FormulaRenderer.toFormulaString(evaluationWorkbook, tokens));
                applySingleCell(workbook, sheet, new CellAddress(row, column), target, targets);
            }
        }
    }

    private static CellAddress parseAddress(String rawAddress) {
        if (rawAddress == null) {
            throw new BusinessException(400, "单元格地址不能为空");
        }
        String normalized = rawAddress.replace("$", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z]{1,3}[1-9][0-9]{0,6}$")) {
            throw new BusinessException(400, "单元格地址必须使用 A1 格式");
        }
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
            case "FILL_FORMULA" -> {
                if (operation.getRange() == null || operation.getFormula() == null
                        || operation.getFormula().isBlank() || operation.getCell() != null
                        || operation.getValueType() != null || operation.getValue() != null
                        || operation.getValues() != null) {
                    throw new BusinessException(400, "FILL_FORMULA 必须且只能提供 range 和 formula");
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
        // POI 的 setCellValue 在已有公式上只更新缓存；显式移除公式才能真正替换为普通值。
        cell.setBlank();
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

    private static void applySheetOperations(XSSFWorkbook workbook,
                                             List<EditXlsxSheetsDTO.Operation> operations) {
        for (EditXlsxSheetsDTO.Operation operation : operations) {
            if (operation == null || operation.getType() == null
                    || operation.getSheetName() == null) {
                throw new BusinessException(400, "工作表操作字段不完整");
            }
            switch (operation.getType()) {
                case "CREATE" -> createSheet(workbook, operation);
                case "RENAME" -> renameSheet(workbook, operation);
                case "DELETE" -> deleteSheet(workbook, operation);
                default -> throw new BusinessException(400, "不支持的工作表操作");
            }
        }
    }

    private static void createSheet(XSSFWorkbook workbook, EditXlsxSheetsDTO.Operation operation) {
        if (operation.getNewName() != null) {
            throw new BusinessException(400, "CREATE 不适用 newName 字段");
        }
        validateSheetName(operation.getSheetName());
        if (workbook.getSheet(operation.getSheetName()) != null) {
            throw new BusinessException(400, "工作表名称已存在：" + operation.getSheetName());
        }
        int position = operation.getPosition() == null
                ? workbook.getNumberOfSheets() + 1 : operation.getPosition();
        if (position < 1 || position > workbook.getNumberOfSheets() + 1) {
            throw new BusinessException(400, "新工作表位置超出范围");
        }
        workbook.createSheet(operation.getSheetName());
        if (position <= workbook.getNumberOfSheets()) {
            workbook.setSheetOrder(operation.getSheetName(), position - 1);
        }
    }

    private static void renameSheet(XSSFWorkbook workbook, EditXlsxSheetsDTO.Operation operation) {
        if (operation.getNewName() == null || operation.getPosition() != null) {
            throw new BusinessException(400, "RENAME 必须且只能额外提供 newName");
        }
        int index = workbook.getSheetIndex(operation.getSheetName());
        if (index < 0) {
            throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
        }
        validateSheetName(operation.getNewName());
        int duplicate = workbook.getSheetIndex(operation.getNewName());
        if (duplicate >= 0 && duplicate != index) {
            throw new BusinessException(400, "工作表名称已存在：" + operation.getNewName());
        }
        workbook.setSheetName(index, operation.getNewName());
    }

    private static void deleteSheet(XSSFWorkbook workbook, EditXlsxSheetsDTO.Operation operation) {
        if (operation.getNewName() != null || operation.getPosition() != null) {
            throw new BusinessException(400, "DELETE 包含不适用的字段");
        }
        if (workbook.getNumberOfSheets() == 1) {
            throw new BusinessException(400, "XLSX 至少需要保留一个工作表");
        }
        int index = workbook.getSheetIndex(operation.getSheetName());
        if (index < 0) {
            throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
        }
        workbook.removeSheetAt(index);
    }

    private static void validateSheetName(String name) {
        try {
            WorkbookUtil.validateSheetName(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "工作表名称无效：" + name);
        }
    }

    private static void applyDataOperations(XSSFWorkbook workbook,
                                            List<EditXlsxDataDTO.Operation> operations,
                                            int maxSortableCells) {
        for (EditXlsxDataDTO.Operation operation : operations) {
            if (operation == null || operation.getType() == null
                    || operation.getSheetName() == null) {
                throw new BusinessException(400, "XLSX 数据操作字段不完整");
            }
            XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
            }
            requireWritableSheet(sheet);
            if (!sheet.getTables().isEmpty()) {
                throw new BusinessException(422, "暂不支持在包含 Excel 表对象的工作表中排序或设置筛选");
            }
            switch (operation.getType()) {
                case "SORT_RANGE" -> sortRange(sheet, operation, maxSortableCells);
                case "SET_AUTO_FILTER" -> setAutoFilter(sheet, operation);
                case "CLEAR_AUTO_FILTER" -> clearAutoFilter(sheet, operation);
                default -> throw new BusinessException(400, "不支持的 XLSX 数据操作");
            }
        }
    }

    private static void sortRange(XSSFSheet sheet, EditXlsxDataDTO.Operation operation,
                                  int maxSortableCells) {
        if (operation.getRange() == null || operation.getKeyColumn() == null
                || operation.getDirection() == null || operation.getHasHeader() == null) {
            throw new BusinessException(400, "SORT_RANGE 必须提供 range、keyColumn、direction 和 hasHeader");
        }
        CellRangeAddress range = parseRange(operation.getRange());
        if (area(range) > maxSortableCells) {
            throw new BusinessException(413, "排序区域单元格数量超过处理限制");
        }
        int keyColumn;
        try {
            keyColumn = CellReference.convertColStringToIndex(
                    operation.getKeyColumn().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "排序列无效：" + operation.getKeyColumn());
        }
        if (keyColumn < range.getFirstColumn() || keyColumn > range.getLastColumn()) {
            throw new BusinessException(400, "排序列必须位于排序区域内");
        }
        requireNoMergedIntersection(sheet, range, "排序区域不能与合并区域相交：");
        int firstDataRow = range.getFirstRow() + (operation.getHasHeader() ? 1 : 0);
        if (firstDataRow > range.getLastRow()) {
            return;
        }

        List<SortRow> rows = new ArrayList<>();
        for (int rowIndex = firstDataRow; rowIndex <= range.getLastRow(); rowIndex++) {
            List<CellSnapshot> cells = new ArrayList<>();
            for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
                Row row = sheet.getRow(rowIndex);
                Cell cell = row == null ? null : row.getCell(column);
                cells.add(snapshotSortableCell(cell));
            }
            rows.add(new SortRow(rowIndex, cells,
                    cells.get(keyColumn - range.getFirstColumn())));
        }
        Comparator<SortRow> comparator = (left, right) -> compareSortValues(
                left.key(), right.key(), "DESC".equals(operation.getDirection()));
        comparator = comparator.thenComparingInt(SortRow::originalRow);
        rows.sort(comparator);

        for (int offset = 0; offset < rows.size(); offset++) {
            int targetRowIndex = firstDataRow + offset;
            SortRow source = rows.get(offset);
            for (int columnOffset = 0; columnOffset < source.cells().size(); columnOffset++) {
                applySnapshot(sheet, targetRowIndex, range.getFirstColumn() + columnOffset,
                        source.cells().get(columnOffset));
            }
        }
    }

    private static CellSnapshot snapshotSortableCell(Cell cell) {
        if (cell == null) {
            return CellSnapshot.missing();
        }
        if (cell.getCellType() == CellType.FORMULA || cell.isPartOfArrayFormulaGroup()) {
            throw new BusinessException(422, "排序区域不能包含公式：" + cell.getAddress().formatAsString());
        }
        if (cell.getCellComment() != null || cell.getHyperlink() != null) {
            throw new BusinessException(422,
                    "排序区域不能包含批注或超链接：" + cell.getAddress().formatAsString());
        }
        return switch (cell.getCellType()) {
            case STRING -> new CellSnapshot(true, CellType.STRING,
                    cell.getRichStringCellValue().getString(), 0, false, (byte) 0,
                    cell.getCellStyle());
            case NUMERIC -> new CellSnapshot(true, CellType.NUMERIC, null,
                    cell.getNumericCellValue(), false, (byte) 0, cell.getCellStyle());
            case BOOLEAN -> new CellSnapshot(true, CellType.BOOLEAN, null,
                    0, cell.getBooleanCellValue(), (byte) 0, cell.getCellStyle());
            case ERROR -> new CellSnapshot(true, CellType.ERROR, null,
                    0, false, cell.getErrorCellValue(), cell.getCellStyle());
            case BLANK, _NONE -> new CellSnapshot(true, CellType.BLANK, null,
                    0, false, (byte) 0, cell.getCellStyle());
            case FORMULA -> throw new BusinessException(422,
                    "排序区域不能包含公式：" + cell.getAddress().formatAsString());
        };
    }

    private static int compareSortValues(CellSnapshot left, CellSnapshot right, boolean descending) {
        boolean leftBlank = left.isBlank();
        boolean rightBlank = right.isBlank();
        if (leftBlank || rightBlank) {
            return leftBlank == rightBlank ? 0 : (leftBlank ? 1 : -1);
        }
        int category = Integer.compare(sortCategory(left.type()), sortCategory(right.type()));
        int result;
        if (category != 0) {
            result = category;
        } else {
            result = switch (left.type()) {
                case NUMERIC -> Double.compare(left.numericValue(), right.numericValue());
                case STRING -> left.stringValue().compareToIgnoreCase(right.stringValue());
                case BOOLEAN -> Boolean.compare(left.booleanValue(), right.booleanValue());
                case ERROR -> Byte.compare(left.errorValue(), right.errorValue());
                default -> 0;
            };
        }
        return descending ? -result : result;
    }

    private static int sortCategory(CellType type) {
        return switch (type) {
            case NUMERIC -> 0;
            case STRING -> 1;
            case BOOLEAN -> 2;
            case ERROR -> 3;
            default -> 4;
        };
    }

    private static void applySnapshot(XSSFSheet sheet, int rowIndex, int columnIndex,
                                      CellSnapshot snapshot) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(columnIndex);
        if (!snapshot.exists()) {
            if (cell != null) {
                row.removeCell(cell);
            }
            return;
        }
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        if (cell == null) {
            cell = row.createCell(columnIndex);
        }
        cell.setBlank();
        cell.setCellStyle(snapshot.style());
        switch (snapshot.type()) {
            case STRING -> cell.setCellValue(snapshot.stringValue());
            case NUMERIC -> cell.setCellValue(snapshot.numericValue());
            case BOOLEAN -> cell.setCellValue(snapshot.booleanValue());
            case ERROR -> cell.setCellErrorValue(snapshot.errorValue());
            case BLANK, _NONE -> cell.setBlank();
            case FORMULA -> throw new IllegalStateException("formula snapshot is not supported");
        }
    }

    private static void setAutoFilter(XSSFSheet sheet, EditXlsxDataDTO.Operation operation) {
        if (operation.getRange() == null || operation.getKeyColumn() != null
                || operation.getDirection() != null || operation.getHasHeader() != null) {
            throw new BusinessException(400, "SET_AUTO_FILTER 必须且只能提供 range");
        }
        sheet.setAutoFilter(parseRange(operation.getRange()));
    }

    private static void clearAutoFilter(XSSFSheet sheet, EditXlsxDataDTO.Operation operation) {
        if (operation.getRange() != null || operation.getKeyColumn() != null
                || operation.getDirection() != null || operation.getHasHeader() != null) {
            throw new BusinessException(400, "CLEAR_AUTO_FILTER 不适用其他数据字段");
        }
        if (sheet.getCTWorksheet().isSetAutoFilter()) {
            sheet.getCTWorksheet().unsetAutoFilter();
        }
    }

    private static void applyFormatOperations(XSSFWorkbook workbook,
                                              List<FormatXlsxCellsDTO.Operation> operations) {
        Map<FormatCacheKey, XSSFCellStyle> styles = new HashMap<>();
        for (FormatXlsxCellsDTO.Operation operation : operations) {
            if (operation == null || operation.getSheetName() == null
                    || operation.getRange() == null) {
                throw new BusinessException(400, "XLSX 格式操作字段不完整");
            }
            requireFormatFields(operation);
            XSSFSheet sheet = workbook.getSheet(operation.getSheetName());
            if (sheet == null) {
                throw new BusinessException(404, "工作表不存在：" + operation.getSheetName());
            }
            requireWritableSheet(sheet);
            CellRangeAddress range = parseArea(operation.getRange());
            requireFullyCoveredMergedRegions(sheet, range);
            FormatSpec spec = FormatSpec.from(operation);
            for (int rowIndex = range.getFirstRow(); rowIndex <= range.getLastRow(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    row = sheet.createRow(rowIndex);
                }
                for (int column = range.getFirstColumn(); column <= range.getLastColumn(); column++) {
                    Cell cell = row.getCell(column);
                    if (cell == null) {
                        cell = row.createCell(column, CellType.BLANK);
                    }
                    XSSFCellStyle baseStyle = (XSSFCellStyle) cell.getCellStyle();
                    FormatCacheKey key = new FormatCacheKey(baseStyle.getIndex(), spec);
                    XSSFCellStyle style = styles.computeIfAbsent(key,
                            ignored -> buildStyle(workbook, baseStyle, spec));
                    cell.setCellStyle(style);
                }
            }
        }
    }

    private static void requireFormatFields(FormatXlsxCellsDTO.Operation operation) {
        if (operation.getFontName() == null && operation.getFontSize() == null
                && operation.getBold() == null && operation.getItalic() == null
                && operation.getFontColor() == null && operation.getHorizontalAlignment() == null
                && operation.getVerticalAlignment() == null && operation.getWrapText() == null
                && operation.getFillColor() == null && operation.getNumberFormat() == null
                && operation.getBorderStyle() == null) {
            throw new BusinessException(400, "格式操作至少需要提供一个格式字段");
        }
    }

    private static XSSFCellStyle buildStyle(XSSFWorkbook workbook, XSSFCellStyle base,
                                            FormatSpec spec) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(base);
        if (spec.hasFontChange()) {
            XSSFFont source = workbook.getFontAt(base.getFontIndex());
            XSSFFont font = workbook.createFont();
            font.getCTFont().set(source.getCTFont());
            if (spec.fontName() != null) {
                font.setFontName(spec.fontName());
            }
            if (spec.fontSize() != null) {
                font.setFontHeight(spec.fontSize());
            }
            if (spec.bold() != null) {
                font.setBold(spec.bold());
            }
            if (spec.italic() != null) {
                font.setItalic(spec.italic());
            }
            if (spec.fontColor() != null) {
                font.setColor(toColor(spec.fontColor()));
            }
            style.setFont(font);
        }
        if (spec.horizontalAlignment() != null) {
            style.setAlignment(HorizontalAlignment.valueOf(spec.horizontalAlignment()));
        }
        if (spec.verticalAlignment() != null) {
            style.setVerticalAlignment(VerticalAlignment.valueOf(spec.verticalAlignment()));
        }
        if (spec.wrapText() != null) {
            style.setWrapText(spec.wrapText());
        }
        if (spec.fillColor() != null) {
            style.setFillForegroundColor(toColor(spec.fillColor()));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (spec.numberFormat() != null) {
            style.setDataFormat(workbook.createDataFormat().getFormat(spec.numberFormat()));
        }
        if (spec.borderStyle() != null) {
            BorderStyle border = BorderStyle.valueOf(spec.borderStyle());
            style.setBorderTop(border);
            style.setBorderRight(border);
            style.setBorderBottom(border);
            style.setBorderLeft(border);
        }
        return style;
    }

    private static XSSFColor toColor(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        byte[] rgb = new byte[] {
                (byte) Integer.parseInt(value.substring(0, 2), 16),
                (byte) Integer.parseInt(value.substring(2, 4), 16),
                (byte) Integer.parseInt(value.substring(4, 6), 16)
        };
        return new XSSFColor(rgb, new DefaultIndexedColorMap());
    }

    private static CellRangeAddress parseArea(String rawRange) {
        if (rawRange != null && !rawRange.contains(":")) {
            CellAddress address = parseAddress(rawRange);
            return new CellRangeAddress(address.getRow(), address.getRow(),
                    address.getColumn(), address.getColumn());
        }
        return parseRange(rawRange);
    }

    private static long area(CellRangeAddress range) {
        return (long) (range.getLastRow() - range.getFirstRow() + 1)
                * (range.getLastColumn() - range.getFirstColumn() + 1);
    }

    private static void requireNoMergedIntersection(XSSFSheet sheet, CellRangeAddress requested,
                                                    String message) {
        for (CellRangeAddress merged : sheet.getMergedRegions()) {
            if (intersects(requested, merged)) {
                throw new BusinessException(400, message + merged.formatAsString());
            }
        }
    }

    private static void requireFullyCoveredMergedRegions(XSSFSheet sheet,
                                                         CellRangeAddress requested) {
        for (CellRangeAddress merged : sheet.getMergedRegions()) {
            if (intersects(requested, merged) && !contains(requested, merged)) {
                throw new BusinessException(400,
                        "格式区域不能只覆盖合并区域的一部分：" + merged.formatAsString());
            }
        }
    }

    private static boolean intersects(CellRangeAddress first, CellRangeAddress second) {
        return first.getFirstRow() <= second.getLastRow()
                && first.getLastRow() >= second.getFirstRow()
                && first.getFirstColumn() <= second.getLastColumn()
                && first.getLastColumn() >= second.getFirstColumn();
    }

    private static boolean contains(CellRangeAddress outer, CellRangeAddress inner) {
        return outer.getFirstRow() <= inner.getFirstRow()
                && outer.getLastRow() >= inner.getLastRow()
                && outer.getFirstColumn() <= inner.getFirstColumn()
                && outer.getLastColumn() >= inner.getLastColumn();
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
        String formula = normalizeFormula(rawFormula, target);
        XSSFWorkbook workbook = (XSSFWorkbook) cell.getSheet().getWorkbook();
        Ptg[] tokens = parseFormula(workbook, workbook.getSheetIndex(cell.getSheet()),
                cell.getRowIndex(), formula);
        validateFormulaReferences(workbook, tokens);
        try {
            cell.setCellFormula(formula);
        } catch (FormulaParseException | IllegalArgumentException | IllegalStateException e) {
            throw new BusinessException(400, "公式语法无效：" + target);
        }
    }

    private static String normalizeFormula(String rawFormula, String target) {
        if (rawFormula == null) {
            throw new BusinessException(400, "公式不能为空：" + target);
        }
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
        requireSafeFormula(formula, target);
        return formula;
    }

    private static void requireSafeFormula(String formula, String target) {
        if (formula.contains("://") || formula.contains("|")
                || EXTERNAL_WORKBOOK_REFERENCE.matcher(formula).find()
                || UNSAFE_FORMULA_FUNCTION.matcher(formula).find()) {
            throw new BusinessException(400, "公式包含外部引用或高风险函数：" + target);
        }
    }

    private static Ptg[] parseFormula(XSSFWorkbook workbook, int sheetIndex, int rowIndex,
                                      String formula) {
        try {
            return FormulaParser.parse(formula, XSSFEvaluationWorkbook.create(workbook),
                    FormulaType.CELL, sheetIndex, rowIndex);
        } catch (FormulaParseException | IllegalArgumentException | IllegalStateException e) {
            throw new BusinessException(400, "公式语法无效或引用无法解析");
        }
    }

    private static void validateFormulaReferences(XSSFWorkbook workbook, Ptg[] tokens) {
        for (Ptg token : tokens) {
            if (token instanceof org.apache.poi.ss.formula.ptg.RefErrorPtg
                    || token instanceof org.apache.poi.ss.formula.ptg.AreaErrPtg
                    || token instanceof org.apache.poi.ss.formula.ptg.DeletedRef3DPtg
                    || token instanceof org.apache.poi.ss.formula.ptg.DeletedArea3DPtg
                    || token instanceof org.apache.poi.ss.formula.ptg.Deleted3DPxg
                    || (token instanceof org.apache.poi.ss.formula.ptg.ErrPtg error
                    && error.getErrorCode() == FormulaError.REF.getCode())) {
                throw new BusinessException(400, "公式包含无效或越界的引用");
            }
            if (token instanceof RefPtgBase reference) {
                validateReferenceBounds(reference.getRow(), reference.getColumn());
            }
            if (token instanceof AreaPtgBase reference) {
                validateReferenceBounds(reference.getFirstRow(), reference.getFirstColumn());
                validateReferenceBounds(reference.getLastRow(), reference.getLastColumn());
            }
            if (token instanceof Pxg reference) {
                if (reference.getExternalWorkbookNumber() >= 0) {
                    throw new BusinessException(400, "公式不允许外部工作簿引用");
                }
                if (reference.getSheetName() != null
                        && workbook.getSheet(reference.getSheetName()) == null) {
                    throw new BusinessException(400, "公式引用的工作表不存在：" + reference.getSheetName());
                }
                if (reference instanceof Pxg3D multiSheet && multiSheet.getLastSheetName() != null
                        && workbook.getSheet(multiSheet.getLastSheetName()) == null) {
                    throw new BusinessException(400, "公式引用的工作表不存在：" + multiSheet.getLastSheetName());
                }
            }
        }
    }

    private static void validateReferenceBounds(int row, int column) {
        if (row < 0 || row > SpreadsheetVersion.EXCEL2007.getLastRowIndex()
                || column < 0 || column > SpreadsheetVersion.EXCEL2007.getLastColumnIndex()) {
            throw new BusinessException(400, "公式引用超出 XLSX 范围");
        }
    }

    private VersionVO editAndSave(Long userId, Long fileId, Integer sourceVersion,
                                  String instruction, String summary,
                                  Consumer<XSSFWorkbook> editor) {
        return editAndSave(userId, fileId, sourceVersion, instruction, summary, ChangeType.MANUAL_EDIT, editor);
    }

    private VersionVO editAndSave(Long userId, Long fileId, Integer sourceVersion,
                                  String instruction, String summary, ChangeType changeType,
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
                    changeType, instruction, summary, sourceVersion);
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK && !fileStorage.delete(storageKey)) {
                            log.warn("外层事务回滚后无法清理 XLSX 产物, storageKey={}", storageKey);
                        }
                    }
                });
            }
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

    private record CellSnapshot(boolean exists, CellType type, String stringValue,
                                double numericValue, boolean booleanValue, byte errorValue,
                                CellStyle style) {
        private static CellSnapshot missing() {
            return new CellSnapshot(false, CellType.BLANK, null,
                    0, false, (byte) 0, null);
        }

        private boolean isBlank() {
            return !exists || type == CellType.BLANK || type == CellType._NONE;
        }
    }

    private record SortRow(int originalRow, List<CellSnapshot> cells, CellSnapshot key) {
    }

    private record FormatSpec(String fontName, Double fontSize, Boolean bold, Boolean italic,
                              String fontColor, String horizontalAlignment,
                              String verticalAlignment, Boolean wrapText, String fillColor,
                              String numberFormat, String borderStyle) {
        private static FormatSpec from(FormatXlsxCellsDTO.Operation operation) {
            return new FormatSpec(operation.getFontName(), operation.getFontSize(),
                    operation.getBold(), operation.getItalic(), normalizeColor(operation.getFontColor()),
                    operation.getHorizontalAlignment(), operation.getVerticalAlignment(),
                    operation.getWrapText(), normalizeColor(operation.getFillColor()),
                    operation.getNumberFormat(), operation.getBorderStyle());
        }

        private static String normalizeColor(String color) {
            if (color == null) {
                return null;
            }
            return (color.startsWith("#") ? color.substring(1) : color).toUpperCase(Locale.ROOT);
        }

        private boolean hasFontChange() {
            return fontName != null || fontSize != null || bold != null
                    || italic != null || fontColor != null;
        }
    }

    private record FormatCacheKey(short baseStyleIndex, FormatSpec spec) {
    }

    private record ColumnFormat(int width, boolean hidden, CellStyle style) {
    }
}
