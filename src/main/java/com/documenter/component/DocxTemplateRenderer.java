package com.documenter.component;

import com.documenter.exception.BusinessException;
import com.documenter.util.TemplateVariables;
import org.apache.poi.ooxml.util.DocumentHelper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Literal DOCX text replacement; never evaluates expressions, HTML, OOXML, URLs or scripts. */
@Component
public class DocxTemplateRenderer {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");
    private static final int MAX_VALUE_CHARS = 10_000;
    private static final int MAX_REPLACEMENTS = 10_000;
    private static final int MAX_RENDERED_CHARS = 2_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    public byte[] render(byte[] source, String definitions, Map<String, Object> supplied) {
        Map<String, String> values = resolveValues(definitions, supplied);
        try (var document = new XWPFDocument(new ByteArrayInputStream(source));
             var output = new ByteArrayOutputStream()) {
            List<XWPFParagraph> paragraphs = new ArrayList<>();
            collect(document.getBodyElements(), paragraphs);
            Set<Node> supported = new HashSet<>();
            paragraphs.forEach(paragraph -> supported.add(paragraph.getCTP().getDomNode()));
            rejectUnsupportedParagraphs(document.getDocument().getBody().getDomNode(), supported);
            // Header/footer/notes/comments and other package parts are preserved, but cannot contain variables.
            for (var part : document.getPackage().getParts()) {
                if (part.getPartName().equals(document.getPackagePart().getPartName())) continue;
                if (part.isRelationshipPart() || !part.getPartName().getName().startsWith("/word/")) continue;
                if (!part.getContentType().endsWith("xml")) continue;
                try (var input = part.getInputStream()) {
                    if (hasDelimiters(wordText(DocumentHelper.readDocument(input)))) {
                        throw unsupported("占位符仅支持正文和表格，不能出现在页眉页脚、批注等其他部分");
                    }
                }
            }
            var budget = new Budget();
            for (var paragraph : paragraphs) replace(paragraph, values, budget);
            document.write(output);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            BusinessException failure = unsupported("模板 DOCX 无法解析或内容已损坏");
            failure.initCause(e);
            throw failure;
        }
    }

    private static Map<String, String> resolveValues(String definitions, Map<String, Object> supplied) {
        String normalized;
        try {
            normalized = TemplateVariables.validateAndNormalize(definitions);
        } catch (BusinessException e) {
            throw unsupported("模板变量定义无效，请联系管理员修正");
        }
        Map<String, Boolean> declared = new LinkedHashMap<>();
        if (normalized != null) {
            for (var item : JSON.readTree(normalized)) {
                declared.put(item.get("name").asText(), item.has("required") && item.get("required").asBoolean());
            }
        }
        Map<String, Object> input = supplied == null ? Map.of() : supplied;
        if (input.size() > 100) throw new BusinessException(400, "单次最多填写 100 个模板变量");
        for (String name : input.keySet()) {
            if (!declared.containsKey(name)) throw new BusinessException(400, "包含未定义的模板变量：" + name);
        }
        Map<String, String> values = new LinkedHashMap<>();
        declared.forEach((name, required) -> {
            Object value = input.get(name);
            if (value != null && !(value instanceof String)) {
                throw new BusinessException(400, "模板变量必须是纯文本字符串：" + name);
            }
            String text = value == null ? "" : (String) value;
            if (required && text.isBlank()) throw new BusinessException(400, "缺少必填模板变量：" + name);
            if (text.length() > MAX_VALUE_CHARS) throw new BusinessException(400, "单个模板变量最多 10000 字符：" + name);
            if (!text.codePoints().allMatch(DocxTemplateRenderer::validXmlCharacter)) {
                throw new BusinessException(400, "模板变量包含不支持的控制字符：" + name);
            }
            values.put(name, text.replace("\r\n", "\n").replace('\r', '\n'));
        });
        return values;
    }

    private static boolean validXmlCharacter(int value) {
        return value == 9 || value == 10 || value == 13 || value >= 0x20 && value <= 0xD7FF
                || value >= 0xE000 && value <= 0xFFFD || value >= 0x10000 && value <= 0x10FFFF;
    }

