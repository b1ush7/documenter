package com.documenter.util;

import com.documenter.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Changes only formatting and drawing extents; source document parts remain intact. */
public final class DocxNormalizer {
    private static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Pattern HEADING = Pattern.compile("(?i)^(?:heading|标题)[ _-]?([1-9])$");
    private static final long EMU_PER_CM = 360000;
    private static final long EMU_PER_TWIP = 635;
    private DocxNormalizer() { }

    private record Bounds(long width, long height) { }
    private record Paragraph(XWPFParagraph value, Bounds bounds, boolean inTable) { }
    private record Picture(Element extent, Element innerExtent, XWPFParagraph paragraph,
                           long width, long height, long sourceWidth, long sourceHeight,
                           Bounds bounds, boolean floating) { }

    public static Runnable prepareFormat(XWPFDocument document, DocxNormalizationSpec.Format spec) {
        var selected = paragraphs(document).stream()
                .filter(p -> spec.matches(headingLevel(document, p.value()), p.inTable())).toList();
        if (selected.isEmpty()) fail("当前源版本没有匹配格式范围的段落");
        if (selected.size() > DocxNormalizationSpec.MAX_PARAGRAPHS) fail("批量段落数量超过上限");
        return () -> selected.forEach(item -> {
            var p = item.value();
            if (spec.alignment() != null) p.setAlignment(alignment(spec.alignment()));
            if (spec.lineSpacing() != null) p.setSpacingBetween(spec.lineSpacing(), LineSpacingRule.AUTO);
            if (spec.spaceBeforePoints() != null) {
                p.setSpacingBefore((int) Math.round(spec.spaceBeforePoints() * 20));
                var spacing = p.getCTP().getPPr().getSpacing();
                if (spacing.isSetBeforeAutospacing()) spacing.unsetBeforeAutospacing();
                if (spacing.isSetBeforeLines()) spacing.unsetBeforeLines();
            }
            if (spec.spaceAfterPoints() != null) {
                p.setSpacingAfter((int) Math.round(spec.spaceAfterPoints() * 20));
                var spacing = p.getCTP().getPPr().getSpacing();
                if (spacing.isSetAfterAutospacing()) spacing.unsetAfterAutospacing();
                if (spacing.isSetAfterLines()) spacing.unsetAfterLines();
            }
            for (var run : p.getRuns()) {
                if (spec.fontFamily() != null) {
                    for (var range : XWPFRun.FontCharRange.values()) run.setFontFamily(spec.fontFamily(), range);
                    for (var fonts : descendants(run.getCTR().getDomNode(), W, "rFonts")) {
                        for (String theme : List.of("asciiTheme", "hAnsiTheme", "eastAsiaTheme", "cstheme", "csTheme")) fonts.removeAttributeNS(W, theme);
                    }
                }
                if (spec.fontSizePoints() != null) {
                    run.setFontSize(spec.fontSizePoints());
                    runProperty(run, "szCs", String.valueOf(Math.round(spec.fontSizePoints() * 2)));
                }
                if (spec.bold() != null) { run.setBold(spec.bold()); runProperty(run, "bCs", spec.bold() ? "1" : "0"); }
                if (spec.italic() != null) { run.setItalic(spec.italic()); runProperty(run, "iCs", spec.italic() ? "1" : "0"); }
            }
        });
    }

