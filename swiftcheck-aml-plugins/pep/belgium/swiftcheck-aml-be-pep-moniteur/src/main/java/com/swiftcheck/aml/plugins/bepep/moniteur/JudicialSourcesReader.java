package com.swiftcheck.aml.plugins.bepep.moniteur;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Appointment;
import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Kind;

/**
 * Lit les sources officielles directes des hautes juridictions judiciaires belges :
 * <ul>
 *   <li><b>Cour de cassation</b> ({@code cass.be}) : aperçus officiels des magistrats du siège
 *       ({@code Historischoverzichtvandemagistratenvandezetel.pdf}) et du parquet ({@code HistoriqueAGFRNL.pdf}),
 *       ainsi que les audiences solennelles d'installation ({@code News-2025.html}, {@code News-2026.html}) ;</li>
 *   <li><b>Collège des cours et tribunaux</b> ({@code tribunaux-rechtbanken.be/fr/college/composition}) :
 *       premiers présidents des cours d'appel et des cours du travail siégeant au Collège.</li>
 * </ul>
 */
final class JudicialSourcesReader {

    static final String DEFAULT_CASS_SIEGE_URL = "https://www.cass.be/pdf/Historischoverzichtvandemagistratenvandezetel.pdf";
    static final String DEFAULT_CASS_PARQUET_URL = "https://www.cass.be/pdf/HistoriqueAGFRNL.pdf";
    static final String DEFAULT_CASS_NEWS_2025_URL = "https://www.cass.be/News-2025.html";
    static final String DEFAULT_CASS_NEWS_2026_URL = "https://www.cass.be/News-2026.html";
    static final String DEFAULT_COLLEGE_URL = "https://www.tribunaux-rechtbanken.be/fr/college/composition";

    static final String FILE_CASS_SIEGE = "cass-hist-siege.pdf";
    static final String FILE_CASS_PARQUET = "cass-hist-parquet.pdf";
    static final String FILE_CASS_NEWS_2025 = "cass-news-2025.html";
    static final String FILE_CASS_NEWS_2026 = "cass-news-2026.html";
    static final String FILE_COLLEGE = "college-composition.html";

    /**
     * Ligne des PDF historiques de la Cour de cassation :
     * {@code 2016 : Eric de Formanoir de la Cazerie, écuyer, Eerste voorzitter – Premier président}
     * ou {@code 1997-2025 : Christian Storck, Voorzitter - Président}.
     */
    private static final Pattern CASS_PDF_LINE = Pattern.compile(
            "^(\\d{4})(?:\\s*[-–]\\s*(\\d{4}))?\\s*:\\s*(.+?),\\s*(?:écuyer,\\s*)?[^-–]+[-–]\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern NOBLE_PREFIX = Pattern.compile(
            "^(?:Baron|Baronne|Chevalier|Comte|Comtesse|Vicomte|Vicomtesse|Burggraaf|Ridder)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Mention d'installation dans les actualités de la Cour de cassation :
     * {@code Madame Marie-Noëlle Borlée, conseiller à la cour du travail de Liège, nommée conseiller à la Cour de cassation par arrêté royal du 10 novembre 2025}
     */
    private static final Pattern CASS_NEWS_INSTALL = Pattern.compile(
            "(Madame|Monsieur)\\s+([A-ZÀ-ÖØ-Ý][A-Za-zÀ-ÖØ-öø-ÿ'’-]+(?:\\s+[A-ZÀ-ÖØ-Ýa-zà-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ'’-]+){1,4})\\s*,\\s*"
                    + "([^,]+?)\\s*,\\s*(?:[^,]+?,\\s*)?(?:qui\\s+a\\s+été\\s+|est\\s+)?"
                    + "(?:nommée?|désignée?)\\s+(?:en\\s+qualité\\s+de\\s+)?"
                    + "(président\\s+de\\s+section|conseiller\\s+à\\s+la\\s+Cour\\s+de\\s+cassation|avocat\\s+général\\s+près\\s+la\\s+Cour\\s+de\\s+cassation)"
                    + "(?:\\s+par\\s+(?:arrêté\\s+royal|l['’]assemblée\\s+générale\\s+de\\s+la\\s+Cour)\\s+(?:du|le)\\s+(\\d{1,2})\\s+([a-zéû]+)\\s+(\\d{4}))?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("février", 2), Map.entry("fevrier", 2),
            Map.entry("mars", 3), Map.entry("avril", 4), Map.entry("mai", 5),
            Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("août", 8), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11),
            Map.entry("décembre", 12), Map.entry("decembre", 12));

