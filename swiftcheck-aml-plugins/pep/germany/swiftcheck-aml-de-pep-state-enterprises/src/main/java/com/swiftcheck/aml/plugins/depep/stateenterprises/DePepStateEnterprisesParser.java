package com.swiftcheck.aml.plugins.depep.stateenterprises;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
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
 * <b>de_pep_state_enterprises</b> – Dirigeants d'entreprises publiques allemandes (catégorie <b>DE-h</b>).
 * <p>
 * Chaque page de direction est téléchargée à chaque exécution et lue par un extracteur propre à la page
 * ({@link EnterprisePages}). Aucun nom n'est embarqué dans le plugin : une page illisible, ou dont la mise en page a
 * changé, est signalée dans le message d'exécution et ne produit aucune personne.
 */
@Extension
public class DePepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepStateEnterprisesParser.class);

    static final String ID_PREFIX = "de-pep-state-enterprises";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_SOE = "gov.soe";
    static final String CONFIG_RESOURCE = "/state-enterprises.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Entreprises publiques)";
    /** Une page de direction qui livre moins de personnes que cela est considérée comme illisible. */
    static final int MIN_PERSONS = 2;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceConfig(String id, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnterpriseConfig(String key, String name, List<SourceConfig> sources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<EnterpriseConfig> enterprises) {
    }

    /** Résultat de la lecture d'une page de direction. */
    record Outcome(EnterpriseConfig enterprise, SourceConfig source, List<EnterprisePages.Person> persons, String problem) {
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
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC_PEP);
        String message = convert(outcomes, LocalDate.now(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    static Config loadConfig() throws IOException {
        try (var in = DePepStateEnterprisesParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return new ObjectMapper().readValue(in, Config.class);
        }
    }

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (EnterpriseConfig e : config.enterprises()) {
            for (SourceConfig s : e.sources()) {
                var extractor = EnterprisePages.EXTRACTORS.get(e.key() + "/" + s.id());
                if (extractor == null) {
                    out.add(new Outcome(e, s, List.of(), "aucun extracteur pour cette page (page non lisible par le plugin)"));
                    continue;
                }
                try {
                    String html = fetcher.get(s.url());
                    if (rawDir != null) {
                        Files.writeString(rawDir.resolve(e.key() + "-" + s.id() + ".html"), html, StandardCharsets.UTF_8);
                    }
                    List<EnterprisePages.Person> persons = extractor.apply(PageText.lines(html));
                    if (persons.size() < MIN_PERSONS) {
                        out.add(new Outcome(e, s, List.of(), "page lue mais " + persons.size() + " personne(s) reconnue(s), la mise en page a changé"));
                    } else {
                        out.add(new Outcome(e, s, persons, null));
                    }
                } catch (IOException | RuntimeException ex) {
                    log.warn("Page {} / {} non lue : {}", e.key(), s.id(), ex.getMessage());
                    out.add(new Outcome(e, s, List.of(), ex.getMessage()));
                }
            }
        }
        if (out.stream().allMatch(o -> o.persons().isEmpty())) {
            throw new IllegalStateException("Aucune page de direction lisible : "
                    + String.join(" ; ", out.stream().map(o -> o.enterprise().key() + "/" + o.source().id() + " (" + o.problem() + ")").toList()));
        }
        return out;
    }

    static String convert(List<Outcome> outcomes, LocalDate today, FtmDatasetBuilder dataset) {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                failures.add(o.enterprise().name() + " / " + o.source().id() + " (" + o.problem() + ")");
                continue;
            }
            EnterpriseConfig e = o.enterprise();
            FtmEntity company = dataset.newEntity("Company", e.key());
            company.addValue("name", e.name());
            company.addValue("jurisdiction", "de");
            company.addValue("topics", TOPIC_SOE);
            dataset.addEntity(company);
            for (EnterprisePages.Person d : o.persons()) {
                String posKey = e.key() + "-" + o.source().id();
                FtmEntity position = dataset.newEntity("Position", posKey, d.role());
                position.addValue("name", d.role() + " (" + e.name() + ")");
                position.addValue("country", "de");
                position.addValue("organization", e.name());
                position.addValue("topics", "gov.national");
                position.addValue("topics", TOPIC_SOE);
                dataset.addEntity(position);

                FtmEntity person = dataset.newEntity("Person", e.key(), d.cleanName());
                person.addValue("name", d.cleanName());
                person.addValue("title", d.title());
                String[] parts = d.cleanName().split("\\s+", 2);
                person.addValue("firstName", parts.length > 1 ? parts[0] : null);
                person.addValue("lastName", parts.length > 1 ? parts[1] : parts[0]);
                person.addValue("country", "de");
                person.addValue("position", d.role() + " (" + e.name() + ")");
                person.addValue("topics", TOPIC_SOE);
                person.addValue("sourceUrl", o.source().url());
                dataset.addEntity(person);

                FtmEntity occ = dataset.newEntity("Occupancy", e.key(), d.cleanName(), o.source().id(), d.role());
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("startDate", d.start() == null ? null : d.start().toString());
                occ.addValue("status", "current");
                occ.addValue("description", d.role());
                dataset.addEntity(occ);
                persons++;
            }
        }
        String message = persons + " dirigeants lus sur " + (outcomes.size() - failures.size()) + " pages de direction";
        return failures.isEmpty() ? message + "." : message + ". Pages non lues : " + String.join(" ; ", failures) + ".";
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Fetcher http(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Thread.sleep(delayMs);
                    HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                            .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