    public static Runnable prepareImages(XWPFDocument document, DocxNormalizationSpec.Images spec) {
        List<Picture> pictures = new ArrayList<>();
        for (var item : paragraphs(document)) {
            for (var run : item.value().getRuns()) {
                for (var drawing : run.getCTR().getDrawingList()) {
                    for (var inline : drawing.getInlineList()) addPicture(pictures, inline.getDomNode(), item, false);
                    for (var anchor : drawing.getAnchorList()) addPicture(pictures, anchor.getDomNode(), item, true);
                }
            }
        }
        if (pictures.isEmpty()) fail("正文没有受支持的已有图片；暂不处理 VML 或组合形状");
        if (pictures.size() > DocxNormalizationSpec.MAX_IMAGES) fail("批量图片数量超过上限");
        for (var pic : pictures) {
            if (spec.alignment() != null && (pic.floating() || !pic.paragraph().getText().isBlank())) {
                fail("单独图片对齐仅支持独立成段的内嵌图片；含正文或浮动图片时请省略 alignment");
            }
        }
        return () -> pictures.forEach(pic -> {
            double width = spec.widthCm() == null ? Double.POSITIVE_INFINITY : spec.widthCm() * EMU_PER_CM;
            double height = spec.heightCm() == null ? Double.POSITIVE_INFINITY : spec.heightCm() * EMU_PER_CM;
            width = Math.min(width, pic.bounds().width());
            height = Math.min(height, pic.bounds().height());
            long w, h;
            if (spec.keepAspectRatio()) {
                double scale = Math.min(width / pic.sourceWidth(), height / pic.sourceHeight());
                w = Math.max(1, Math.round(pic.sourceWidth() * scale));
                h = Math.max(1, Math.round(pic.sourceHeight() * scale));
            } else { w = Math.max(1, Math.round(width)); h = Math.max(1, Math.round(height)); }
            setExtent(pic.extent(), w, h);
            setExtent(pic.innerExtent(), w, h);
            if (spec.alignment() != null) pic.paragraph().setAlignment(alignment(spec.alignment()));
        });
    }

