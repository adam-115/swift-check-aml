package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lecture minimale d'un classeur Excel (.xlsx) : un .xlsx est une archive ZIP de fichiers XML. On lit les chaînes
 * partagées, la liste des feuilles et les cellules d'une feuille, sans formules ni mise en forme. Suffit pour les
 * tableaux publiés par les offices statistiques (Destatis, Bayerisches Landesamt für Statistik).
 */
final class Xlsx {

    private static final Pattern SHARED = Pattern.compile("(?s)<si>(.*?)</si>");
    private static final Pattern TEXT = Pattern.compile("(?s)<t[^>]*>(.*?)</t>");
    private static final Pattern SHEET = Pattern.compile("<sheet [^>]*name=\"([^\"]+)\"[^>]*r:id=\"([^\"]+)\"");
    private static final Pattern REL = Pattern.compile("<Relationship [^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"");
    private static final Pattern REL_REVERSED = Pattern.compile("<Relationship [^>]*Target=\"([^\"]+)\"[^>]*Id=\"([^\"]+)\"");
    private static final Pattern ROW = Pattern.compile("(?s)<row[^>]*>(.*?)</row>");
    private static final Pattern CELL = Pattern.compile("(?s)<c r=\"([A-Z]+)\\d+\"([^>]*?)(?:/>|>(.*?)</c>)");
    private static final Pattern VALUE = Pattern.compile("(?s)<v>(.*?)</v>");

    private Xlsx() {
    }

    /**
     * Lignes d'une feuille, chacune en colonnes (« A », « B »…) vers valeur texte.
     *
     * @param sheetName motif du nom de la feuille (expression régulière), ou null pour la première feuille qui contient des données
     */
    static List<Map<String, String>> rows(byte[] xlsx, String sheetName) throws IOException {
        Map<String, String> files = unzip(xlsx);
        List<String> shared = new ArrayList<>();
        Matcher s = SHARED.matcher(files.getOrDefault("xl/sharedStrings.xml", ""));
        while (s.find()) {
            StringBuilder sb = new StringBuilder();
            Matcher t = TEXT.matcher(s.group(1));
            while (t.find()) {
                sb.append(t.group(1));
            }
            shared.add(xml(sb.toString()));
        }
        Map<String, String> rels = new HashMap<>();
        String relsXml = files.getOrDefault("xl/_rels/workbook.xml.rels", "");
        for (Pattern p : List.of(REL, REL_REVERSED)) {
            Matcher r = p.matcher(relsXml);
            while (r.find()) {
                boolean idFirst = p == REL;
                rels.putIfAbsent(idFirst ? r.group(1) : r.group(2), idFirst ? r.group(2) : r.group(1));
            }
        }
        List<String> candidates = new ArrayList<>();
        Matcher sh = SHEET.matcher(files.getOrDefault("xl/workbook.xml", ""));
        while (sh.find()) {
            String target = rels.get(sh.group(2));
            if (target == null) {
                continue;
            }
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            if (sheetName == null || Pattern.compile(sheetName).matcher(xml(sh.group(1))).find()) {
                candidates.add(path);
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("feuille « " + sheetName + " » absente du classeur");
        }
        for (String path : candidates) {
            List<Map<String, String>> rows = sheet(files.getOrDefault(path, ""), shared);
            if (!rows.isEmpty() || sheetName != null) {
                return rows;
            }
        }
        return List.of();
    }

    private static List<Map<String, String>> sheet(String xml, List<String> shared) {
        List<Map<String, String>> out = new ArrayList<>();
        Matcher r = ROW.matcher(xml);
        while (r.find()) {
            Map<String, String> row = new LinkedHashMap<>();
            Matcher c = CELL.matcher(r.group(1));
            while (c.find()) {
                String attrs = c.group(2);
                String body = c.group(3) == null ? "" : c.group(3);
                String value;
                if (attrs.contains("t=\"inlineStr\"")) {
                    Matcher t = TEXT.matcher(body);
                    value = t.find() ? xml(t.group(1)) : "";
                } else {
                    Matcher v = VALUE.matcher(body);
                    value = v.find() ? v.group(1) : "";
                    if (attrs.contains("t=\"s\"") && !value.isEmpty()) {
                        value = shared.get(Integer.parseInt(value.trim()));
                    } else {
                        value = xml(value);
                    }
                }
                if (!value.isEmpty()) {
                    row.put(c.group(1), value);
                }
            }
            if (!row.isEmpty()) {
                out.add(row);
            }
        }
        return out;
    }

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
                    files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        if (files.isEmpty()) {
            throw new IOException("le fichier n'est pas un classeur .xlsx");
        }
        return files;
    }

    private static String xml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Date Excel (nombre de jours depuis le 30/12/1899) en ISO, ou null. */
    static String excelDate(String serial) {
        try {
            return java.time.LocalDate.of(1899, 12, 30).plusDays((long) Double.parseDouble(serial.trim())).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
