package com.documenter.service.impl;

import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentComparisonService;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.XlsxDocumentService;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.VersionComparisonVO;
import com.documenter.vo.VersionComparisonVO.Change;
import com.documenter.vo.VersionComparisonVO.ChangeType;
import com.documenter.vo.XlsxStructureVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 文档版本比较实现（TODO 4.3「支持修改前后内容比较」）。
 *
 * <p>比较策略：把两个版本各自扁平化成「定位标识 → 内容」的有序映射，
 * 再按标识对齐。这样做的前提是<b>未被改动的块在两个版本里标识相同</b> ——
 * DOCX 用 OOXML 结构路径（{@code body/p2}），XLSX 用「工作表 + 单元格地址」。
 *
 * <p>为什么不做字符级 diff：本文档是结构化办公文档，用户关心的是
 * 「第二段被改了」而不是一串增删碎片。字符级 diff 会让表格与段落改动
 * 变得难以阅读。代价是段落内的小改动会显示为整段变化 —— 这对
 * 「看清楚哪一段动了」这个主要诉求是可以接受的取舍。
 *
 * <p>已知退化情形：若两个版本的块结构差异很大（例如整体重排），
 * 结构路径会大量错位，此时「修改」会被拆成「删除 + 新增」。
 * 结果本身仍正确（内容确实变了），只是分类不够精细。
 */
@Slf4j
@Service
public class DocumentComparisonServiceImpl implements DocumentComparisonService {

    private static final String DOCX_EXTENSION = "docx";
    private static final String XLSX_EXTENSION = "xlsx";

    /** 参与比较的 DOCX 块类型。容器块（TABLE/ROW）与图片本身没有可比文本。 */
    private static final String TYPE_PARAGRAPH = "PARAGRAPH";
    private static final String TYPE_HEADING = "HEADING";
    private static final String TYPE_CELL = "CELL";
    private static final String TYPE_IMAGE = "IMAGE";

    private final DocxDocumentService docxDocumentService;
    private final XlsxDocumentService xlsxDocumentService;

    public DocumentComparisonServiceImpl(DocxDocumentService docxDocumentService,
                                         XlsxDocumentService xlsxDocumentService) {
        this.docxDocumentService = docxDocumentService;
        this.xlsxDocumentService = xlsxDocumentService;
    }

    @Override
    public VersionComparisonVO compare(Long userId, Long fileId,
                                       Integer fromVersion, Integer toVersion) {
        if (fromVersion == null || toVersion == null) {
            throw new BusinessException(400, "必须同时指定要比较的两个版本号");
        }

        // 两个版本都经各自的读取路径解析归属；读取失败即版本不存在或不属于该用户
        DocxStructureVO fromDocx = tryReadDocx(userId, fileId, fromVersion);
        DocxStructureVO toDocx = tryReadDocx(userId, fileId, toVersion);
        if (fromDocx != null && toDocx != null) {
            return compareDocx(fileId, fromVersion, toVersion, fromDocx, toDocx);
        }

        XlsxStructureVO fromXlsx = tryReadXlsx(userId, fileId, fromVersion);
        XlsxStructureVO toXlsx = tryReadXlsx(userId, fileId, toVersion);
        if (fromXlsx != null && toXlsx != null) {
            return compareXlsx(fileId, fromVersion, toVersion, fromXlsx, toXlsx);
        }

        // 两个版本格式不同时无法逐块比较。这是真实存在的情形：
        // 同一文件的不同版本允许使用不同格式（如 DOCX 转出 PDF 后的版本）。
        throw new BusinessException(400,
                "两个版本的格式不同或不受支持，无法逐块比较；"
                        + "目前仅支持同格式的 DOCX 与 XLSX 版本之间的比较");
    }

    // ------------------------------------------------------------ DOCX

    private VersionComparisonVO compareDocx(Long fileId, Integer fromVersion, Integer toVersion,
                                            DocxStructureVO from, DocxStructureVO to) {
        Map<String, Node> before = new LinkedHashMap<>();
        Map<String, Node> after = new LinkedHashMap<>();
        flattenDocx(from.blocks(), before);
        flattenDocx(to.blocks(), after);

        List<Change> changes = diff(before, after);
        log.info("DOCX 版本比较完成, fileId={}, from={}, to={}, changes={}",
                fileId, fromVersion, toVersion, changes.size());
        return new VersionComparisonVO(fileId, fromVersion, toVersion,
                DOCX_EXTENSION.toUpperCase(Locale.ROOT), changes, false);
    }

    private void flattenDocx(List<DocxStructureVO.Block> blocks, Map<String, Node> target) {
        for (DocxStructureVO.Block block : blocks) {
            switch (block.type()) {
                case TYPE_PARAGRAPH, TYPE_HEADING ->
                        target.put(block.blockId(),
                                new Node(label(block), block.text(), block.type()));
                case TYPE_CELL ->
                        target.put(block.blockId(),
                                new Node(label(block), block.text(), TYPE_CELL));
                case TYPE_IMAGE ->
                        // 图片没有可比文本，用名称与大小描述「换了一张图」
                        target.put(block.blockId(), new Node(label(block),
                                describeImage(block), TYPE_IMAGE));
                default -> {
                    // TABLE / ROW 是容器块，本身无内容；其内容由子块体现
                }
            }
            if (!block.children().isEmpty()) {
                flattenDocx(block.children(), target);
            }
        }
    }

