package com.swiftcheck.aml.plugins.be.pep.councilofstate;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser PEP Belgique – Conseil d'État (raadvst-consetat.be).
 * Extrait les hauts magistrats : Premier Président, Présidents de chambre,
 * Conseillers d'État (titulaires et suppléants), Assesseurs et Auditeurs.
 */
@Extension
public class BePepCouncilOfStateParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepCouncilOfStateParser.class);

    static final String DEFAULT_URL = "https://www.raadvst-consetat.be/fr/le-conseil-detat/organisation/direction-et-gestion/";
    static final String FILE_NAME = "council-of-state-direction.html";
    static final String CHAMBRES_PDF_URL = "https://www.raadvst-consetat.be/media/5ecflwih/chambres.pdf";
    static final String AUDIT_PDF_URL = "https://www.raadvst-consetat.be/media/ktni0t5j/auditfr.pdf";
    static final String CHAMBRES_FILE_NAME = "chambres.pdf";
    static final String AUDIT_FILE_NAME = "auditfr.pdf";

    static final String ID_PREFIX = "be-pep-council-of-state";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String BASE_URL = "https://www.raadvst-consetat.be";
    static final String PHOTO_LICENCE = "© Conseil d'État de Belgique";

    private static final Pattern PERSON_CARD_PATTERN = Pattern.compile(
            "(?:<img[^>]+src=\"([^\"]+)\"[^>]*>[\\s\\S]{0,400}?)?"
            + "<h4[^>]*class=\"[^\"]*o-person__name[^\"]*\"[^>]*>([\\s\\S]*?)</h4>\\s*"
            + "<p[^>]*class=\"[^\"]*o-person__function[^\"]*\"[^>]*>([\\s\\S]*?)</p>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern AUDITOR_LINE_PATTERN = Pattern.compile(
            "^(?:M\\.|Mme)\\s+([A-ZÀ-ÖØ-Þa-zà-öø-ÿ .'-]+?)\\s+(Premier auditeur chef de section|Premier auditeur|Auditeur adjoint|Auditeur)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());

        Path htmlFile = ctx.rawDir().resolve(FILE_NAME);
        try {
            downloadFile(DEFAULT_URL, htmlFile);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Impossible de télécharger l'annuaire direction HTML : {}", e.getMessage());
            copyClasspathFallback("/" + FILE_NAME, htmlFile);
        }

        Path chambresPdf = ctx.rawDir().resolve(CHAMBRES_FILE_NAME);
        try {
            downloadBinary(CHAMBRES_PDF_URL, chambresPdf);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Impossible de télécharger chambres.pdf : {}", e.getMessage());
            copyClasspathFallback("/" + CHAMBRES_FILE_NAME, chambresPdf);
        }

        Path auditPdf = ctx.rawDir().resolve(AUDIT_FILE_NAME);
        try {
            downloadBinary(AUDIT_PDF_URL, auditPdf);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Impossible de télécharger auditfr.pdf : {}", e.getMessage());
            copyClasspathFallback("/" + AUDIT_FILE_NAME, auditPdf);
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<MemberRecord> records = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(records.size() + " magistrats du Conseil d'État extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private void downloadFile(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - Conseil d'Etat)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
            Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
        } else {
            throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
        }
    }

    private void downloadBinary(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - Conseil d'Etat)")
                .GET().build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 1000) {
            Files.write(target, resp.body());
        } else {
            throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
        }
    }

    private static void copyClasspathFallback(String resourceName, Path target) {
        try (InputStream is = BePepCouncilOfStateParser.class.getResourceAsStream(resourceName)) {
            if (is != null) {
                Files.copy(is, target);
            }
        } catch (Exception ignored) {
        }
    }

    List<MemberRecord> convert(Path dirOrFile, FtmDatasetBuilder dataset) throws IOException {
        Path rawDir = Files.isDirectory(dirOrFile) ? dirOrFile : dirOrFile.getParent();
        Map<String, MemberRecord> allMembers = new LinkedHashMap<>();

        // 1. Direction HTML
        Path htmlFile = rawDir.resolve(FILE_NAME);
        if (Files.isRegularFile(htmlFile)) {
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            for (MemberRecord r : parseMembersHtml(html)) {
                allMembers.put(r.slug(), r);
            }
        }

        // 2. Chambres PDF
        Path chambresPdf = rawDir.resolve(CHAMBRES_FILE_NAME);
        if (Files.isRegularFile(chambresPdf)) {
            for (MemberRecord r : parseChambresPdf(chambresPdf)) {
                allMembers.putIfAbsent(r.slug(), r);
            }
        } else {
            try (InputStream is = getClass().getResourceAsStream("/" + CHAMBRES_FILE_NAME)) {
                if (is != null) {
                    for (MemberRecord r : parseChambresPdfStream(is)) {
                        allMembers.putIfAbsent(r.slug(), r);
                    }
                }
            }
        }

        // 3. Auditorat PDF
        Path auditPdf = rawDir.resolve(AUDIT_FILE_NAME);
        if (Files.isRegularFile(auditPdf)) {
            for (MemberRecord r : parseAuditoratPdf(auditPdf)) {
                allMembers.putIfAbsent(r.slug(), r);
            }
        } else {
            try (InputStream is = getClass().getResourceAsStream("/" + AUDIT_FILE_NAME)) {
                if (is != null) {
                    for (MemberRecord r : parseAuditoratPdfStream(is)) {
                        allMembers.putIfAbsent(r.slug(), r);
                    }
                }
            }
        }

        if (allMembers.isEmpty()) {
            throw new IllegalStateException("Aucun magistrat du Conseil d'État extrait depuis " + dirOrFile);
        }

        List<MemberRecord> records = new ArrayList<>(allMembers.values());
        for (MemberRecord rec : records) {
            writeRecord(rec, dataset);
        }
        return records;
    }

    static List<MemberRecord> parseMembersHtml(String html) {
        if (html == null || html.isBlank()) return List.of();
        Matcher m = PERSON_CARD_PATTERN.matcher(html);
        List<MemberRecord> list = new ArrayList<>();
        while (m.find()) {
            String imgSrc = m.group(1);
            String rawName = cleanText(m.group(2));
            String function = cleanText(m.group(3));
            if (rawName.length() < 3 || "X".equalsIgnoreCase(rawName)) continue;
            if (!isEligibleJudicialFunction(function)) continue;

            String[] fl = splitName(rawName);
            String displayName = (fl[0].isEmpty() ? "" : fl[0] + " ") + fl[1];
            list.add(new MemberRecord(slugOf(displayName), fl[0], fl[1], displayName.trim(), rawName, function, absolutize(imgSrc)));
        }
        return list;
    }

    static List<MemberRecord> parseChambresPdf(Path pdfPath) {
        try (PDDocument doc = PDDocument.load(pdfPath.toFile())) {
            return extractChambresRecords(doc);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Erreur lecture {}: {}", pdfPath, e.getMessage());
            return List.of();
        }
    }

    static List<MemberRecord> parseChambresPdfStream(InputStream is) {
        try (PDDocument doc = PDDocument.load(is)) {
            return extractChambresRecords(doc);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Erreur lecture stream chambres: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<MemberRecord> extractChambresRecords(PDDocument doc) throws IOException {
        String text = new PDFTextStripper().getText(doc);
        List<MemberRecord> records = new ArrayList<>();
        String currentRole = "Conseiller d'État";

        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.matches("^\\d+$") || line.contains("COMPOSITION")
                    || line.contains("Bruxelles, le") || line.contains("WVV/PVA/GDL")
                    || line.contains("2026-2027") || line.contains("DES AFFAIRES")
                    || line.contains("LANGUE ALLEMANDE")) {
                continue;
            }
            if (line.startsWith("Président") || line.startsWith("Présidents")) {
                currentRole = "Président de chambre";
                String rest = line.replaceFirst("^Pr[ée]sidents?\\s*", "").trim();
                if (!rest.isEmpty()) addChambresPerson(records, rest, currentRole);
                continue;
            }
            if (line.startsWith("Membres")) {
                currentRole = "Conseiller d'État";
                String rest = line.replaceFirst("^Membres\\s*", "").trim();
                if (!rest.isEmpty()) addChambresPerson(records, rest, currentRole);
                continue;
            }
            if (line.startsWith("Suppléant") || line.startsWith("Suppléants")) {
                currentRole = "Conseiller d'État suppléant";
                String rest = line.replaceFirst("^Suppl[ée]ants?\\s*", "").trim();
                if (!rest.isEmpty()) addChambresPerson(records, rest, currentRole);
                continue;
            }
            if (line.startsWith("Assesseurs")) {
                String rest = line.replaceFirst("^Assesseurs\\s*", "").trim();
                for (String part : rest.split(",")) {
                    addChambresPerson(records, part.trim(), "Assesseur");
                }
                continue;
            }
            if (line.startsWith("Greffier") || line.startsWith("A. Section") || line.startsWith("B. Section") || line.contains("chambre")) {
                if (line.startsWith("Greffier")) currentRole = "Greffier";
                continue;
            }
            if ("Greffier".equals(currentRole)) continue;
            if (line.contains("VAN VAERENBERGH")) {
                addChambresPerson(records, "Wilfried Van Vaerenbergh", "Premier Président");
                continue;
            }

            if (line.matches("^(?:M\\.|Mme\\s+)?(?:[A-ZÀ-ÖØ-Þ][a-zà-öø-ÿA-ZÀ-ÖØ-Þ.-]+\\s*)+.*$")
                    && !line.contains("Lundi") && !line.contains("Mardi") && !line.contains("Mercredi")
                    && !line.contains("Jeudi") && !line.contains("Vendredi")) {
                addChambresPerson(records, line, currentRole);
            }
        }
        return records;
    }

    private static void addChambresPerson(List<MemberRecord> records, String rawName, String function) {
        String clean = rawName.replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (clean.length() < 3 || clean.equalsIgnoreCase("N.") || clean.startsWith("Bruxelles") || clean.contains("Greffier")) {
            return;
        }
        String[] fl = splitName(clean);
        String displayName = (fl[0].isEmpty() ? "" : fl[0] + " ") + fl[1];
        records.add(new MemberRecord(slugOf(displayName), fl[0], fl[1], displayName.trim(), clean, function, null));
    }

    static List<MemberRecord> parseAuditoratPdf(Path pdfPath) {
        try (PDDocument doc = PDDocument.load(pdfPath.toFile())) {
            return extractAuditoratRecords(doc);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Erreur lecture {}: {}", pdfPath, e.getMessage());
            return List.of();
        }
    }

    static List<MemberRecord> parseAuditoratPdfStream(InputStream is) {
        try (PDDocument doc = PDDocument.load(is)) {
            return extractAuditoratRecords(doc);
        } catch (Exception e) {
            log.warn("[be_pep_council_of_state] Erreur lecture stream auditorat: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<MemberRecord> extractAuditoratRecords(PDDocument doc) throws IOException {
        String text = new PDFTextStripper().getText(doc);
        List<MemberRecord> records = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            Matcher m = AUDITOR_LINE_PATTERN.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                String role = m.group(2).trim();
                if (name.length() >= 3 && !name.equalsIgnoreCase("N1")) {
                    String[] fl = splitName(name);
                    String displayName = (fl[0].isEmpty() ? "" : fl[0] + " ") + fl[1];
                    records.add(new MemberRecord(slugOf(displayName), fl[0], fl[1], displayName.trim(), name, role, null));
                }
            }
        }
        return records;
    }

    private static String slugOf(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static String[] splitName(String raw) {
        String clean = raw.replaceAll("^(?:M\\.|Mme)\\s+", "").trim().replaceAll("\\s+", " ");
        int lastSpace = clean.lastIndexOf(' ');
        if (lastSpace < 0) {
            return new String[]{"", clean};
        }
        // Ex: "W. Van Vaerenbergh" ou "P. Ronvaux" ou "Chr. Behrendt"
        return new String[]{clean.substring(0, lastSpace).trim(), clean.substring(lastSpace + 1).trim()};
    }

    static boolean isEligibleJudicialFunction(String function) {
        if (function == null || function.isBlank()) return false;
        String lower = function.toLowerCase(Locale.ROOT);
        if (lower.contains("greffier") || lower.contains("administrateur") || lower.contains("directeur")
                || lower.contains("personnel") || lower.contains("budget")) {
            return false;
        }
        return lower.contains("président") || lower.contains("president")
                || lower.contains("auditeur") || lower.contains("conseiller") || lower.contains("assesseur");
    }

    static void writeRecord(MemberRecord rec, FtmDatasetBuilder dataset) {
        String fullPositionLabel = rec.function() + " du Conseil d'État de Belgique";

        FtmEntity person = dataset.newEntity("Person", "member", rec.displayName());
        person.addValue("name", rec.displayName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", fullPositionLabel);
        person.addValue("notes", rec.function() + " – Conseil d'État de Belgique");
        person.addValue("sourceUrl", DEFAULT_URL);

        if (!rec.rawName().equals(rec.displayName())) {
            person.addValue("alias", rec.rawName());
        }
        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), DEFAULT_URL, PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", fullPositionLabel);
        position.addValue("name", fullPositionLabel);
        position.addValue("country", COUNTRY);
        position.addValue("organization", "Conseil d'État de Belgique");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.judicial");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.displayName(), fullPositionLabel);
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("description", fullPositionLabel);
        occ.addValue("sourceUrl", DEFAULT_URL);
        dataset.addEntity(occ);
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&#x27;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String absolutize(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) return null;
        String clean = pathOrUrl.replace("&amp;", "&").trim();
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        return clean.startsWith("/") ? BASE_URL + clean : BASE_URL + "/" + clean;
    }

    public record MemberRecord(
            String slug,
            String firstName,
            String lastName,
            String displayName,
            String rawName,
            String function,
            String photoUrl
    ) {}
}