    record SourcedAppointment(String sourceUrl, LocalDate actDate, Appointment appointment) {
    }

    private JudicialSourcesReader() {
    }

    static List<SourcedAppointment> readAll(Path rawDir, Map<String, String> params, LocalDate oldestEndDate) {
        List<SourcedAppointment> out = new ArrayList<>();

        Path siegePdf = rawDir.resolve(FILE_CASS_SIEGE);
        if (Files.isRegularFile(siegePdf)) {
            String url = params.getOrDefault("cassationSiegeUrl", DEFAULT_CASS_SIEGE_URL);
            out.addAll(readCassationHistoricalPdf(siegePdf, url, oldestEndDate));
        }

        Path parquetPdf = rawDir.resolve(FILE_CASS_PARQUET);
        if (Files.isRegularFile(parquetPdf)) {
            String url = params.getOrDefault("cassationParquetUrl", DEFAULT_CASS_PARQUET_URL);
            out.addAll(readCassationHistoricalPdf(parquetPdf, url, oldestEndDate));
        }

        Path news2025 = rawDir.resolve(FILE_CASS_NEWS_2025);
        if (Files.isRegularFile(news2025)) {
            String url = params.getOrDefault("cassationNews2025Url", DEFAULT_CASS_NEWS_2025_URL);
            out.addAll(readCassationNewsHtml(news2025, url, LocalDate.of(2025, 11, 1)));
        }

        Path news2026 = rawDir.resolve(FILE_CASS_NEWS_2026);
        if (Files.isRegularFile(news2026)) {
            String url = params.getOrDefault("cassationNews2026Url", DEFAULT_CASS_NEWS_2026_URL);
            out.addAll(readCassationNewsHtml(news2026, url, LocalDate.of(2026, 1, 1)));
        }

        Path collegeHtml = rawDir.resolve(FILE_COLLEGE);
        if (Files.isRegularFile(collegeHtml)) {
            String url = params.getOrDefault("collegeCompositionUrl", DEFAULT_COLLEGE_URL);
            out.addAll(readCollegeCompositionHtml(collegeHtml, url));
        }

        return out;
    }

    static List<SourcedAppointment> readCassationHistoricalPdf(Path pdfFile, String sourceUrl, LocalDate oldestEndDate) {
        List<SourcedAppointment> list = new ArrayList<>();
        String text;
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            text = new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            return list;
        }

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
            Matcher m = CASS_PDF_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int startYear = Integer.parseInt(m.group(1));
            String endYearStr = m.group(2);
            LocalDate startDate = LocalDate.of(startYear, 1, 1);
            LocalDate endDate = null;
            if (endYearStr != null) {
                int endYear = Integer.parseInt(endYearStr);
                endDate = LocalDate.of(endYear, 12, 31);
                if (endDate.isBefore(oldestEndDate)) {
                    continue;
                }
            }

            String name = cleanCassationName(m.group(3));
            String function = normalizeCassationFunction(m.group(4));
            if (name.isEmpty() || function == null) {
                continue;
            }

