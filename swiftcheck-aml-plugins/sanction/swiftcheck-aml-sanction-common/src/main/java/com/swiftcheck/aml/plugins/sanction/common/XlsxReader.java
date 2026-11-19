package com.swiftcheck.aml.plugins.sanction.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture minimale d'un classeur Excel (.xlsx) sans Apache POI : un .xlsx est une archive ZIP de fichiers XML
 * (chaînes partagées, feuilles). Suffisant pour les listes publiées en tableau (DFAT, Émirats, Israël, Japon…).
 *
 * <p>Les cellules sont rendues en texte : chaînes partagées, texte en ligne, nombres tels qu'écrits dans le
 * fichier (une date Excel reste un numéro de série : voir {@link #excelDate}).
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /** Toutes les feuilles, dans l'ordre du classeur ; chaque feuille est une liste de lignes. */
    public static List<List<List<String>>> readAll(Path file) throws IOException {
        return readAll(Files.readAllBytes(file));
    }

    public static List<List<List<String>>> readAll(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = unzip(xlsx);
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml"))
                : List.of();
        List<String> sheetNames = new ArrayList<>(parts.keySet().stream()
                .filter(n -> n.matches("xl/worksheets/sheet\\d+\\.xml")).toList());
        sheetNames.sort((a, b) -> Integer.compare(sheetNumber(a), sheetNumber(b)));
        List<List<List<String>>> sheets = new ArrayList<>();
        for (String name : sheetNames) {
            sheets.add(sheet(parts.get(name), shared));
        }
        return sheets;
    }

    /**
     * Une seule feuille, par son numéro de fichier ({@code xl/worksheets/sheetN.xml}) : les autres feuilles ne sont
     * pas décompressées (utile quand le classeur contient une très grande feuille inutile).
     */
    public static List<List<String>> sheet(Path file, int sheetFileNumber) throws IOException {
        String wanted = "xl/worksheets/sheet" + sheetFileNumber + ".xml";
        byte[] sharedXml = null;
        byte[] sheetXml = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/sharedStrings.xml")) {
                    sharedXml = zip.readAllBytes();
                } else if (entry.getName().equals(wanted)) {
                    sheetXml = zip.readAllBytes();
                }
            }
        }
        if (sheetXml == null) {
            throw new IOException("Feuille " + wanted + " absente du classeur");
        }
        return sheet(sheetXml, sharedXml == null ? List.of() : sharedStrings(sharedXml));
    }

    /** Première feuille. */
    public static List<List<String>> firstSheet(Path file) throws IOException {
        List<List<List<String>>> all = readAll(file);
        return all.isEmpty() ? List.of() : all.get(0);
    }

    /**
     * Lignes d'une feuille sous forme « en-tête → valeur ». L'en-tête est la première ligne qui contient la
     * cellule {@code headerMarker} (insensible à la casse), ce qui permet d'ignorer les titres au-dessus du tableau.
     */
    public static List<Map<String, String>> rows(List<List<String>> sheet, String headerMarker) {
        int headerIndex = -1;
        for (int i = 0; i < sheet.size() && headerIndex < 0; i++) {
            for (String cell : sheet.get(i)) {
                if (cell != null && cell.trim().equalsIgnoreCase(headerMarker)) {
                    headerIndex = i;
                    break;
                }
            }
        }
        if (headerIndex < 0) {
            throw new IllegalStateException("En-tête « " + headerMarker + " » introuvable dans la feuille");
        }
        List<String> header = sheet.get(headerIndex);
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = headerIndex + 1; i < sheet.size(); i++) {
            List<String> line = sheet.get(i);
            Map<String, String> row = new LinkedHashMap<>();
            boolean any = false;
            for (int c = 0; c < header.size(); c++) {
                String key = header.get(c) == null ? "" : header.get(c).replaceAll("\\s+", " ").trim();
                String value = c < line.size() ? line.get(c) : null;
                if (value != null && !value.isBlank()) {
                    any = true;
                }
                if (!key.isEmpty()) {
                    row.putIfAbsent(key, value == null ? null : value.trim());
                }
            }
            if (any) {
                out.add(row);
            }
        }
        return out;
    }

    /** Numéro de série Excel (ex. « 23377 ») → date ISO ; la valeur telle quelle si ce n'est pas un nombre. */
    public static String excelDate(String value) {
        if (value == null || !value.trim().matches("\\d{4,5}(\\.0+)?")) {
            return value;
        }
        long days = (long) Double.parseDouble(value.trim());
        return java.time.LocalDate.of(1899, 12, 30).plusDays(days).toString();
    }

    private static int sheetNumber(String name) {
        return Integer.parseInt(name.replaceAll("\\D", ""));
    }

    private static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/sheet")) {
                    parts.put(name, zip.readAllBytes());
                }
            }
        }
        if (parts.isEmpty()) {
            throw new IOException("Le fichier n'est pas un classeur .xlsx");
        }
        return parts;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(xml);
        return f.createXMLStreamReader(in);
    }

    private static List<String> sharedStrings(byte[] xml) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = reader(xml);
            StringBuilder current = null;
            boolean inPhonetic = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("si")) {
                        current = new StringBuilder();
                    } else if (n.equals("rPh")) {
                        inPhonetic = true; // lecture phonétique (japonais) : ignorée
                    } else if (n.equals("t") && current != null && !inPhonetic) {
                        current.append(r.getElementText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("si") && current != null) {
                        out.add(current.toString());
                        current = null;
                    } else if (r.getLocalName().equals("rPh")) {
                        inPhonetic = false;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("sharedStrings.xml illisible", e);
        }
        return out;
    }

    private static List<List<String>> sheet(byte[] xml, List<String> shared) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = reader(xml);
            List<String> row = null;
            String cellRef = null;
            String cellType = null;
            String value = null;
            StringBuilder inline = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            cellRef = r.getAttributeValue(null, "r");
                            cellType = r.getAttributeValue(null, "t");
                            value = null;
                            inline = null;
                        }
                        case "v" -> value = r.getElementText();
                        case "is" -> inline = new StringBuilder();
                        case "t" -> {
                            if (inline != null) {
                                inline.append(r.getElementText());
                            }
                        }
                        default -> {
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("c") && row != null) {
                        String text;
                        if ("s".equals(cellType) && value != null) {
                            int i = Integer.parseInt(value.trim());
                            text = i < shared.size() ? shared.get(i) : null;
                        } else if (inline != null) {
                            text = inline.toString();
                        } else {
                            text = value;
                        }
                        int col = cellRef == null ? row.size() : column(cellRef);
                        while (row.size() < col) {
                            row.add(null);
                        }
                        row.add(text);
                    } else if (n.equals("row") && row != null) {
                        rows.add(row);
                        row = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("Feuille illisible", e);
        }
        return rows;
    }

    /** « C12 » → 2 (colonne C, base 0). */
    private static int column(String ref) {
        int col = 0;
        for (char ch : ref.toCharArray()) {
            if (Character.isLetter(ch)) {
                col = col * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }
}
