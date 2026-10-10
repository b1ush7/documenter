package com.documenter.service.impl;

import com.documenter.dto.EditDocxParagraphsDTO;
import com.documenter.dto.EditDocxTableDTO;
import com.documenter.dto.FormatDocxParagraphsDTO;
import com.documenter.dto.ReplaceDocxTextDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.IRunElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.springframework.stereotype.Service;
import org.apache.xmlbeans.XmlCursor;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.math.BigInteger;

/** Apache POI 驱动的 DOCX 结构读取器。 */
@Slf4j
@Service
public class DocxDocumentServiceImpl implements DocxDocumentService {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^heading[ _-]?([1-9])$");

    private final DocumentVersionService versionService;
    private final FileStorage fileStorage;
    private final VersionWriter versionWriter;

    public DocxDocumentServiceImpl(DocumentVersionService versionService,
                                   FileStorage fileStorage,
                                   VersionWriter versionWriter) {
        this.versionService = versionService;
        this.fileStorage = fileStorage;
        this.versionWriter = versionWriter;
    }

    @Override
    public DocxStructureVO readStructure(Long userId, Long fileId, Integer versionNo) {
        FileDownload version = versionService.downloadVersion(userId, fileId, versionNo);
        if (!isDocx(version)) {
            throw new BusinessException(400, "当前版本不是 DOCX 文档");
        }

        try (InputStream input = version.resource().getInputStream();
             XWPFDocument document = new XWPFDocument(input)) {
            List<DocxStructureVO.Block> blocks = readBody(document.getBodyElements(), "body");
            return new DocxStructureVO(fileId, versionNo, blocks);
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.warn("读取 DOCX 结构失败, fileId={}, versionNo={}", fileId, versionNo, e);
            throw new BusinessException(422, "DOCX 文档无法解析或内容已损坏");
        }
    }

