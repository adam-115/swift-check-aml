package com.swiftcheck.aml.plugins.depep.defenceattaches;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_defence_attaches</b> – Attachés de défense (Verteidigungsattachés) des ambassades d'Allemagne, § 1 (12) g)
 * GwG.
 * <p>
 * L'Allemagne ne publie pas la liste de ses attachés de défense. Ils sont lus dans les listes diplomatiques officielles
 * des pays d'accueil (paramètre {@code lists} : tableau JSON {@code host}, {@code publisher}, {@code url}, {@code nameAt},
 * {@code section}, {@code window}). Couverture partielle : seuls les pays dont la liste est publiée et lisible. Un même
 * attaché accrédité dans plusieurs pays n'est publié qu'une fois, avec chaque pays.
 * <p>
 * Autres paramètres : {@code delayMs} (1000).
 */
@Extension
public class DePepDefenceAttachesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceAttachesParser.class);

    static final String ID_PREFIX = "de-pep-defence-attaches";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Attachés de défense)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Accès HTTP binaire, remplaçable dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    /** Résultat de la lecture : attachés (fusionnés par nom) et message d'exécution. */
    record Reading(Map<String, List<DiplomaticLists.Attache>> byPerson, String message) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() != null) {
            Files.createDirectories(ctx.rawDir());
        }
        Reading reading = read(lists(p.get("lists")), http(parseLong(p.get("delayMs"), 1000)), ctx.rawDir());
        if (reading.byPerson().isEmpty()) {
            throw new IllegalStateException("Aucun attaché de défense lu." + reading.message());
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(reading, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(reading.message().trim());
    }

    static List<DiplomaticLists.ListConfig> lists(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Paramètre « lists » manquant dans la configuration du parser de_pep_defence_attaches.");
        }
        try {
            return JSON.readValue(json, new TypeReference<List<DiplomaticLists.ListConfig>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("Paramètre lists illisible : " + e.getMessage(), e);
        }
    }

    /** Lit chaque liste ; une liste illisible ou sans attaché est signalée, les autres sont lues. */
    static Reading read(List<DiplomaticLists.ListConfig> lists, Fetcher web, Path rawDir) {
        Map<String, List<DiplomaticLists.Attache>> byPerson = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int index = 0;
        for (DiplomaticLists.ListConfig list : lists) {
            index++;
            try {
                byte[] content = web.get(list.url());
                if (rawDir != null) {
                    Files.write(rawDir.resolve(String.format("liste-%02d-%s.bin", index, list.host().replaceAll("[^A-Za-z]+", "-"))), content);
                }
                List<DiplomaticLists.Attache> found = DiplomaticLists.attaches(DiplomaticLists.text(content), list);
                if (found.isEmpty()) {
                    problems.add(list.host() + " (ALERT STRUCTURE_CHANGED : aucun attaché de défense allemand lu)");
                }
                for (DiplomaticLists.Attache a : found) {
                    byPerson.computeIfAbsent(key(a.fullName()), k -> new ArrayList<>()).add(a);
                }
                parts.add(list.host() + " " + found.size());
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: liste diplomatique {} non lue : {}", list.host(), e.toString());
                problems.add(list.host() + " (ALERT SOURCE_BROKEN : " + e.getMessage() + ")");
            }
        }
        String message = byPerson.size() + " attachés de défense allemands lus dans " + parts.size() + " listes diplomatiques ("
                + String.join(", ", parts) + ")." + (problems.isEmpty() ? "" : " Listes en échec : " + String.join(" ; ", problems) + ".");
        return new Reading(byPerson, message);
    }

    static String key(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
    }

    static void convert(Reading reading, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "verteidigungsattache");
        position.addValue("name", "Verteidigungsattaché (attaché de défense) d'une ambassade d'Allemagne");
        position.addValue("country", "de");
        position.addValue("organization", "Bundeswehr / Auswärtiges Amt");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "mil");
        dataset.addEntity(position);
        for (List<DiplomaticLists.Attache> seen : reading.byPerson().values()) {
            DiplomaticLists.Attache a = seen.get(0);
            List<String> hosts = seen.stream().map(DiplomaticLists.Attache::host).distinct().toList();
            String role = "Verteidigungsattaché (" + String.join(", ", hosts) + ")";
            FtmEntity person = dataset.newEntity("Person", a.fullName());
            person.addValue("name", a.fullName());
            person.addValue("firstName", a.firstName());
            person.addValue("lastName", a.lastName());
            person.addValue("title", seen.stream().map(DiplomaticLists.Attache::rank).filter(r -> r != null).findFirst().orElse(null));
            person.addValue("country", "de");
            person.addValue("position", role);
            seen.stream().map(DiplomaticLists.Attache::sourceUrl).distinct().forEach(u -> person.addValue("sourceUrl", u));
            person.addValue("notes", "Lu dans la liste diplomatique officielle de : " + String.join(", ", hosts) + " (« " + a.line() + " »).");
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", a.fullName(), "attache");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", role);
            dataset.addEntity(occ);
        }
    }

    static Fetcher http(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    Thread.sleep(delayMs * attempt);
                    HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
                            .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Reading reading = read(lists(p.get("lists")), http(parseLong(p.get("delayMs"), 1000)), null);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (List<DiplomaticLists.Attache> seen : reading.byPerson().values()) {
            DiplomaticLists.Attache a = seen.get(0);
            records.add(new SourceRecord(ids.buildEntityId(a.fullName()), "Person", a.fullName(), "Verteidigungsattaché"));
        }
        return records;
    }
}
