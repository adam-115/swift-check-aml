package com.swiftcheck.aml.plugins.ptpep.regulators;

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
 * <b>pt_pep_regulators</b> – Autorités de régulation portugaises (catégorie <b>PT-d</b> ; loi 83/2017, art. 2.º, n.º 1, al. cc) ;
 * liste notifiée, JO C/2023/724).
 *
 * <p>Les pages officielles sont téléchargées à chaque exécution et lues par un extracteur propre à chaque régulateur
 * ({@link RegulatorPages}). Aucun nom n'est embarqué dans le plugin ; les adresses sont dans {@code regulators.json}.
 * Couvert aujourd'hui : le Conseil régulateur de l'ERC (communication sociale). Le Banco de Portugal est protégé par un
 * contrôle anti-robots (non contourné) ; la CMVM, l'ASF et la CNPD n'ont pas de page de composition lisible sans
 * JavaScript (novembre 2026) : ils ne sont pas lus. Un régulateur dont la page échoue est cité dans le message ;
 * si aucun n'est lisible, l'exécution s'arrête.
 */
@Extension
public class PtPepRegulatorsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(PtPepRegulatorsParser.class);

    static final String ID_PREFIX = "pt-pep-regulators";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/regulators.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Portugal - régulateurs)";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegulatorConfig(String key, String name, String acronym, String country, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<RegulatorConfig> regulators) {
    }

    /** Résultat de la lecture d'une juridiction. */
    record Outcome(RegulatorConfig regulator, List<RegulatorPages.Member> members, String problem) {
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
        try (var in = PtPepRegulatorsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return new ObjectMapper().readValue(in, Config.class);
        }
    }

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (RegulatorConfig c : config.regulators()) {
            var extractor = RegulatorPages.EXTRACTORS.get(c.key());
            if (extractor == null) {
                out.add(new Outcome(c, List.of(), "aucun extracteur pour ce régulateur"));
                continue;
            }
            try {
                String html = fetcher.get(c.url());
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve(c.key() + ".html"), html, StandardCharsets.UTF_8);
                }
                List<RegulatorPages.Member> members = extractor.apply(PageText.lines(html));
                out.add(members.isEmpty()
                        ? new Outcome(c, List.of(), "page lue mais aucune personne reconnue, la mise en page a changé")
                        : new Outcome(c, members, null));
            } catch (IOException | RuntimeException e) {
                log.warn("Régulateur {} non lu : {}", c.key(), e.getMessage());
                out.add(new Outcome(c, List.of(), e.getMessage()));
            }
        }
        if (out.stream().allMatch(o -> o.members().isEmpty())) {
            throw new IllegalStateException("Aucune régulateur lisible : "
                    + String.join(" ; ", out.stream().map(o -> o.regulator().key() + " (" + o.problem() + ")").toList()));
        }
        return out;
    }

    static String convert(List<Outcome> outcomes, FtmDatasetBuilder dataset) {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                failures.add(o.regulator().acronym() + " (" + o.problem() + ")");
                continue;
            }
            RegulatorConfig c = o.regulator();
            FtmEntity position = dataset.newEntity("Position", c.key(), "member");
            position.addValue("name", "Membre du collège de régulation – " + c.name());
            position.addValue("country", "pt");
            position.addValue("organization", c.name());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.igo");
            dataset.addEntity(position);
            for (RegulatorPages.Member m : o.members()) {
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
        String message = persons + " personnes lues sur " + (outcomes.size() - failures.size()) + " régulateur(s)";
        return failures.isEmpty() ? message + "." : message + ". Régulateurs non lus : " + String.join(" ; ", failures) + ".";
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
}