    @Override
    public VersionVO replaceParagraphText(Long userId, Long fileId, Integer sourceVersion,
                                          ReplaceDocxTextDTO request) {
        ensureDistinctBlockIds(request.getOperations());
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "替换 " + request.getOperations().size() + " 个段落的文本", document -> {
            for (ReplaceDocxTextDTO.Operation operation : request.getOperations()) {
                XWPFParagraph paragraph = requireParagraph(document, operation.getBlockId());
                replaceSimpleParagraph(paragraph, operation.getBlockId(), operation.getText());
            }
        });
    }

    @Override
    public VersionVO editParagraphs(Long userId, Long fileId, Integer sourceVersion,
                                    EditDocxParagraphsDTO request) {
        validateParagraphEdits(request.getOperations());
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "执行 " + request.getOperations().size() + " 个段落插入或删除操作", document -> {
            List<ResolvedParagraphEdit> edits = request.getOperations().stream()
                    .map(operation -> new ResolvedParagraphEdit(
                            operation, requireParagraph(document, operation.getBlockId())))
                    .toList();
            for (ResolvedParagraphEdit edit : edits) {
                switch (edit.operation().getType()) {
                    case "INSERT_BEFORE" -> insertParagraph(edit.paragraph(), edit.operation().getText(), false);
                    case "INSERT_AFTER" -> insertParagraph(edit.paragraph(), edit.operation().getText(), true);
                    case "DELETE" -> deleteParagraph(edit.paragraph(), edit.operation().getBlockId());
                    default -> throw new BusinessException(400, "不支持的段落操作");
                }
            }
        });
    }

    @Override
    public VersionVO editTable(Long userId, Long fileId, Integer sourceVersion,
                               EditDocxTableDTO request) {
        validateTableEdits(request.getOperations());
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "执行 " + request.getOperations().size() + " 个表格编辑操作", document -> {
            List<ResolvedTableEdit> edits = request.getOperations().stream()
                    .map(operation -> resolveTableEdit(document, operation))
                    .toList();
            for (ResolvedTableEdit edit : edits) {
                switch (edit.operation().getType()) {
                    case "SET_CELL_TEXT" -> replaceCellText(edit.cell(), edit.operation().getBlockId(),
                            edit.operation().getText());
                    case "INSERT_ROW_BEFORE" -> insertRow(edit.table(), edit.row(),
                            edit.operation().getValues(), false);
                    case "INSERT_ROW_AFTER" -> insertRow(edit.table(), edit.row(),
                            edit.operation().getValues(), true);
                    case "DELETE_ROW" -> deleteRow(edit.table(), edit.row(), edit.operation().getBlockId());
                    default -> throw new BusinessException(400, "不支持的表格操作");
                }
            }
        });
    }

    @Override
    public VersionVO formatParagraphs(Long userId, Long fileId, Integer sourceVersion,
                                      FormatDocxParagraphsDTO request) {
        validateParagraphFormats(request.getOperations());
        return editAndSave(userId, fileId, sourceVersion, request.getInstruction(),
                "设置 " + request.getOperations().size() + " 个段落的标题或列表格式", document -> {
            List<ResolvedParagraphFormat> formats = request.getOperations().stream()
                    .map(operation -> new ResolvedParagraphFormat(
                            operation, requireParagraph(document, operation.getBlockId())))
                    .toList();
            BigInteger bulletNumId = formats.stream().anyMatch(
                    item -> "SET_BULLET".equals(item.operation().getType()))
                    ? createNumbering(document, true) : null;
            BigInteger numberedNumId = formats.stream().anyMatch(
                    item -> "SET_NUMBERED".equals(item.operation().getType()))
                    ? createNumbering(document, false) : null;
            for (ResolvedParagraphFormat format : formats) {
                XWPFParagraph paragraph = format.paragraph();
                switch (format.operation().getType()) {
                    case "SET_HEADING" -> {
                        clearNumbering(paragraph);
                        paragraph.setStyle("Heading" + format.operation().getLevel());
                    }
                    case "SET_NORMAL" -> {
                        clearNumbering(paragraph);
                        paragraph.setStyle("Normal");
                    }
                    case "SET_BULLET" -> applyNumbering(paragraph, bulletNumId);
                    case "SET_NUMBERED" -> applyNumbering(paragraph, numberedNumId);
                    case "CLEAR_LIST" -> clearNumbering(paragraph);
                    default -> throw new BusinessException(400, "不支持的段落格式操作");
                }
            }
        });
    }

    private VersionVO editAndSave(Long userId, Long fileId, Integer sourceVersion,
                                  String instruction, String summary,
                                  Consumer<XWPFDocument> editor) {
        FileDownload version = versionService.downloadVersion(userId, fileId, sourceVersion);
        if (!isDocx(version)) {
            throw new BusinessException(400, "当前版本不是 DOCX 文档");
        }

        byte[] edited;
        try (InputStream input = version.resource().getInputStream();
             XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            editor.accept(document);
            document.write(output);
            edited = output.toByteArray();
        } catch (IOException e) {
            log.warn("编辑 DOCX 失败, fileId={}, sourceVersion={}", fileId, sourceVersion, e);
            throw new BusinessException(422, "DOCX 文档无法编辑或内容已损坏");
        }

        String storageKey = fileStorage.store(edited, "docx");
        try {
            DocumentVersion created = versionWriter.appendVersion(
                    fileId, userId,
                    new VersionWriter.VersionContent(storageKey, edited.length, Digests.sha256Hex(edited)),
                    ChangeType.MANUAL_EDIT,
                    instruction,
                    summary,
                    sourceVersion);
            return VersionVO.from(created);
        } catch (RuntimeException e) {
            if (!fileStorage.delete(storageKey)) {
                log.warn("新增版本失败且无法清理编辑产物, storageKey={}", storageKey);
            }
            throw e;
        }
    }

    private static boolean isDocx(FileDownload version) {
        if (DOCX_CONTENT_TYPE.equalsIgnoreCase(version.contentType())) {
            return true;
        }
        String extension = version.asset().getExtension();
        return extension != null && "docx".equals(extension.toLowerCase(Locale.ROOT));
    }

    private static List<DocxStructureVO.Block> readBody(List<IBodyElement> elements, String parentPath) {
        List<DocxStructureVO.Block> result = new ArrayList<>();
        int paragraphIndex = 0;
        int tableIndex = 0;
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                result.add(readParagraph(paragraph, parentPath + "/p" + paragraphIndex++));
            } else if (element instanceof XWPFTable table) {
                result.add(readTable(table, parentPath + "/t" + tableIndex++));
            }
        }
        return result;
    }

    private static DocxStructureVO.Block readParagraph(XWPFParagraph paragraph, String path) {
        Integer headingLevel = headingLevel(paragraph.getStyle());
        List<DocxStructureVO.Block> images = new ArrayList<>();
        int imageIndex = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                byte[] data = picture.getPictureData().getData();
                images.add(new DocxStructureVO.Block(
                        path + "/img" + imageIndex++, "IMAGE", null, null, null,
                        picture.getPictureData().getFileName(), picture.getPictureData().getPackagePart().getContentType(),
                        (long) data.length, List.of()));
            }
        }
        return new DocxStructureVO.Block(path, headingLevel == null ? "PARAGRAPH" : "HEADING",
                paragraph.getText(), paragraph.getStyle(), headingLevel,
                null, null, null, images);
    }

    private static DocxStructureVO.Block readTable(XWPFTable table, String path) {
        List<DocxStructureVO.Block> rows = new ArrayList<>();
        List<XWPFTableRow> sourceRows = table.getRows();
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            XWPFTableRow row = sourceRows.get(rowIndex);
            List<DocxStructureVO.Block> cells = new ArrayList<>();
            List<XWPFTableCell> sourceCells = row.getTableCells();
            for (int cellIndex = 0; cellIndex < sourceCells.size(); cellIndex++) {
                XWPFTableCell cell = sourceCells.get(cellIndex);
                String cellPath = path + "/r" + rowIndex + "/c" + cellIndex;
                cells.add(new DocxStructureVO.Block(cellPath, "CELL", cell.getText(), null, null,
                        null, null, null, readBody(cell.getBodyElements(), cellPath)));
            }
            rows.add(new DocxStructureVO.Block(path + "/r" + rowIndex, "ROW", null, null, null,
                    null, null, null, cells));
        }
        return new DocxStructureVO.Block(path, "TABLE", null, null, null,
                null, null, null, rows);
    }

    private static Integer headingLevel(String styleId) {
        if (styleId == null) {
            return null;
        }
        Matcher matcher = HEADING_STYLE.matcher(styleId.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static void ensureDistinctBlockIds(List<ReplaceDocxTextDTO.Operation> operations) {
        Set<String> blockIds = new HashSet<>();
        for (ReplaceDocxTextDTO.Operation operation : operations) {
            if (!blockIds.add(operation.getBlockId())) {
                throw new BusinessException(400, "同一段落不能在一次请求中重复替换：" + operation.getBlockId());
            }
        }
    }

    private static XWPFParagraph requireParagraph(XWPFDocument document, String blockId) {
        String[] segments = blockId.split("/");
        IBody body = document;
        int index = 1;
        try {
            while (index < segments.length) {
                String segment = segments[index];
                if (segment.startsWith("p") && index == segments.length - 1) {
                    return body.getParagraphs().get(parseIndex(segment));
                }
                if (!segment.startsWith("t") || index + 2 >= segments.length) {
                    break;
                }
                XWPFTable table = body.getTables().get(parseIndex(segment));
                String rowSegment = segments[index + 1];
                String cellSegment = segments[index + 2];
                if (!rowSegment.startsWith("r") || !cellSegment.startsWith("c")) {
                    break;
                }
                XWPFTableRow row = table.getRows().get(parseIndex(rowSegment));
                body = row.getTableCells().get(parseIndex(cellSegment));
                index += 3;
            }
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            // 统一在下方转换为面向接口调用方的错误，避免泄露内部集合信息
        }
        throw new BusinessException(400, "段落块不存在于源版本中：" + blockId);
    }

    private static XWPFTable requireTable(XWPFDocument document, String blockId) {
        String[] segments = blockId.split("/");
        IBody body = document;
        int index = 1;
        try {
            while (index < segments.length) {
                String tableSegment = segments[index];
                if (!tableSegment.startsWith("t")) {
                    break;
                }
                XWPFTable table = body.getTables().get(parseIndex(tableSegment));
                if (index == segments.length - 1) {
                    return table;
                }
                if (index + 2 >= segments.length) {
                    break;
                }
                String rowSegment = segments[index + 1];
                String cellSegment = segments[index + 2];
                if (!rowSegment.startsWith("r") || !cellSegment.startsWith("c")) {
                    break;
                }
                body = table.getRows().get(parseIndex(rowSegment))
                        .getTableCells().get(parseIndex(cellSegment));
                index += 3;
            }
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            // 统一转成块不存在错误
        }
        throw new BusinessException(400, "表格块不存在于源版本中：" + blockId);
    }

    private static XWPFTableRow requireRow(XWPFDocument document, String blockId) {
        int rowSeparator = blockId.lastIndexOf("/r");
        if (rowSeparator < 0) {
            throw new BusinessException(400, "行块 ID 格式错误：" + blockId);
        }
        XWPFTable table = requireTable(document, blockId.substring(0, rowSeparator));
        try {
            return table.getRows().get(Integer.parseInt(blockId.substring(rowSeparator + 2)));
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new BusinessException(400, "表格行不存在于源版本中：" + blockId);
        }
    }

    private static XWPFTableCell requireCell(XWPFDocument document, String blockId) {
        int cellSeparator = blockId.lastIndexOf("/c");
        if (cellSeparator < 0) {
            throw new BusinessException(400, "单元格块 ID 格式错误：" + blockId);
        }
        XWPFTableRow row = requireRow(document, blockId.substring(0, cellSeparator));
        try {
            return row.getTableCells().get(Integer.parseInt(blockId.substring(cellSeparator + 2)));
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new BusinessException(400, "单元格不存在于源版本中：" + blockId);
        }
    }

    private static int parseIndex(String segment) {
        return Integer.parseInt(segment.substring(1));
    }

    private static void validateParagraphEdits(List<EditDocxParagraphsDTO.Operation> operations) {
        Set<String> operationKeys = new HashSet<>();
        Set<String> insertedTargets = new HashSet<>();
        Set<String> deletedTargets = new HashSet<>();
        for (EditDocxParagraphsDTO.Operation operation : operations) {
            String operationKey = operation.getType() + ":" + operation.getBlockId();
            if (!operationKeys.add(operationKey)) {
                throw new BusinessException(400, "同一段落不能重复执行相同结构操作：" + operation.getBlockId());
            }
            boolean delete = "DELETE".equals(operation.getType());
            if (delete && operation.getText() != null) {
                throw new BusinessException(400, "DELETE 操作不能包含新文本");
            }
            if (!delete && operation.getText() == null) {
                throw new BusinessException(400, operation.getType() + " 操作必须包含新文本");
            }
            if (delete) {
                if (insertedTargets.contains(operation.getBlockId())) {
                    throw new BusinessException(400, "同一段落不能同时插入和删除：" + operation.getBlockId());
                }
                deletedTargets.add(operation.getBlockId());
            } else {
                if (deletedTargets.contains(operation.getBlockId())) {
                    throw new BusinessException(400, "同一段落不能同时插入和删除：" + operation.getBlockId());
                }
                insertedTargets.add(operation.getBlockId());
            }
        }
    }

    private static void validateTableEdits(List<EditDocxTableDTO.Operation> operations) {
        Pattern rowPattern = Pattern.compile("^body(?:/t\\d+/r\\d+/c\\d+)*/t\\d+/r\\d+$");
        Pattern cellPattern = Pattern.compile("^body(?:/t\\d+/r\\d+/c\\d+)*/t\\d+/r\\d+/c\\d+$");
        Set<String> operationKeys = new HashSet<>();
        Set<String> deletedRows = new HashSet<>();
        Set<String> insertedRows = new HashSet<>();
        for (EditDocxTableDTO.Operation operation : operations) {
            if (!operationKeys.add(operation.getType() + ":" + operation.getBlockId())) {
                throw new BusinessException(400, "同一表格块不能重复执行相同操作：" + operation.getBlockId());
            }
            boolean setCell = "SET_CELL_TEXT".equals(operation.getType());
            boolean insert = operation.getType().startsWith("INSERT_ROW_");
            boolean delete = "DELETE_ROW".equals(operation.getType());
            if (setCell) {
                if (!cellPattern.matcher(operation.getBlockId()).matches() || operation.getText() == null
                        || operation.getValues() != null) {
                    throw new BusinessException(400, "SET_CELL_TEXT 必须指向单元格且只包含 text");
                }
                for (String deletedRow : deletedRows) {
                    if (operation.getBlockId().startsWith(deletedRow + "/")) {
                        throw new BusinessException(400, "不能修改同一请求中将被删除的行");
                    }
                }
            } else {
                if (!rowPattern.matcher(operation.getBlockId()).matches() || operation.getText() != null) {
                    throw new BusinessException(400, "行操作必须指向表格行且不能包含 text");
                }
                if (insert && (operation.getValues() == null || operation.getValues().isEmpty())) {
                    throw new BusinessException(400, operation.getType() + " 必须包含 values");
                }
                if (delete && operation.getValues() != null) {
                    throw new BusinessException(400, "DELETE_ROW 不能包含 values");
                }
                if (delete) {
                    if (insertedRows.contains(operation.getBlockId())) {
                        throw new BusinessException(400, "同一行不能同时作为插入参照和删除目标");
                    }
                    deletedRows.add(operation.getBlockId());
                } else {
                    if (deletedRows.contains(operation.getBlockId())) {
                        throw new BusinessException(400, "同一行不能同时作为插入参照和删除目标");
                    }
                    insertedRows.add(operation.getBlockId());
                }
            }
        }
        for (EditDocxTableDTO.Operation operation : operations) {
            if ("SET_CELL_TEXT".equals(operation.getType())) {
                int cellSeparator = operation.getBlockId().lastIndexOf("/c");
                if (deletedRows.contains(operation.getBlockId().substring(0, cellSeparator))) {
                    throw new BusinessException(400, "不能修改同一请求中将被删除的行");
                }
            }
        }
    }

    private static void validateParagraphFormats(List<FormatDocxParagraphsDTO.Operation> operations) {
        Set<String> blockIds = new HashSet<>();
        for (FormatDocxParagraphsDTO.Operation operation : operations) {
            if (!blockIds.add(operation.getBlockId())) {
                throw new BusinessException(400, "同一段落一次只能设置一种格式：" + operation.getBlockId());
            }
            boolean heading = "SET_HEADING".equals(operation.getType());
            if (heading && operation.getLevel() == null) {
                throw new BusinessException(400, "SET_HEADING 必须提供标题级别");
            }
            if (!heading && operation.getLevel() != null) {
                throw new BusinessException(400, operation.getType() + " 不能包含标题级别");
            }
        }
    }

    private static BigInteger createNumbering(XWPFDocument document, boolean bullet) {
        XWPFNumbering numbering = document.getNumbering();
        if (numbering == null) {
            numbering = document.createNumbering();
        }
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum definition =
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum.Factory.newInstance();
        BigInteger nextAbstractNumId = BigInteger.ZERO;
        Set<BigInteger> existingIds = numbering.getAbstractNums().stream()
                .map(item -> item.getCTAbstractNum().getAbstractNumId())
                .collect(java.util.stream.Collectors.toSet());
        while (existingIds.contains(nextAbstractNumId)) {
            nextAbstractNumId = nextAbstractNumId.add(BigInteger.ONE);
        }
        definition.setAbstractNumId(nextAbstractNumId);
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl level = definition.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewStart().setVal(BigInteger.ONE);
        level.addNewNumFmt().setVal(bullet
                ? org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.BULLET
                : org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat.DECIMAL);
        level.addNewLvlText().setVal(bullet ? "•" : "%1.");
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd indent =
                level.addNewPPr().addNewInd();
        indent.setLeft(BigInteger.valueOf(720));
        indent.setHanging(BigInteger.valueOf(360));
        BigInteger abstractNumId = numbering.addAbstractNum(new XWPFAbstractNum(definition));
        return numbering.addNum(abstractNumId);
    }

    private static void applyNumbering(XWPFParagraph paragraph, BigInteger numId) {
        paragraph.setNumID(numId);
        paragraph.setNumILvl(BigInteger.ZERO);
    }

    private static void clearNumbering(XWPFParagraph paragraph) {
        if (paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetNumPr()) {
            paragraph.getCTP().getPPr().unsetNumPr();
        }
    }

    private static ResolvedTableEdit resolveTableEdit(XWPFDocument document,
                                                       EditDocxTableDTO.Operation operation) {
        if ("SET_CELL_TEXT".equals(operation.getType())) {
            return new ResolvedTableEdit(operation, null, null,
                    requireCell(document, operation.getBlockId()));
        }
        XWPFTableRow row = requireRow(document, operation.getBlockId());
        return new ResolvedTableEdit(operation, row.getTable(), row, null);
    }

    private static void replaceCellText(XWPFTableCell cell, String blockId, String text) {
        if (!cell.getTables().isEmpty()) {
            throw new BusinessException(422, "包含嵌套表格的单元格暂不支持整体替换：" + blockId);
        }
        List<XWPFParagraph> paragraphs = new ArrayList<>(cell.getParagraphs());
        if (paragraphs.isEmpty()) {
            throw new BusinessException(422, "单元格缺少必要段落：" + blockId);
        }
        for (XWPFParagraph paragraph : paragraphs) {
            assertSimpleParagraph(paragraph, blockId);
        }
        replaceSimpleParagraph(paragraphs.getFirst(), blockId, text);
        for (int index = cell.getParagraphs().size() - 1; index >= 1; index--) {
            cell.removeParagraph(index);
        }
    }

    private static void insertRow(XWPFTable table, XWPFTableRow reference,
                                  List<String> values, boolean after) {
        int referenceIndex = table.getRows().indexOf(reference);
        if (referenceIndex < 0) {
            throw new BusinessException(422, "参照行已经不存在");
        }
        if (values.size() != reference.getTableCells().size()) {
            throw new BusinessException(400, "插入行的单元格数量必须与参照行一致");
        }
        int insertIndex = after ? referenceIndex + 1 : referenceIndex;
        XWPFTableRow inserted = table.insertNewTableRow(insertIndex);
        if (reference.getCtRow().isSetTrPr()) {
            inserted.getCtRow().setTrPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr)
                    reference.getCtRow().getTrPr().copy());
        }
        for (int index = 0; index < values.size(); index++) {
            XWPFTableCell referenceCell = reference.getTableCells().get(index);
            XWPFTableCell insertedCell = inserted.addNewTableCell();
            if (referenceCell.getCTTc().isSetTcPr()) {
                insertedCell.getCTTc().setTcPr(
                        (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr)
                                referenceCell.getCTTc().getTcPr().copy());
            }
            XWPFParagraph insertedParagraph = insertedCell.getParagraphs().isEmpty()
                    ? insertedCell.addParagraph() : insertedCell.getParagraphs().getFirst();
            XWPFParagraph referenceParagraph = referenceCell.getParagraphs().isEmpty()
                    ? null : referenceCell.getParagraphs().getFirst();
            if (referenceParagraph != null) {
                copyParagraphFormatting(referenceParagraph, insertedParagraph);
            }
            XWPFRun run = insertedParagraph.createRun();
            if (referenceParagraph != null && !referenceParagraph.getRuns().isEmpty()
                    && referenceParagraph.getRuns().getFirst().getCTR().isSetRPr()) {
                run.getCTR().setRPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr)
                        referenceParagraph.getRuns().getFirst().getCTR().getRPr().copy());
            }
            run.setText(values.get(index));
        }
    }

    private static void deleteRow(XWPFTable table, XWPFTableRow row, String blockId) {
        if (table.getRows().size() <= 1) {
            throw new BusinessException(422, "表格必须保留至少一行：" + blockId);
        }
        int index = table.getRows().indexOf(row);
        if (index < 0 || !table.removeRow(index)) {
            throw new BusinessException(422, "无法删除表格行：" + blockId);
        }
    }

    private static void insertParagraph(XWPFParagraph reference, String text, boolean after) {
        IBody body = reference.getBody();
        XWPFParagraph inserted;
        try (XmlCursor cursor = reference.getCTP().newCursor()) {
            if (after && !cursor.toNextSibling()) {
                inserted = appendParagraph(body);
            } else {
                inserted = body.insertNewParagraph(cursor);
            }
        }
        if (inserted == null) {
            throw new BusinessException(422, "无法在目标位置插入段落");
        }
        copyParagraphFormatting(reference, inserted);
        XWPFRun run = inserted.createRun();
        if (!reference.getRuns().isEmpty() && reference.getRuns().getFirst().getCTR().isSetRPr()) {
            run.getCTR().setRPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr)
                    reference.getRuns().getFirst().getCTR().getRPr().copy());
        }
        run.setText(text);
    }

    private static XWPFParagraph appendParagraph(IBody body) {
        if (body instanceof XWPFDocument document) {
            return document.createParagraph();
        }
        if (body instanceof XWPFTableCell cell) {
            return cell.addParagraph();
        }
        throw new BusinessException(422, "当前文档区域不支持追加段落");
    }

    private static void copyParagraphFormatting(XWPFParagraph source, XWPFParagraph target) {
        if (source.getCTP().isSetPPr()) {
            target.getCTP().setPPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr)
                    source.getCTP().getPPr().copy());
        }
    }

    private static void deleteParagraph(XWPFParagraph paragraph, String blockId) {
        assertSimpleParagraph(paragraph, blockId);
        IBody body = paragraph.getBody();
        if (body instanceof XWPFDocument document) {
            int position = document.getPosOfParagraph(paragraph);
            if (position < 0 || !document.removeBodyElement(position)) {
                throw new BusinessException(422, "无法删除段落：" + blockId);
            }
            return;
        }
        if (body instanceof XWPFTableCell cell) {
            if (cell.getParagraphs().size() <= 1) {
                throw new BusinessException(422, "表格单元格必须保留至少一个段落：" + blockId);
            }
            int position = cell.getParagraphs().indexOf(paragraph);
            if (position < 0) {
                throw new BusinessException(422, "无法删除段落：" + blockId);
            }
            cell.removeParagraph(position);
            return;
        }
        throw new BusinessException(422, "当前文档区域不支持删除段落：" + blockId);
    }

    /**
     * 替换简单段落并保留段落属性与首个文本运行的字符格式。
     * 图片或复杂运行会被拒绝，避免静默破坏未要求修改的内容。
     */
    private static void replaceSimpleParagraph(XWPFParagraph paragraph, String blockId, String text) {
        assertSimpleParagraph(paragraph, blockId);

        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr runProperties = null;
        if (!paragraph.getRuns().isEmpty() && paragraph.getRuns().getFirst().getCTR().isSetRPr()) {
            runProperties = (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr)
                    paragraph.getRuns().getFirst().getCTR().getRPr().copy();
        }
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
            paragraph.removeRun(index);
        }
        XWPFRun replacement = paragraph.createRun();
        if (runProperties != null) {
            replacement.getCTR().setRPr(runProperties);
        }
        replacement.setText(text);
    }

    private static void assertSimpleParagraph(XWPFParagraph paragraph, String blockId) {
        for (IRunElement runElement : paragraph.getIRuns()) {
            if (!(runElement instanceof XWPFRun run) || !run.getEmbeddedPictures().isEmpty()) {
                throw new BusinessException(422, "段落包含图片或复杂内容，暂不支持直接替换：" + blockId);
            }
        }
    }

    private record ResolvedParagraphEdit(EditDocxParagraphsDTO.Operation operation,
                                         XWPFParagraph paragraph) {
    }

    private record ResolvedTableEdit(EditDocxTableDTO.Operation operation,
                                     XWPFTable table, XWPFTableRow row, XWPFTableCell cell) {
    }

    private record ResolvedParagraphFormat(FormatDocxParagraphsDTO.Operation operation,
                                           XWPFParagraph paragraph) {
    }
}
