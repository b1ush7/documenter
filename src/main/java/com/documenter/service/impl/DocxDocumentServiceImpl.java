package com.documenter.service.impl;

import com.documenter.dto.EditDocxParagraphsDTO;
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
}
