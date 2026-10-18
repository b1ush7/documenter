package com.documenter.component;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 检查 OOXML 中显式引用字体是否存在于宿主字体库；不声称与 Word 的替换策略一致。 */
final class OfficeFontDiagnostics {
    private OfficeFontDiagnostics() {}
    static List<String> missingFonts(Path source) {
        var available = new HashSet<String>();
        var fonts = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (var locale : List.of(Locale.ENGLISH, Locale.CHINESE, Locale.getDefault())) {
            for (String family : fonts.getAvailableFontFamilyNames(locale)) available.add(family.toLowerCase(Locale.ROOT));
        }
        var requested = new TreeSet<String>();
        try (var archive = new ZipFile(source.toFile())) {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if ((!entry.getName().startsWith("word/") && !entry.getName().equals("xl/styles.xml"))
                        || !entry.getName().endsWith(".xml") || entry.getSize() > 20L * 1024 * 1024) continue;
                try (var input = archive.getInputStream(entry)) {
                    var document = factory.newDocumentBuilder().parse(input);
                    var nodes = document.getElementsByTagNameNS("*", "rFonts");
                    for (int i = 0; i < nodes.getLength(); i++) {
                        var attributes = nodes.item(i).getAttributes();
                        for (int j = 0; j < attributes.getLength(); j++) {
                            var attribute = attributes.item(j);
                            if (Set.of("ascii", "eastAsia", "hAnsi", "cs").contains(attribute.getLocalName())) requested.add(attribute.getNodeValue());
                        }
                    }
                    if (entry.getName().equals("xl/styles.xml")) {
                        var names = document.getElementsByTagNameNS("*", "name");
                        for (int i = 0; i < names.getLength(); i++) {
                            var attribute = names.item(i).getAttributes().getNamedItem("val");
                            if (attribute != null) requested.add(attribute.getNodeValue());
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* 非 OOXML 格式仍可转换，不据此推断字体是否缺失。 */ }
        return requested.stream().filter(name -> !name.isBlank() && !available.contains(name.toLowerCase(Locale.ROOT))).toList();
    }
}