    private static String describeImage(DocxStructureVO.Block block) {
        StringBuilder builder = new StringBuilder();
        builder.append(block.name() == null ? "图片" : block.name());
        if (block.sizeBytes() != null) {
            builder.append(" (").append(block.sizeBytes()).append(" 字节)");
        }
        return builder.toString();
    }

    private static String label(DocxStructureVO.Block block) {
        String id = block.blockId() == null ? "" : block.blockId();
        return switch (block.type()) {
            case TYPE_HEADING -> "标题 " + id;
            case TYPE_PARAGRAPH -> "段落 " + id;
            case TYPE_CELL -> "表格单元格 " + id;
            case TYPE_IMAGE -> "图片 " + id;
            default -> id;
        };
    }

    // ------------------------------------------------------------ XLSX

    private VersionComparisonVO compareXlsx(Long fileId, Integer fromVersion, Integer toVersion,
                                            XlsxStructureVO from, XlsxStructureVO to) {
        Map<String, Node> before = new LinkedHashMap<>();
        Map<String, Node> after = new LinkedHashMap<>();
        boolean truncated = flattenXlsx(from, before) | flattenXlsx(to, after);

        List<Change> changes = diff(before, after);
        log.info("XLSX 版本比较完成, fileId={}, from={}, to={}, changes={}, truncated={}",
                fileId, fromVersion, toVersion, changes.size(), truncated);
        return new VersionComparisonVO(fileId, fromVersion, toVersion,
                XLSX_EXTENSION.toUpperCase(Locale.ROOT), changes, truncated);
    }

    /**
     * 扁平化 XLSX 结构。
     *
     * @return 源结构是否被截断（读取单元格有上限，超大表只返回部分）
     */
    private boolean flattenXlsx(XlsxStructureVO structure, Map<String, Node> target) {
        for (XlsxStructureVO.Sheet sheet : structure.sheets()) {
            for (XlsxStructureVO.Cell cell : sheet.cells()) {
                // 用「工作表名 + 单元格地址」作标识：工作表内地址唯一，
                // 且与结构读取的 A1 地址语义一致
                String key = sheet.name() + "!" + cell.address();
                String content = describeCell(cell);
                if (content == null) {
                    // 空单元格不参与比较，否则「清空一个单元格」与「本来就没有」
                    // 会产生难以理解的差异噪音
                    continue;
                }
                target.put(key, new Node("工作表 " + sheet.name() + " 单元格 " + cell.address(),
                        content, TYPE_CELL));
            }
        }
        return structure.truncated();
    }

    /** 单元格的可比内容：公式优先展示（公式变了比缓存值变了更有意义）。 */
    private static String describeCell(XlsxStructureVO.Cell cell) {
        String formula = cell.formula();
        if (formula != null && !formula.isBlank()) {
            return "=" + formula;
        }
        String value = cell.value();
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------------------------ 通用对齐

    private static List<Change> diff(Map<String, Node> before, Map<String, Node> after) {
        List<Change> changes = new ArrayList<>();

        // 按新版本的顺序遍历，保证输出顺序与用户阅读文档的顺序一致
        for (Map.Entry<String, Node> entry : after.entrySet()) {
            String key = entry.getKey();
            Node now = entry.getValue();
            Node previous = before.get(key);
            if (previous == null) {
                changes.add(new Change(ChangeType.ADDED, key, now.label(),
                        null, now.content(), now.kind()));
            } else if (!Objects.equals(previous.content(), now.content())) {
                changes.add(new Change(ChangeType.MODIFIED, key, now.label(),
                        previous.content(), now.content(), now.kind()));
            }
        }

        // 旧版本里有、新版本里没有的即为删除
        for (Map.Entry<String, Node> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                Node gone = entry.getValue();
                changes.add(new Change(ChangeType.REMOVED, entry.getKey(), gone.label(),
                        gone.content(), null, gone.kind()));
            }
        }

        return changes;
    }

    // ------------------------------------------------------------ 读取辅助

    /** DOCX 结构读取；非 DOCX 或不可读时返回 null，交给下一种格式尝试。 */
    private DocxStructureVO tryReadDocx(Long userId, Long fileId, Integer versionNo) {
        try {
            return docxDocumentService.readStructure(userId, fileId, versionNo);
        } catch (BusinessException e) {
            // 只吞「不是 DOCX」这一类；版本不存在或无权访问必须向上抛，
            // 否则会被换成一句笼统的「格式不支持」，掩盖真实原因
            if (isNotFound(e)) {
                throw e;
            }
            return null;
        }
    }

    private XlsxStructureVO tryReadXlsx(Long userId, Long fileId, Integer versionNo) {
        try {
            return xlsxDocumentService.readStructure(userId, fileId, versionNo);
        } catch (BusinessException e) {
            if (isNotFound(e)) {
                throw e;
            }
            return null;
        }
    }

    /**
     * 判断异常是否属于「资源不存在 / 无权访问」。
     *
     * <p>这类异常必须原样抛出：预览与下载对他人资源统一返回 404，
     * 比较也必须保持同样的语义，否则可以用来探测他人版本是否存在。
     */
    private static boolean isNotFound(BusinessException e) {
        return e.getCode() == 404 || e.getCode() == 403;
    }

    /** 参与比较的节点。 */
    private record Node(String label, String content, String kind) {
    }
}
