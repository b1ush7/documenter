package com.documenter.service.impl;

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
        FileDownload version = versionService.downloadVersion(userId, fileId, sourceVersion);
        if (!isDocx(version)) {
            throw new BusinessException(400, "当前版本不是 DOCX 文档");
        }
        ensureDistinctBlockIds(request.getOperations());

        byte[] edited;
        try (InputStream input = version.resource().getInputStream();
             XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (ReplaceDocxTextDTO.Operation operation : request.getOperations()) {
                XWPFParagraph paragraph = requireParagraph(document, operation.getBlockId());
                replaceSimpleParagraph(paragraph, operation.getBlockId(), operation.getText());
            }
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
                    request.getInstruction(),
                    "替换 " + request.getOperations().size() + " 个段落的文本",
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

    /**
     * 替换简单段落并保留段落属性与首个文本运行的字符格式。
     * 图片或复杂运行会被拒绝，避免静默破坏未要求修改的内容。
     */
    private static void replaceSimpleParagraph(XWPFParagraph paragraph, String blockId, String text) {
        for (IRunElement runElement : paragraph.getIRuns()) {
            if (!(runElement instanceof XWPFRun run) || !run.getEmbeddedPictures().isEmpty()) {
                throw new BusinessException(422, "段落包含图片或复杂内容，暂不支持直接替换：" + blockId);
            }
        }

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
}
