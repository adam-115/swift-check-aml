package com.swiftcheck.aml.plugins.itpep.institutions;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <b>it_pep_high_courts</b> et <b>it_pep_audit_and_central_bank</b> – hautes juridictions, Cour des comptes fédérale et
 * directoire de la Banque nationale d'Italie (catégories <b>IT-1.5</b> et <b>IT-1.6</b> ; FM-GwG § 2 Z 6 lit. d et e ;
 * liste notifiée, JO C/2023/724).
 *
 * <p>Un seul plugin pour les deux parsers : le paramètre {@code keys} choisit les institutions lues
 * ({@code vfgh,vfgh_substitutes,ogh,vwgh} pour les hautes cours ; {@code oenb,rechnungshof} pour la Cour des comptes et la
 * banque nationale ; toutes si le paramètre est absent). Les pages officielles sont téléchargées à chaque exécution et
 * lues par un extracteur propre à chaque site ({@link InstitutionPages}). Aucun nom n'est embarqué dans le plugin ; les
 * adresses sont dans {@code institutions.json}. Une institution dont la page échoue ou dont la mise en page a changé est
 * citée dans le message d'exécution ; si aucune n'est lisible, l'exécution s'arrête.
 */
@Extension
public class ItPepInstitutionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(ItPepInstitutionsParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "it-pep-institutions";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/institutions.json";
    /** Les titulaires dont la fonction a pris fin avant cette date (12 mois) ne sont pas publiés. */
    static final String OLDEST_END = java.time.LocalDate.now().minusMonths(12).toString();
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - hautes cours et institutions)";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstitutionConfig(String key, String name, String acronym, String category, List<String> topics,
            String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<InstitutionConfig> institutions) {
    }

    /** Résultat de la lecture d'une institution. */
    record Outcome(InstitutionConfig institution, List<InstitutionPages.Member> members, String problem) {
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
        List<Outcome> outcomes = readAll(select(loadConfig(), p.get("keys")), http(delay), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(outcomes, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    static Config loadConfig() throws IOException {
        try (var in = ItPepInstitutionsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return new ObjectMapper().readValue(in, Config.class);
        }
    }

    /** Garde les institutions de la liste {@code keys} (séparées par des virgules) ; toutes si elle est vide. */
    static Config select(Config config, String keys) {
        if (keys == null || keys.isBlank()) {
            return config;
        }
        Set<String> wanted = Arrays.stream(keys.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        List<InstitutionConfig> kept = config.institutions().stream().filter(i -> wanted.contains(i.key())).toList();
        if (kept.size() != wanted.size()) {
            Set<String> known = config.institutions().stream().map(InstitutionConfig::key).collect(Collectors.toSet());
            throw new IllegalArgumentException("Institution inconnue dans « keys » : "
                    + wanted.stream().filter(k -> !known.contains(k)).sorted().toList() + ". Valeurs possibles : "
                    + known.stream().sorted().toList() + ".");
        }
        return new Config(kept);
    }

    static List<Outcome> readAll(Config config, Fetcher fetcher, Path rawDir) throws IOException {
        List<Outcome> out = new ArrayList<>();
        for (InstitutionConfig c : config.institutions()) {
            var extractor = InstitutionPages.EXTRACTORS.get(c.key());
            if (extractor == null) {
                out.add(new Outcome(c, List.of(), "aucun extracteur pour cette institution"));
                continue;
            }
            try {
                String html = fetcher.get(c.url());
                if (rawDir != null) {
                    Files.writeString(rawDir.resolve(c.key() + ".html"), html, StandardCharsets.UTF_8);
                }
                List<InstitutionPages.Member> members = extractor.apply(html);
                out.add(members.isEmpty()
                        ? new Outcome(c, List.of(), "page lue mais aucune personne reconnue, la mise en page a changé")
                        : new Outcome(c, members, null));
            } catch (IOException | RuntimeException e) {
                log.warn("Institution {} non lue : {}", c.key(), e.getMessage());
                out.add(new Outcome(c, List.of(), e.getMessage()));
            }
        }
        if (out.stream().allMatch(o -> o.members().isEmpty())) {
            throw new IllegalStateException("Aucune institution lisible : "
                    + String.join(" ; ", out.stream().map(o -> o.institution().key() + " (" + o.problem() + ")").toList()));
        }
        return out;
    }

    static String convert(List<Outcome> outcomes, FtmDatasetBuilder dataset) {
        int persons = 0;
        List<String> failures = new ArrayList<>();
        for (Outcome o : outcomes) {
            if (o.problem() != null) {
                failures.add(o.institution().acronym() + " (" + o.problem() + ")");
                continue;
            }
            InstitutionConfig c = o.institution();
            FtmEntity position = dataset.newEntity("Position", c.key(), "member");
            position.addValue("name", "Membre : " + c.name());
            position.addValue("country", "it");
            position.addValue("organization", c.name());
            position.addValue("topics", "gov.national");
            for (String topic : c.topics() == null ? List.<String>of() : c.topics()) {
                position.addValue("topics", topic);
            }
            dataset.addEntity(position);
            for (InstitutionPages.Member m : o.members()) {
                if (m.end() != null && m.end().compareTo(OLDEST_END) < 0) {
                    continue;                       // fin de fonctions il y a plus de 12 mois : hors périmètre
                }
                FtmEntity person = dataset.newEntity("Person", c.key(), m.name());
                person.addValue("name", m.name());
                person.addValue("firstName", m.firstName());
                person.addValue("lastName", m.lastName());
                person.addValue("country", "it");
                person.addValue("position", m.role() + " – " + c.name());
                person.addValue("sourceUrl", c.url());
                dataset.addEntity(person);
                FtmEntity occ = dataset.newEntity("Occupancy", c.key(), m.name(), m.role());
                occ.addValue("holder", person.getId());
                occ.addValue("post", position.getId());
                occ.addValue("status", m.end() == null ? "current" : "ended");
                occ.addValue("startDate", m.start());
                occ.addValue("endDate", m.end());
                occ.addValue("description", m.role());
                occ.addValue("sourceUrl", c.url());
                dataset.addEntity(occ);
                persons++;
            }
        }
        String message = persons + " personnes lues sur " + (outcomes.size() - failures.size()) + " institution(s)";
        return failures.isEmpty() ? message + "." : message + ". Institutions non lues : " + String.join(" ; ", failures) + ".";
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
                    try {
                        return com.swiftcheck.aml.sdk.StealthBrowserFetcher.fetchHtml(url);
                    } catch (Exception ex) {
                        last = new IOException("HTTP " + resp.statusCode() + " (" + ex.getMessage() + ")");
                    }
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
