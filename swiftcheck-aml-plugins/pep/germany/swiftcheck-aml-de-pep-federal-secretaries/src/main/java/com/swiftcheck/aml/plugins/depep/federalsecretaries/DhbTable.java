package com.swiftcheck.aml.plugins.depep.federalsecretaries;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Lecture du tableau « Namentliche Aufstellung der Minister und Staatssekretäre » du Datenhandbuch du Bundestag
 * (chapitre 6.3). Le tableau a trois colonnes (ministre, secrétaires d'État parlementaires, secrétaires d'État
 * fonctionnaires) et PDFBox fusionne une ligne en un seul texte : on découpe donc chaque ligne aux espaces larges, puis
 * on classe les morceaux par colonne d'après leur abscisse.
 */
final class DhbTable {

    /** Morceau de texte positionné : page, ordonnée, abscisse, texte. */
    record Seg(int page, float y, float x, String text) {
    }

    enum Kind { MINISTER, PARLIAMENTARY, CIVIL }

    /** Un titulaire lu dans un bloc « 21. WP ». */
    record Holder(String ministry, Kind kind, String name, String party, String role, LocalDate start, LocalDate end,
                  boolean deceased) {
    }

    static final float COL_LABEL = 150f;
    static final float COL_MINISTER = 205f;
    static final float COL_PARLIAMENTARY = 325f;
    static final float COL_CIVIL = 440f;
    static final float GAP = 9f;

    private static final Pattern WP_ROW = Pattern.compile("^(\\d+)\\. WP$");
    private static final Pattern YEAR = Pattern.compile("^\\d{4}\\s*[–-]\\s*(\\d{4})?$");
    private static final Pattern FOOTER = Pattern.compile("^Seite \\d+ von \\d+$");
    private static final Pattern SLOT = Pattern.compile("^(\\d)\\)\\s*(.*)$");
    private static final Pattern DATE_LINE = Pattern.compile(
            "^\\((bis|ab)\\s*(†\\s*)?(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{4}|(?:Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\\s+\\d{4})\\)$");
    private static final Pattern PARTY = Pattern.compile("\\s*\\((?!bis|ab)[^)]*\\)\\s*$");
    private static final Pattern NAME = Pattern.compile(
            "^(?:(?:Dr|Prof)\\.\\s+)*\\p{Lu}[\\p{L}'’\\-]*(?:\\s+(?:von|van|de|zu|der|ter|vom)?\\s*\\p{Lu}[\\p{L}'’\\-]*){1,4}$");
    private static final Pattern SURNAME_ONLY = Pattern.compile("^\\p{Lu}[\\p{L}'’\\-]*$");
    private static final Pattern STAND = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");
    private static final java.util.Set<String> DESCRIPTOR_WORDS = java.util.Set.of("Staatsminister", "Staatsministerin",
            "Staatssekretär", "Staatssekretärin", "Beauftragte", "Beauftragter", "Chef", "Amtschef", "Ständiger", "Vertreter",
            "Bundesregierung", "Regierungssprecher", "Informationsamtes", "Bundeskanzleramtes", "Presse-", "Bund-", "Länder-",
            "Zusammenarbeit", "Migration", "Flüchtlinge", "Integration", "Ostdeutschland", "Bundeskanzler", "Bundeskanzlerin",
            "Bundesminister", "Bundesministerin", "Kultur", "Medien", "Kulturstaatsminister");

    private DhbTable() {
    }

    // ------------------------------------------------------------------ PDF -> morceaux positionnés