    private static void collect(List<IBodyElement> elements, List<XWPFParagraph> paragraphs) {
        for (var element : elements) {
            if (element instanceof XWPFParagraph paragraph) paragraphs.add(paragraph);
            else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) for (var cell : row.getTableCells()) collect(cell.getBodyElements(), paragraphs);
            }
        }
    }

    private static void rejectUnsupportedParagraphs(Node node, Set<Node> supported) {
        if (WORD.equals(node.getNamespaceURI()) && "p".equals(node.getLocalName())
                && !supported.contains(node) && hasDelimiters(wordText(node))) {
            throw unsupported("占位符不能出现在文本框、内容控件或其他不支持的结构中");
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            rejectUnsupportedParagraphs(child, supported);
        }
    }

    private static String wordText(Node node) {
        StringBuilder result = new StringBuilder();
        appendWordText(node, result);
        return result.toString();
    }

    private static void appendWordText(Node node, StringBuilder result) {
        if (node.getNodeType() == Node.ELEMENT_NODE && ("t".equals(node.getLocalName())
                && (WORD.equals(node.getNamespaceURI()) || DRAWING.equals(node.getNamespaceURI()))
                || WORD.equals(node.getNamespaceURI()) && "instrText".equals(node.getLocalName()))) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.TEXT_NODE) result.append(child.getNodeValue());
            }
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendWordText(child, result);
    }

    private static void replace(XWPFParagraph paragraph, Map<String, String> values, Budget budget) {
        String text = wordText(paragraph.getCTP().getDomNode());
        budget.characters += text.length();
        budget.check();
        if (!hasDelimiters(text)) return;
        if (text.contains("{{{") || text.contains("}}}")) throw unsupported("模板占位符格式不合法");
        // Ordinary text runs only. Fields, hyperlinks, drawings, breaks and tabs are not editable token containers.
        for (Node child = paragraph.getCTP().getDomNode().getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (!WORD.equals(child.getNamespaceURI())) throw unsupported("占位符段落包含不支持的结构");
            if (Set.of("pPr", "bookmarkStart", "bookmarkEnd", "proofErr").contains(child.getLocalName())) continue;
            if (!"r".equals(child.getLocalName())) throw unsupported("占位符不能包含超链接、域或内容控件");
            for (Node runChild = child.getFirstChild(); runChild != null; runChild = runChild.getNextSibling()) {
                if (runChild.getNodeType() == Node.ELEMENT_NODE
                        && (!WORD.equals(runChild.getNamespaceURI()) || !Set.of("rPr", "t").contains(runChild.getLocalName()))) {
                    throw unsupported("含占位符的段落仅支持普通文字，不能混用图片、域或换行结构");
                }
            }
        }
        List<Part> parts = new ArrayList<>();
        int offset = 0;
        for (var run : paragraph.getRuns()) for (var item : run.getCTR().getTList()) {
            int end = offset + item.getStringValue().length();
            parts.add(new Part(item, offset, end));
            offset = end;
        }
        List<Replacement> replacements = new ArrayList<>();
        var matcher = TOKEN.matcher(text);
        int previous = 0;
        while (matcher.find()) {
            if (hasDelimiters(text.substring(previous, matcher.start()))) throw unsupported("模板占位符格式不合法");
            String name = matcher.group(1);
            if (!values.containsKey(name)) throw unsupported("模板包含未定义的占位符：" + name);
            String value = values.get(name);
            replacements.add(new Replacement(matcher.start(), matcher.end(), value));
            budget.replacements++;
            budget.characters += value.length() - (matcher.end() - matcher.start());
            budget.check();
            previous = matcher.end();
        }
        if (hasDelimiters(text.substring(previous))) throw unsupported("模板占位符格式不合法");
        // Rebuild each text node once, avoiding repeated string copying for large batches of placeholders.
        Map<Integer, List<LocalEdit>> edits = new LinkedHashMap<>();
        for (Replacement replacement : replacements) {
            int firstIndex = partIndex(parts, replacement.start, false);
            int lastIndex = partIndex(parts, replacement.end, true);
            Part first = parts.get(firstIndex);
            Part last = parts.get(lastIndex);
            int start = replacement.start - first.start;
            int end = replacement.end - last.start;
            if (firstIndex == lastIndex) addEdit(edits, firstIndex, start, end, replacement.value);
            else {
                addEdit(edits, firstIndex, start, first.end - first.start, replacement.value);
                for (int part = firstIndex + 1; part < lastIndex; part++) {
                    addEdit(edits, part, 0, parts.get(part).end - parts.get(part).start, "");
                }
                addEdit(edits, lastIndex, 0, end, "");
            }
        }
        edits.forEach((index, changes) -> {
            CTText node = parts.get(index).text;
            String original = node.getStringValue();
            StringBuilder rendered = new StringBuilder();
            int cursor = 0;
            for (LocalEdit change : changes) {
                rendered.append(original, cursor, change.start).append(change.value);
                cursor = change.end;
            }
            rendered.append(original, cursor, original.length());
            setText(node, rendered.toString());
        });
        for (var run : paragraph.getRuns()) expandLineBreaks(run);
    }

    private static void setText(CTText text, String value) {
        text.setStringValue(value);
        text.setSpace(SpaceAttribute.Space.PRESERVE);
    }

    private static void addEdit(Map<Integer, List<LocalEdit>> edits, int index, int start, int end, String value) {
        edits.computeIfAbsent(index, ignored -> new ArrayList<>()).add(new LocalEdit(start, end, value));
    }

    private static int partIndex(List<Part> parts, int position, boolean end) {
        int low = 0;
        int high = parts.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            int boundary = parts.get(middle).end;
            if (boundary < position || !end && boundary == position) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void expandLineBreaks(XWPFRun run) {
        String text = run.getCTR().getTList().stream().map(CTText::getStringValue).collect(java.util.stream.Collectors.joining());
        if (text.indexOf('\n') < 0 && text.indexOf('\t') < 0) return;
        while (run.getCTR().sizeOfTArray() > 0) run.getCTR().removeT(0);
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\n' && character != '\t') continue;
            setText(run.getCTR().addNewT(), text.substring(start, index));
            if (character == '\n') run.getCTR().addNewBr();
            else run.getCTR().addNewTab();
            start = index + 1;
        }
        setText(run.getCTR().addNewT(), text.substring(start));
    }

    private static boolean hasDelimiters(String text) { return text.contains("{{") || text.contains("}}"); }
    private static BusinessException unsupported(String message) { return new BusinessException(422, message); }
    private record Part(CTText text, int start, int end) {}
    private record Replacement(int start, int end, String value) {}
    private record LocalEdit(int start, int end, String value) {}
    private static final class Budget {
        long characters;
        int replacements;
        void check() {
            if (characters > MAX_RENDERED_CHARS || replacements > MAX_REPLACEMENTS) {
                throw new BusinessException(413, "模板生成内容或占位符数量超过处理限制");
            }
        }
    }
}
