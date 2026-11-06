package com.swiftcheck.aml.plugins.depep.federalsecretaries;

import java.io.ByteArrayInputStream;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_federal_secretaries</b> – Secrétaires d'État parlementaires, ministres d'État et secrétaires d'État
 * fonctionnaires des ministères fédéraux, ainsi que les anciens ministres sortis depuis moins de 12 mois (catégorie
 * <b>DE-a</b>, § 1 (12) a) GwG).
 * <p>
 * Source : Datenhandbuch du Bundestag, chapitre 6.3 (« Namentliche Aufstellung der Minister und Staatssekretäre »), PDF
 * mis à jour par le service du Bundestag. Les ministres en fonction viennent du plugin du cabinet fédéral : ce plugin ne
 * retient que ceux dont le tableau indique une date de fin.
 */
@Extension
public class DePepFederalSecretariesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalSecretariesParser.class);

    static final String ID_PREFIX = "de-pep-federal-secretaries";
    static final String TOPIC = "role.pep";
    static final String CHAPTER_PAGE = "https://www.bundestag.de/dokumente/parlamentsarchiv/datenhandbuch/06/kapitel-06-475940";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - secrétaires d'État)";
    static final int DEFAULT_TERM = 21;
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final Pattern PDF_LINK = Pattern.compile("href=\"((?:https://www\\.bundestag\\.de)?/resource/blob/\\d+/Kapitel_06_03_[^\"]*\\.pdf)\"");

    /** Résultat de la lecture : lignes retenues et chiffres pour le message. */
    record Report(List<DhbTable.Holder> kept, int current, int ended, int droppedOld, int deceased, int currentMinisters,
                  LocalDate stand) {
        String message(LocalDate today) {
            StringBuilder sb = new StringBuilder();
            sb.append(kept.size()).append(" titulaires retenus (").append(current).append(" en fonction, ").append(ended)
                    .append(" sortis depuis moins de 12 mois) ; ").append(droppedOld).append(" sortis avant, écartés, ")
                    .append(deceased).append(" décédé(s) écarté(s), ").append(currentMinisters)
                    .append(" ministres en fonction laissés au plugin du cabinet.");
            if (stand != null) {
                long days = ChronoUnit.DAYS.between(stand, today);
                sb.append(" Datenhandbuch arrêté au ").append(stand);
                if (days > 60) {
                    sb.append(" (").append(days).append(" jours : les nominations et départs depuis cette date sont à contrôler)");
                }
                sb.append('.');
            }
            return sb.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = intParam(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
        int term = intParam(p, "term", DEFAULT_TERM);
        byte[] pdf;
        String pdfUrl;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            pdf = Files.readAllBytes(ctx.inputFile());
            pdfUrl = "fichier local";
        } else {
            if (ctx.rawDir() == null) {
                throw new IllegalStateException("Aucun fichier d'entrée ni dossier de données brutes.");
            }
            Files.createDirectories(ctx.rawDir());
            pdfUrl = p.getOrDefault("pdfUrl", "").trim();
            if (pdfUrl.isEmpty()) {
                String page = new String(download(p.getOrDefault("chapterUrl", CHAPTER_PAGE).trim()), StandardCharsets.UTF_8);
                Files.writeString(ctx.rawDir().resolve("kapitel-06.html"), page, StandardCharsets.UTF_8);
                pdfUrl = discoverPdfUrl(page);
                if (pdfUrl == null) {
                    throw new IllegalStateException("Lien du chapitre 6.3 introuvable sur la page du Datenhandbuch : la page a changé.");
                }
            }
            pdf = download(pdfUrl);
            Files.write(ctx.rawDir().resolve("kapitel-06-03.pdf"), pdf);
        }
        LocalDate today = LocalDate.now();
        List<DhbTable.Seg> segs = DhbTable.segments(new ByteArrayInputStream(pdf));
        List<DhbTable.Holder> holders = DhbTable.holders(segs, term);
        if (holders.size() < 20) {
            throw new IllegalStateException("Tableau du Datenhandbuch illisible : " + holders.size()
                    + " titulaires pour la " + term + "e législature, la mise en page a probablement changé.");
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(holders, DhbTable.standDate(segs), pdfUrl, today, months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message(today));
    }

    static String discoverPdfUrl(String html) {
        Matcher m = PDF_LINK.matcher(html);
        if (!m.find()) {
            return null;
        }
        String u = m.group(1);
        return u.startsWith("http") ? u : "https://www.bundestag.de" + u;
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        try {
            return Integer.parseInt(p.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " pour " + url);
        }
        log.info("Téléchargé {} ({} octets)", url, resp.body().length);
        return resp.body();
    }

    // ------------------------------------------------------------------ conversion FtM

    static Report convert(List<DhbTable.Holder> holders, LocalDate stand, String sourceUrl, LocalDate today, int months,
                          FtmDatasetBuilder dataset) {
        LocalDate cutoff = today.minusMonths(months);
        List<DhbTable.Holder> kept = new ArrayList<>();
        int current = 0;
        int ended = 0;
        int old = 0;
        int deceased = 0;
        int ministers = 0;
        for (DhbTable.Holder h : holders) {
            if (h.deceased()) {
                deceased++;
                continue;
            }
            boolean isEnded = h.end() != null && !h.end().isAfter(today);
            if (h.kind() == DhbTable.Kind.MINISTER && !isEnded) {
                ministers++; // en fonction : plugin du cabinet fédéral
                continue;
            }
            if (isEnded && h.end().isBefore(cutoff)) {
                old++;
                continue;
            }
            kept.add(h);
            if (isEnded) {
                ended++;
            } else {
                current++;
            }
        }
        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        java.util.Set<String> positions = new java.util.HashSet<>();
        for (DhbTable.Holder h : kept) {
            String organization = organization(h.ministry());
            String positionName = positionName(h);
            String positionKey = organization + "|" + positionName;
            FtmEntity position = dataset.newEntity("Position", organization, positionName);
            if (positions.add(positionKey)) {
                position.addValue("name", positionName + " (" + organization + ")");
                position.addValue("country", "de");
                position.addValue("organization", organization);
                position.addValue("topics", "gov.executive");
                position.addValue("topics", "gov.national");
                dataset.addEntity(position);
            }
            FtmEntity person = persons.get(h.name());
            if (person == null) {
                person = dataset.newEntity("Person", h.name());
                person.addValue("name", h.name());
                String[] parts = h.name().split("\\s+");
                person.addValue("firstName", parts[0]);
                person.addValue("lastName", parts[parts.length - 1]);
                person.addValue("country", "de");
                person.addValue("sourceUrl", sourceUrl);
                persons.put(h.name(), person);
            }
            if (h.party() != null && !h.party().isBlank()) {
                person.addValue("political", h.party());
            }
            person.addValue("position", positionName + " (" + organization + ")");

            FtmEntity occ = dataset.newEntity("Occupancy", h.name(), organization, positionName,
                    h.start() == null ? "" : h.start().toString(), h.end() == null ? "" : h.end().toString());
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("startDate", h.start() == null ? null : h.start().toString());
            occ.addValue("endDate", h.end() == null ? null : h.end().toString());
            occ.addValue("status", h.end() != null && !h.end().isAfter(today) ? "ended" : "current");
            occ.addValue("sourceUrl", sourceUrl);
            dataset.addEntity(occ);
        }
        persons.values().forEach(dataset::addEntity);
        if (kept.isEmpty()) {
            throw new IllegalStateException("Aucun titulaire dans la fenêtre de rétention.");
        }
        return new Report(kept, current, ended, old, deceased, ministers, stand);
    }

    static String organization(String label) {
        String s = label == null ? "" : label.replaceAll("(\\p{L})- (\\p{Ll})", "$1$2").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return "Gouvernement fédéral";
        }
        if (s.startsWith("BM für") || s.startsWith("BM der") || s.startsWith("BM des")) {
            s = "Bundesministerium" + s.substring(2);
        }
        return s.replace("Bundeskanzleramt/ Bundespresseamt", "Bundeskanzleramt");
    }

    static String positionName(DhbTable.Holder h) {
        String role = h.role() == null ? "" : h.role().replaceAll("(\\p{L})- (\\p{Ll})", "$1$2").replaceAll("\\s+", " ").trim();
        if (!role.isEmpty() && !role.startsWith("(")) {
            return role;
        }
        return switch (h.kind()) {
            case MINISTER -> "Bundesminister";
            case PARLIAMENTARY -> "Parlamentarischer Staatssekretär";
            case CIVIL -> "Beamteter Staatssekretär";
        };
    }

    /**
     * Test du parser : relance la lecture de la source vers des dossiers temporaires (la source archivée n'est pas
     * touchée) et renvoie chaque personne avec l'ID et le nom que Yente doit connaître.
     */
    @Override
    public java.util.List<com.swiftcheck.aml.plugin.SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("src-records");
        try {
            java.nio.file.Path out = tmp.resolve("out.ndjson");
            java.nio.file.Path raw = java.nio.file.Files.createDirectories(tmp.resolve("raw"));
            parse(new ParserContext(ctx.datasetName(), ctx.inputFile(), raw, out, ctx.parameters()));
            java.util.regex.Pattern str = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("^\\{\"id\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.List<com.swiftcheck.aml.plugin.SourceRecord> records = new java.util.ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(out, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.contains("\"schema\":\"Person\"")) {
                    continue;
                }
                java.util.regex.Matcher id = idPattern.matcher(line);
                int n = line.indexOf("\"name\":[");
                if (!id.find() || n < 0) {
                    continue;
                }
                java.util.regex.Matcher name = str.matcher(line.substring(n + 7));
                if (name.find()) {
                    records.add(new com.swiftcheck.aml.plugin.SourceRecord(unescape(id.group(1)), "Person",
                            unescape(name.group(1)), null));
                }
            }
            return records;
        } finally {
            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }
}