    static List<Seg> segments(InputStream pdf) throws IOException {
        List<Seg> out = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    StringBuilder cur = new StringBuilder();
                    float startX = 0;
                    float startY = 0;
                    float lastEnd = 0;
                    for (TextPosition p : positions) {
                        boolean gap = cur.length() > 0 && p.getXDirAdj() - lastEnd > GAP;
                        if (gap) {
                            flush(out, getCurrentPageNo(), startY, startX, cur);
                        }
                        if (cur.length() == 0 && p.getUnicode().isBlank()) {
                            continue;
                        }
                        if (cur.length() == 0) {
                            startX = p.getXDirAdj();
                            startY = p.getYDirAdj();
                        }
                        cur.append(p.getUnicode());
                        lastEnd = p.getXDirAdj() + p.getWidthDirAdj();
                    }
                    flush(out, getCurrentPageNo(), startY, startX, cur);
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
        }
        return out;
    }

    private static void flush(List<Seg> out, int page, float y, float x, StringBuilder cur) {
        String t = cur.toString().replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (!t.isEmpty()) {
            out.add(new Seg(page, y, x, t));
        }
        cur.setLength(0);
    }

    /** Date « Stand » lue dans l'en-tête de la première page (« DHB Kapitel 6.3 … 12.08.2026 »). */
    static LocalDate standDate(List<Seg> segs) {
        for (Seg s : segs) {
            if (s.page() == 1 && s.y() < 70 && (s.text().startsWith("DHB Kapitel") || STAND.matcher(s.text()).matches())) {
                Matcher m = STAND.matcher(s.text());
                String last = null;
                while (m.find()) {
                    last = m.group(1);
                }
                if (last != null) {
                    return LocalDate.parse(last, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ blocs « 21. WP »

    static List<Holder> holders(List<Seg> all, int term) {
        List<Seg> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(Seg::page).thenComparing(Seg::y).thenComparing(Seg::x));
        int lastPage = sorted.stream().mapToInt(Seg::page).max().orElse(0);
        List<Holder> result = new ArrayList<>();
        String ministry = "";
        for (int page = 1; page <= lastPage; page++) {
            final int pg = page;
            List<Seg> segs = sorted.stream().filter(s -> s.page() == pg).toList();
            List<Seg> rows = segs.stream().filter(s -> s.x() < COL_MINISTER && WP_ROW.matcher(s.text()).matches()).toList();
            float footerY = segs.stream().filter(s -> FOOTER.matcher(s.text()).matches() || s.text().startsWith("Quelle:"))
                    .mapToDouble(Seg::y)
                    .min().stream().mapToObj(d -> (float) d).findFirst().orElse(9999f);
            for (int i = 0; i < rows.size(); i++) {
                Seg row = rows.get(i);
                float yEnd = i + 1 < rows.size() ? rows.get(i + 1).y() : footerY;
                // libellé du ministère : texte de la colonne de gauche autour de la ligne
                ministry = ministryLabel(segs, row.y(), yEnd, ministry);
                Matcher m = WP_ROW.matcher(row.text());
                m.matches();
                if (Integer.parseInt(m.group(1)) != term) {
                    continue;
                }
                List<Seg> block = new ArrayList<>(segs.stream().filter(s -> s.y() >= row.y() - 1 && s.y() < yEnd - 1).toList());
                if (i + 1 >= rows.size() && page < lastPage) {
                    // le bloc peut continuer en haut de la page suivante, avant sa première ligne « NN. WP »
                    final int next = page + 1;
                    List<Seg> nextSegs = sorted.stream().filter(s -> s.page() == next).toList();
                    float firstRow = nextSegs.stream().filter(s -> s.x() < COL_MINISTER && WP_ROW.matcher(s.text()).matches())
                            .mapToDouble(Seg::y).min().stream().mapToObj(d -> (float) d).findFirst().orElse(9999f);
                    for (Seg s : nextSegs) {
                        if (s.y() < firstRow - 1 && s.y() > 90 && !isTableHeader(s)) {
                            block.add(new Seg(page, footerY + (s.y() - 90), s.x(), s.text()));
                        }
                    }
                }
                result.addAll(readBlock(block, ministry));
            }
        }
        return result;
    }

    private static boolean isTableHeader(Seg s) {
        String t = s.text();
        return t.startsWith("DHB Kapitel") || t.equals("Wahl-") || t.equals("periode") || t.equals("Parlamentarische")
                || t.equals("Staatssekretäre") || t.equals("Bundesministerium") || t.equals("Bundesminister")
                || t.equals("Beamtete Staatssekretäre") || FOOTER.matcher(t).matches();
    }

    private static String ministryLabel(List<Seg> segs, float y0, float y1, String previous) {
        List<String> lines = new ArrayList<>();
        for (Seg s : segs) {
            if (s.x() < COL_LABEL && s.y() >= y0 - 22 && s.y() < y1 - 1 && !WP_ROW.matcher(s.text()).matches()
                    && !YEAR.matcher(s.text()).matches() && !isTableHeader(s) && s.text().length() > 2
                    && !s.text().matches("^[0-9]+$")) {
                lines.add(s.text());
            }
        }
        if (lines.isEmpty()) {
            return previous;
        }
        String joined = String.join(" ", lines);
        int k = joined.lastIndexOf(". WP:");
        if (k >= 0) {
            joined = joined.substring(k + 5);
        }
        joined = joined.replaceAll("\\s+", " ").trim();
        boolean label = joined.startsWith("BM") || joined.startsWith("Auswärtiges") || joined.startsWith("Bundeskanzleramt");
        return label ? joined : previous;
    }

    private static List<Holder> readBlock(List<Seg> block, String ministry) {
        List<Holder> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            List<Seg> col = block.stream().filter(s -> s.x() >= COL_MINISTER && kind(s.x()) == kind && !WP_ROW.matcher(s.text()).matches()
                    && !YEAR.matcher(s.text()).matches() && !FOOTER.matcher(s.text()).matches()).toList();
            out.addAll(readColumn(col, kind, ministry));
        }
        return out;
    }

    static Kind kind(float x) {
        return x < COL_PARLIAMENTARY ? Kind.MINISTER : (x < COL_CIVIL ? Kind.PARLIAMENTARY : Kind.CIVIL);
    }

    private static List<Holder> readColumn(List<Seg> col, Kind kind, String ministry) {
        // regrouper les morceaux de même ordonnée
        List<Seg> sorted = new ArrayList<>(col);
        sorted.sort(Comparator.comparing(Seg::y).thenComparing(Seg::x));
        List<String> lines = new ArrayList<>();
        float lastY = -100;
        for (Seg s : sorted) {
            if (!lines.isEmpty() && Math.abs(s.y() - lastY) < 2.5f) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + s.text());
            } else {
                lines.add(s.text());
            }
            lastY = s.y();
        }
        List<Holder> out = new ArrayList<>();
        List<String> roleLines = new ArrayList<>();
        String pendingWord = null;
        Draft current = null;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher slot = SLOT.matcher(line);
            if (slot.matches()) {
                finish(out, current, ministry, kind);
                current = null;
                roleLines = new ArrayList<>();
                pendingWord = null;
                line = slot.group(2).trim();
                if (line.isEmpty()) {
                    continue;
                }
            }
            if (pendingWord != null && !(SURNAME_ONLY.matcher(PARTY.matcher(line).replaceFirst("").trim()).matches()
                    && PARTY.matcher(line).find())) {
                roleLines.add(pendingWord); // fragment de libellé (« Länder-Zusammen- »), pas un prénom
                pendingWord = null;
            }
            if ("N. N.".equals(line)) { // successeur non encore nommé : les dates qui suivent ne concernent personne
                finish(out, current, ministry, kind);
                current = null;
                continue;
            }
            if (line.matches("^\\((?!bis|ab)[^)]*\\)$") && current != null && current.party == null) {
                current.party = line; // groupe politique seul sur sa ligne
                continue;
            }
            Matcher date = DATE_LINE.matcher(line);
            if (date.matches()) {
                if (current != null) {
                    boolean deceased = date.group(2) != null;
                    LocalDate d = parseDate(date.group(3), "bis".equals(date.group(1)));
                    if ("bis".equals(date.group(1))) {
                        current.end = d;
                    } else {
                        current.start = d;
                    }
                    current.deceased |= deceased;
                }
                continue;
            }
            // un morceau peut contenir un nom suivi d'une date : « Nom (CDU) (bis 29.7.2026) »
            Matcher inline = Pattern.compile("^(.*?)\\s*(\\((?:bis|ab)\\s[^)]*\\))$").matcher(line);
            String trailingDate = null;
            if (inline.matches() && !inline.group(1).isEmpty()) {
                line = inline.group(1).trim();
                trailingDate = inline.group(2);
            }
            String namePart = PARTY.matcher(line).replaceFirst("").trim();
            String party = namePart.length() < line.length() ? line.substring(namePart.length()).trim() : null;
            boolean isName = NAME.matcher(namePart).matches() && !hasDescriptor(namePart);
            boolean surnameOnly = SURNAME_ONLY.matcher(namePart).matches() && party != null && pendingWord != null;
            if (surnameOnly) {
                namePart = pendingWord + " " + namePart;
                isName = true;
                pendingWord = null;
            }
            if (isName) {
                finish(out, current, ministry, kind);
                current = new Draft(namePart, party, String.join(" ", roleLines));
            } else if (SURNAME_ONLY.matcher(line).matches() && !DESCRIPTOR_WORDS.contains(line)) {
                pendingWord = line; // prénom coupé en fin de ligne
            } else {
                roleLines.add(line);
            }
            if (trailingDate != null && current != null) {
                Matcher td = DATE_LINE.matcher(trailingDate);
                if (td.matches()) {
                    LocalDate d = parseDate(td.group(3), "bis".equals(td.group(1)));
                    if ("bis".equals(td.group(1))) {
                        current.end = d;
                    } else {
                        current.start = d;
                    }
                    current.deceased |= td.group(2) != null;
                }
            }
        }
        finish(out, current, ministry, kind);
        return out;
    }

    private static boolean hasDescriptor(String text) {
        for (String w : text.split("\\s+")) {
            if (DESCRIPTOR_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static final class Draft {
        final String name;
        String party;
        final String role;
        LocalDate start;
        LocalDate end;
        boolean deceased;

        Draft(String name, String party, String role) {
            this.name = name;
            this.party = party;
            this.role = role;
        }
    }

    private static void finish(List<Holder> out, Draft d, String ministry, Kind kind) {
        if (d != null) {
            String party = d.party == null ? null : d.party.replaceAll("^\\(|\\)$", "").replaceAll(";?\\s*nicht MdB", "")
                    .replaceAll("\\d+$", "").trim();
            out.add(new Holder(ministry, kind, d.name, party == null || party.isEmpty() ? null : party, d.role,
                    d.start, d.end, d.deceased));
        }
    }

    static LocalDate parseDate(String text, boolean endOfPeriod) {
        String t = text.replaceAll("\\s+", "");
        Matcher m = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").matcher(t);
        if (m.matches()) {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        String[] months = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober",
                "November", "Dezember"};
        Matcher mm = Pattern.compile("^(\\p{L}+)(\\d{4})$").matcher(t);
        if (mm.matches()) {
            for (int i = 0; i < months.length; i++) {
                if (months[i].equals(mm.group(1))) {
                    YearMonth ym = YearMonth.of(Integer.parseInt(mm.group(2)), i + 1);
                    return endOfPeriod ? ym.atEndOfMonth() : ym.atDay(1);
                }
            }
        }
        return null;
    }
}