            list.add(new SourcedAppointment(
                    sourceUrl,
                    startDate,
                    new Appointment(Kind.NOMINATION, null, name, function, startDate, endDate)));
        }
        return list;
    }

    static List<SourcedAppointment> readCassationNewsHtml(Path htmlFile, String sourceUrl, LocalDate fallbackDate) {
        List<SourcedAppointment> list = new ArrayList<>();
        String html;
        try {
            html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return list;
        }

        Document doc = Jsoup.parse(html);
        for (Element p : doc.select("p")) {
            String text = MoniteurAppointmentReader.normalize(p.text());
            if (!text.toLowerCase(Locale.FRENCH).contains("installation")) {
                continue;
            }
            Matcher m = CASS_NEWS_INSTALL.matcher(text);
            while (m.find()) {
                String civility = m.group(1);
                String name = cleanCassationName(m.group(2));
                String previousRole = m.group(3).trim();
                String newRoleRaw = m.group(4).trim();
                LocalDate actDate = fallbackDate;
                if (m.group(5) != null && m.group(6) != null && m.group(7) != null) {
                    Integer month = MONTHS.get(m.group(6).toLowerCase(Locale.FRENCH));
                    if (month != null) {
                        actDate = LocalDate.of(Integer.parseInt(m.group(7)), month, Integer.parseInt(m.group(5)));
                    }
                }

                String cassRole = normalizeCassationFunction(newRoleRaw);
                if (cassRole != null) {
                    list.add(new SourcedAppointment(
                            sourceUrl,
                            actDate,
                            new Appointment(Kind.NOMINATION, civility, name, cassRole, actDate, null)));
                }

                // Si la fonction antérieure est une fonction PEP (ex. conseiller à la cour d'appel / cour du travail),
                // l'enregistrer comme mandat clos à la date de nomination à la Cour de cassation.
                if (!previousRole.toLowerCase(Locale.FRENCH).contains("cour de cassation")
                        && BePepMoniteurParser.matchPepFunction(previousRole) != null) {
                    list.add(new SourcedAppointment(
                            sourceUrl,
                            actDate,
                            new Appointment(Kind.RESIGNATION, civility, name, previousRole, actDate, actDate)));
                }
            }
        }
        return list;
    }

    static List<SourcedAppointment> readCollegeCompositionHtml(Path htmlFile, String sourceUrl) {
        List<SourcedAppointment> list = new ArrayList<>();
        String html;
        try {
            html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return list;
        }

        Document doc = Jsoup.parse(html);
        Element main = doc.selectFirst("main, body");
        if (main == null) {
            return list;
        }
        for (Element el : main.select("br, p, div, li, tr")) {
            el.prepend("\n");
            el.append("\n");
        }
        List<String> lines = new ArrayList<>();
        for (String raw : main.wholeText().split("\n")) {
            String clean = MoniteurAppointmentReader.normalize(raw);
            if (!clean.isEmpty()) {
                lines.add(clean);
            }
        }

        LocalDate startDate = LocalDate.of(2025, 11, 20);
        for (int i = 0; i + 1 < lines.size(); i++) {
            String candidateName = lines.get(i);
            String candidateRole = lines.get(i + 1);
            if (BePepMoniteurParser.matchPepFunction(candidateRole) != null
                    && candidateName.matches("^[A-ZÀ-ÖØ-Ý][a-zà-öø-ÿ'-]+(?:\\s+[A-ZÀ-ÖØ-Ýa-zà-öø-ÿ][a-zà-öø-ÿ'-]+)+$")) {
                list.add(new SourcedAppointment(
                        sourceUrl,
                        startDate,
                        new Appointment(Kind.NOMINATION, null, candidateName, candidateRole, startDate, null)));
                i++;
            }
        }
        return list;
    }

    static String cleanCassationName(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = raw.replaceAll(",\\s*écuyer\\b", "")
                .replaceAll("\\s+", " ")
                .trim();
        clean = NOBLE_PREFIX.matcher(clean).replaceFirst("").trim();
        if ("Lugentz Frédéric".equalsIgnoreCase(clean)) {
            return "Frédéric Lugentz";
        }
        return clean;
    }

    static String normalizeCassationFunction(String rawRoleFr) {
        if (rawRoleFr == null) {
            return null;
        }
        String r = rawRoleFr.toLowerCase(Locale.FRENCH).replaceAll("\\s+", " ").trim();
        if (r.contains("premier président")) {
            return "Premier président de la Cour de cassation";
        }
        if (r.contains("président de section")) {
            return "Président de section à la Cour de cassation";
        }
        if (r.startsWith("président")) {
            return "Président de la Cour de cassation";
        }
        if (r.contains("conseiller")) {
            return "Conseiller à la Cour de cassation";
        }
        if (r.contains("premier avocat général")) {
            return "Premier avocat général près la Cour de cassation";
        }
        if (r.contains("avocat général délégué")) {
            return "Avocat général délégué près la Cour de cassation";
        }
        if (r.contains("avocat général")) {
            return "Avocat général près la Cour de cassation";
        }
        if (r.contains("procureur général")) {
            return "Procureur général près la Cour de cassation";
        }
        return null;
    }
}

