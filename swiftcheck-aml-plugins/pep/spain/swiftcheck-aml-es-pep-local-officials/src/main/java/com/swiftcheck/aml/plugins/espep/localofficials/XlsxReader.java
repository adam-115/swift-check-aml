package com.swiftcheck.aml.plugins.espep.localofficials;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class XlsxReader {

    private static final DateTimeFormatter SPANISH_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record MayorRecord(
            String community,
            String province,
            String municipality,
            String ineCode,
            String name,
            String party,
            LocalDate startDate,
            LocalDate endDate
    ) {}

    private XlsxReader() {}

    public static List<MayorRecord> readMayors(Path xlsxFile) throws Exception {
        List<String> strings = new ArrayList<>();
        byte[] sheetBytes = null;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(xlsxFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/sharedStrings.xml".equals(entry.getName())) {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    byte[] strBytes = zis.readAllBytes();
                    Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(strBytes));
                    NodeList sis = doc.getElementsByTagName("si");
                    for (int i = 0; i < sis.getLength(); i++) {
                        strings.add(sis.item(i).getTextContent());
                    }
                } else if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    sheetBytes = zis.readAllBytes();
                }
            }
        }

        if (sheetBytes == null) {
            throw new IllegalArgumentException("sheet1.xml introuvable dans le fichier " + xlsxFile);
        }

        List<MayorRecord> records = new ArrayList<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(sheetBytes));
        NodeList rowNodes = doc.getElementsByTagName("row");

        for (int i = 0; i < rowNodes.getLength(); i++) {
            Element rowEl = (Element) rowNodes.item(i);
            NodeList cs = rowEl.getElementsByTagName("c");
            Map<String, String> colMap = new HashMap<>();
            for (int j = 0; j < cs.getLength(); j++) {
                Element c = (Element) cs.item(j);
                String ref = c.getAttribute("r");
                String colLetter = ref.replaceAll("\\d", "");
                String type = c.getAttribute("t");
                String val = c.getTextContent();
                if ("s".equals(type) && !val.isBlank()) {
                    try {
                        int idx = Integer.parseInt(val.trim());
                        val = idx < strings.size() ? strings.get(idx) : val;
                    } catch (NumberFormatException ignored) {}
                }
                colMap.put(colLetter, val != null ? val.trim() : "");
            }

            String name = colMap.get("H");
            String municipality = colMap.get("F");
            if (name == null || name.isBlank() || "NOMBRE".equalsIgnoreCase(name) || municipality == null || municipality.isBlank()) {
                continue;
            }

            String community = colMap.getOrDefault("D", "");
            String province = colMap.getOrDefault("E", "");
            String ineCode = colMap.getOrDefault("G", "");
            String party = colMap.getOrDefault("I", "");
            LocalDate start = parseDate(colMap.get("J"));
            LocalDate end = parseDate(colMap.get("K"));

            records.add(new MayorRecord(community, province, municipality, ineCode, name, party, start, end));
        }

        return records;
    }

    private static LocalDate parseDate(String val) {
        if (val == null || val.isBlank() || val.contains("SIN FECHA")) {
            return null;
        }
        try {
            return LocalDate.parse(val.trim(), SPANISH_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
