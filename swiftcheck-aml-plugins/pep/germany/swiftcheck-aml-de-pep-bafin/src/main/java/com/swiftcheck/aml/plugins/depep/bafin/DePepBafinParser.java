package com.swiftcheck.aml.plugins.depep.bafin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * <b>de_pep_bafin</b> – Direction de la BaFin (Bundesanstalt für Finanzdienstleistungsaufsicht) : le président et les
 * directeurs exécutifs (catégorie <b>DE-f</b>, autorités de supervision financière).
 * <p>
 * Source : page « Direktorium » de bafin.de, un bloc par membre (domaine de surveillance, puis « Nom, Exekutivdirektor(in) »).
 * Le Verwaltungsrat de la BaFin n'est pas lu : la page ne publie pas ses membres sous une forme lisible.
 */
@Extension
public class DePepBafinParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBafinParser.class);

    static final String ID_PREFIX = "de-pep-bafin";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_URL = "https://www.bafin.de/DE/die-bafin/ueber-die-bafin/organisation/direktorium/direktorium_node.html";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - BaFin)";
    static final String ORGANIZATION = "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)";

    private static final Pattern TEASER = Pattern.compile(
            "(?s)<h3 class=\"c-teaser__headline[^\"]*\">\\s*(.*?)\\s*</h3>.*?<div class=\"c-teaser__text\">\\s*<p>(.*?)</p>");
    private static final Pattern ACADEMIC_TITLE = Pattern.compile("^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?)\\s+)+");

    /** Un membre de la direction : le domaine placé sous sa responsabilité est vide pour le président. */
    record Member(String fullName, String title, String cleanName, String role, String area) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String html;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
        } else {
            if (ctx.rawDir() == null) {
                throw new IllegalStateException("Aucun fichier d'entrée ni dossier de données brutes.");
            }
            Files.createDirectories(ctx.rawDir());
            html = download(p.getOrDefault("url", DEFAULT_URL).trim());
            Files.writeString(ctx.rawDir().resolve("direktorium.html"), html, StandardCharsets.UTF_8);
        }
        List<Member> members = members(html);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(members, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(members.size() + " membres du Direktorium de la BaFin extraits.");
    }

    /** Le président et les directeurs exécutifs ; la page change de forme : on échoue plutôt que de produire un résultat partiel. */
    static List<Member> members(String html) {
        Map<String, Member> byName = new LinkedHashMap<>();
        Matcher m = TEASER.matcher(html);
        while (m.find()) {
            String headline = clean(m.group(1));
            String text = clean(m.group(2));
            String role;
            String name;
            if ("Präsident".equals(headline) || "Präsidentin".equals(headline)) {
                role = headline + " der BaFin";
                name = text;
                headline = null;
            } else if (text.contains(",") && text.matches(".*,\\s*Exekutivdirektor(in)?$")) {
                name = text.substring(0, text.lastIndexOf(',')).trim();
                role = text.substring(text.lastIndexOf(',') + 1).trim() + " der BaFin";
            } else {
                continue;
            }
            if (name.isBlank() || name.length() > 60 || name.matches(".*\\d.*")) {
                continue;
            }
            String title = null;
            String cleanName = name;
            Matcher tm = ACADEMIC_TITLE.matcher(name);
            if (tm.find()) {
                title = tm.group().trim();
                cleanName = name.substring(tm.end()).trim();
            }
            byName.putIfAbsent(cleanName, new Member(name, title, cleanName, role, headline));
        }
        List<Member> list = new ArrayList<>(byName.values());
        boolean president = list.stream().anyMatch(x -> x.role().startsWith("Präsident"));
        if (!president || list.size() < 3) {
            throw new IllegalStateException("Page du Direktorium de la BaFin illisible (" + list.size()
                    + " membre(s), président " + (president ? "trouvé" : "absent") + ") : la mise en page a probablement changé.");
        }
        return list;
    }

    private static String clean(String s) {
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " pour " + url);
        }
        log.info("Page du Direktorium lue ({} caractères)", resp.body().length());
        return resp.body();
    }

    static void convert(List<Member> members, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "direktorium");
        position.addValue("name", "Mitglied des Direktoriums der BaFin");
        position.addValue("country", "de");
        position.addValue("organization", ORGANIZATION);
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.financial");
        dataset.addEntity(position);

        for (Member m : members) {
            FtmEntity person = dataset.newEntity("Person", m.cleanName());
            person.addValue("name", m.cleanName());
            person.addValue("title", m.title());
            String[] parts = m.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", m.role());
            person.addValue("sourceUrl", DEFAULT_URL);
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.cleanName(), "direktorium");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.area() == null ? m.role() : m.role() + " – " + m.area());
            dataset.addEntity(occ);
        }
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