    private static void addPicture(List<Picture> target, Node drawing, Paragraph item, boolean floating) {
        List<Element> pics = descendants(drawing, PIC, "pic");
        if (pics.isEmpty()) return; // Charts and non-picture drawings are preserved.
        if (pics.size() != 1) fail("组合图片暂不支持批量尺寸调整");
        var pic = pics.getFirst();
        Element extent = child(drawing, WP, "extent");
        Element spPr = child(pic, PIC, "spPr");
        Element transform = spPr == null ? null : child(spPr, A, "xfrm");
        Element inner = transform == null ? null : child(transform, A, "ext");
        if (extent == null || inner == null) fail("图片缺少可安全修改的显示尺寸");
        long width = positive(extent.getAttribute("cx")), height = positive(extent.getAttribute("cy"));
        long sw = width, sh = height;
        // Keep existing display ratio when cropping/rotation is present, or the raster format is unsupported.
        boolean cropped = !descendants(pic, A, "srcRect").isEmpty();
        boolean rotated = transform.hasAttribute("rot") && !"0".equals(transform.getAttribute("rot"));
        if (!cropped && !rotated) {
            var blips = descendants(pic, A, "blip");
            String embed = blips.isEmpty() ? "" : blips.getFirst().getAttributeNS(R, "embed");
            if (!embed.isBlank() && item.value().getBody().getPart().getRelationById(embed) instanceof XWPFPictureData data) {
                try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data.getData()))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (readers.hasNext()) {
                        var reader = readers.next();
                        try { reader.setInput(input); sw = reader.getWidth(0); sh = reader.getHeight(0); }
                        finally { reader.dispose(); }
                    }
                } catch (Exception ignored) { sw = width; sh = height; }
            }
        }
        if (sw <= 0 || sh <= 0) fail("图片宽高不合法");
        Bounds bounds = item.bounds();
        if (floating) {
            // Account for positive offsets relative to the column/paragraph without moving the anchor.
            var horizontal = child(drawing, WP, "positionH");
            if (horizontal != null && "column".equals(horizontal.getAttribute("relativeFrom"))) {
                var offset = child(horizontal, WP, "posOffset");
                if (offset != null) {
                    try { bounds = new Bounds(Math.max(1, bounds.width() - Math.max(0, Long.parseLong(offset.getTextContent()))), bounds.height()); }
                    catch (NumberFormatException invalid) { fail("浮动图片定位不合法"); }
                }
            }
        }
        target.add(new Picture(extent, inner, item.value(), width, height, sw, sh, bounds, floating));
    }

    private static List<Paragraph> paragraphs(XWPFDocument document) {
        var elements = document.getBodyElements();
        Bounds[] perElement = new Bounds[elements.size()];
        Bounds bounds = bounds(document.getDocument().getBody().getSectPr());
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i) instanceof XWPFParagraph p && p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr()) {
                bounds = bounds(p.getCTP().getPPr().getSectPr());
            }
            perElement[i] = bounds;
        }
        List<Paragraph> result = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) collect(elements.get(i), perElement[i], false, result);
        return result;
    }

    private static void collect(IBodyElement element, Bounds bounds, boolean inTable, List<Paragraph> out) {
        if (element instanceof XWPFParagraph p) out.add(new Paragraph(p, bounds, inTable));
        else if (element instanceof XWPFTable table) {
            for (var row : table.getRows()) for (var cell : row.getTableCells()) {
                Bounds inside = bounds;
                var props = cell.getCTTc().getTcPr();
                if (props != null && props.isSetTcW() && props.getTcW().getW() != null
                        && "dxa".equals(String.valueOf(props.getTcW().getType()))) {
                    try {
                        long width = Long.parseLong(props.getTcW().getW().toString()) * EMU_PER_TWIP;
                        if (width > 0) inside = new Bounds(Math.min(width, bounds.width()), bounds.height());
                    } catch (NumberFormatException invalid) { fail("表格单元格宽度不合法"); }
                }
                for (var child : cell.getBodyElements()) collect(child, inside, true, out);
            }
        }
    }

    private static Bounds bounds(CTSectPr section) {
        long width = 11906, height = 16838, left = 1440, right = 1440, top = 1440, bottom = 1440;
        if (section != null) {
            if (section.isSetPgSz()) {
                if (section.getPgSz().getW() != null) width = Long.parseLong(section.getPgSz().getW().toString());
                if (section.getPgSz().getH() != null) height = Long.parseLong(section.getPgSz().getH().toString());
            }
            if (section.isSetPgMar()) {
                var m = section.getPgMar();
                if (m.getLeft() != null) left = Long.parseLong(m.getLeft().toString());
                if (m.getRight() != null) right = Long.parseLong(m.getRight().toString());
                if (m.getTop() != null) top = Long.parseLong(m.getTop().toString());
                if (m.getBottom() != null) bottom = Long.parseLong(m.getBottom().toString());
                if (m.getGutter() != null) left += Long.parseLong(m.getGutter().toString());
            }
        }
        if (width <= left + right || height <= top + bottom) { fail("页面版心尺寸无效"); }
        return new Bounds((width - left - right) * EMU_PER_TWIP, (height - top - bottom) * EMU_PER_TWIP);
    }

    public static Integer headingLevel(XWPFDocument doc, XWPFParagraph p) {
        if (p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetOutlineLvl()) {
            int value = p.getCTP().getPPr().getOutlineLvl().getVal().intValue();
            return value >= 0 && value < 9 ? value + 1 : null;
        }
        String id = p.getStyle();
        Set<String> visited = new HashSet<>();
        while (id != null && visited.add(id)) {
            var match = HEADING.matcher(id);
            if (match.matches()) return Integer.parseInt(match.group(1));
            var style = doc.getStyles() == null ? null : doc.getStyles().getStyle(id);
            if (style == null) break;
            if (style.getCTStyle().isSetPPr() && style.getCTStyle().getPPr().isSetOutlineLvl()) {
                int value = style.getCTStyle().getPPr().getOutlineLvl().getVal().intValue();
                return value >= 0 && value < 9 ? value + 1 : null;
            }
            match = HEADING.matcher(style.getName() == null ? "" : style.getName());
            if (match.matches()) return Integer.parseInt(match.group(1));
            id = style.getBasisStyleID();
        }
        return null;
    }

    private static ParagraphAlignment alignment(String value) { return "JUSTIFY".equals(value) ? ParagraphAlignment.BOTH : ParagraphAlignment.valueOf(value); }
    private static void runProperty(XWPFRun run, String local, String value) {
        var props = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        var node = props.getDomNode();
        var property = child(node, W, local);
        if (property == null) {
            property = node.getOwnerDocument().createElementNS(W, "w:" + local);
            node.appendChild(property);
        }
        property.setAttributeNS(W, "w:val", value);
    }
    private static long positive(String value) {
        try { long n = Long.parseLong(value); if (n > 0 && n <= 1_000_000_000L) return n; }
        catch (NumberFormatException ignored) { }
        fail("图片原始显示尺寸不合法"); return 0;
    }
    private static void setExtent(Element extent, long width, long height) { extent.setAttribute("cx", String.valueOf(width)); extent.setAttribute("cy", String.valueOf(height)); }
    private static Element child(Node node, String namespace, String local) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && namespace.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        return null;
    }
    private static List<Element> descendants(Node node, String namespace, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && namespace.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) out.add(e);
            out.addAll(descendants(n, namespace, local));
        }
        return out;
    }
    private static void fail(String message) { throw new BusinessException(422, message); }
}
