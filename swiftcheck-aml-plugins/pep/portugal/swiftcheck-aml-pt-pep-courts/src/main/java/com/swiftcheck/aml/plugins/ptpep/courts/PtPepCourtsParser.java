package com.swiftcheck.aml.plugins.ptpep.courts;

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
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>pt_pep_courts</b> – Hautes juridictions portugaises (catégorie <b>PT-c</b> ; loi 83/2017, art. 2.º, n.º 1, al. cc) ;
 * liste notifiée, JO C/2023/724).
 *
 * <p>Les pages officielles sont téléchargées à chaque exécution et lues par un extracteur propre à chaque juridiction
 * ({@link CourtPages}). Aucun nom n'est embarqué dans le plugin ; les adresses sont dans {@code courts.json}.
 * Couvert aujourd'hui : le Tribunal de Contas (président, vice-président, juges conseillers). Le Tribunal
 * constitutionnel et la Cour suprême de justice n'ont pas de page de composition lisible sans JavaScript (avril
 * 2026) : ils ne sont pas lus et le message d'exécution ne les compte pas comme couverts. Une juridiction dont la page
 * échoue est citée dans le message ; si aucune n'est lisible, l'exécution s'arrête.
 */
@Extension
public class PtPepCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepCourtsParser.class);

    static final String ID_PREFIX = "pt-pep-courts";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/courts.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Portugal - hautes juridictions)";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtConfig(String key, String name, String acronym, String country, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<CourtConfig> courts) {
    }

    /** Résultat de la lecture d'une juridiction. */
    record Outcome(CourtConfig court, List<CourtPages.Member> members, String problem) {
    }

    /** Accès HTTP remplaçable dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun dossier de données brutes.");
        }
        Files.createDirectories(ctx.rawDir());
        long delay = parseLong(p.get("delayMs"), 1500);
        List<Outcome> outcomes = readAll(loadConfig(), http(delay), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(outcomes, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    static Config loadConfig() throws IOException {
        try (var in = PtPepCourtsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return new ObjectMapper().readValue(in, Config.class);
        }
    }

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (CourtConfig c : config.courts()) {
            var extractor = CourtPages.EXTRACTORS.get(c.key());
            if (extractor == null) {
                out.add(new Outcome(c, List.of(), "aucun extracteur pour cette juridiction"));
                continue;
            }
            try {
                String html = fetcher.get(c.url());
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve(c.key() + ".html"), html, StandardCharsets.UTF_8);
                }
                List<CourtPages.Member> members = extractor.apply(PageText.lines(html));
                out.add(members.isEmpty()
                        ? new Outcome(c, List.of(), "page lue mais aucune personne reconnue, la mise en page a changé")
                        : new Outcome(c, members, null));
            } catch (IOException | RuntimeException e) {
                log.warn("Juridiction {} non lue : {}", c.key(), e.getMessage());
                out.add(new Outcome(c, List.of(), e.getMessage()));
            }
        }
        if (out.stream().allMatch(o -> o.members().isEmpty())) {
            throw new IllegalStateException("Aucune juridiction lisible : "
                    + String.join(" ; ", out.stream().map(o -> o.court().key() + " (" + o.problem() + ")").toList()));
        }
        return out;
    }

    static String convert(List<Outcome> outcomes, FtmDatasetBuilder dataset) {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                failures.add(o.court().acronym() + " (" + o.problem() + ")");
                continue;
            }
            CourtConfig c = o.court();
            FtmEntity position = dataset.newEntity("Position", c.key(), "member");
            position.addValue("name", "Membre du " + c.name());
            position.addValue("country", "pt");
            position.addValue("organization", c.name());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.judicial");
            dataset.addEntity(position);
            for (CourtPages.Member m : o.members()) {
                FtmEntity person = dataset.newEntity("Person", c.key(), m.name());
                person.addValue("name", m.name());
                String[] words = m.name().split("\\s+");
                person.addValue("firstName", words[0]);
                person.addValue("lastName", words.length > 1 ? words[words.length - 1] : null);
                person.addValue("country", "pt");
                person.addValue("position", m.role() + " – " + c.name());
                person.addValue("sourceUrl", c.url());
                dataset.addEntity(person);

                FtmEntity occ = dataset.newEntity("Occupancy", c.key(), m.name(), m.role());
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("status", "current");
                occ.addValue("description", m.role());
                occ.addValue("sourceUrl", c.url());
                dataset.addEntity(occ);
                persons++;
            }
        }
        String message = persons + " personnes lues sur " + (outcomes.size() - failures.size()) + " juridiction(s)";
        return failures.isEmpty() ? message + "." : message + ". Juridictions non lues : " + String.join(" ; ", failures) + ".";
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Fetcher http(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Thread.sleep(delayMs);
                    HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() == 200) {
                        return resp.body();
                    }
                    last = new IOException("HTTP " + resp.statusCode());
                    if (resp.statusCode() < 500 && resp.statusCode() != 429) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrompu", e);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
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
