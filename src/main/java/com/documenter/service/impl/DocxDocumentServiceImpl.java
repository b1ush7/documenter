package com.documenter.service.impl;

import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.DocxDocumentService;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public DocxDocumentServiceImpl(DocumentVersionService versionService) {
        this.versionService = versionService;
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
}
