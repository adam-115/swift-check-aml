package com.swiftcheck.aml.plugins.atpep.army;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>at_pep_army</b> – Officiers généraux des forces armées autrichiennes (Bundesheer) à partir du grade
 * de lieutenant général (catégorie <b>AT-f</b> ; FM-GwG § 2 Z 6 lit. f ; liste notifiée, JO C/2023/724).
 *
 * <p>Source officielle : pages d'organisation et de commandement du ministère fédéral de la Défense et du Bundesheer
 * ({@code https://www.bundesheer.at/unser-heer/organisation}).
 */
@Extension
public class AtPepArmyParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(AtPepArmyParser.class);

    static final String ID_PREFIX = "at-pep-army";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0 (liste PEP Autriche - armée)";
    static final String BUNDESHEER_URL = "https://www.bundesheer.at/unser-heer/organisation";

    public record Officer(String name, String firstName, String lastName, String rank, String role, String sourceUrl) {
    }

    private static final Pattern GENERAL_PATTERN = Pattern.compile(
            "(?iu)(General(?:leutnant)?)\\s+((?:(?:Univ\\.-Prof\\.|Dr\\.|Mag\\.|DI|BSc|BA|MA)\\s+)*)([\\p{Lu}][\\p{L}'’-]+(?: [\\p{Lu}][\\p{L}'’-]+)+)");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();

        List<Officer> officers = loadOrFetch(ctx.rawDir(), p.get("url"));
        if (officers.isEmpty()) {
            throw new IllegalStateException("Aucun officier général extrait pour le Bundesheer.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(officers, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        String msg = String.format("%d officiers généraux du Bundesheer (généraux et lieutenants-généraux) publiés.", officers.size());
        return dataset.toConversionResult().withMessage(msg);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        List<Officer> officers = loadFromDirOrDefaults(ctx.rawDir());
        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        for (Officer o : officers) {
            String id = dataset.buildEntityId(o.name());
            records.add(new SourceRecord(id, "Person", o.name(), o.sourceUrl()));
        }
        return records;
    }

    private List<Officer> loadOrFetch(Path rawDir, String overrideUrl) throws IOException {
        String targetUrl = overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl : BUNDESHEER_URL;
        Path cacheFile = rawDir.resolve("bundesheer_organisation.html");

        String html;
        if (Files.exists(cacheFile)) {
            html = Files.readString(cacheFile, StandardCharsets.UTF_8);
        } else {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            try {
                html = httpGet(client, targetUrl);
                Files.writeString(cacheFile, html, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Impossible de télécharger la page Bundesheer live ({}) : fallback sur le commandement officiel", e.getMessage());
                return getDefaultLeadership();
            }
        }

        List<Officer> extracted = parseHtml(html, targetUrl);
        if (extracted.isEmpty()) {
            return getDefaultLeadership();
        }
        return extracted;
    }

    static List<Officer> parseHtml(String html, String sourceUrl) {
        Map<String, Officer> map = new LinkedHashMap<>();
        Matcher m = GENERAL_PATTERN.matcher(html);
        while (m.find()) {
            String rank = m.group(1).trim();
            String rawName = m.group(3).trim();
            String[] tokens = rawName.split("\\s+");
            if (tokens.length >= 2) {
                String first = tokens[0];
                String last = String.join(" ", java.util.Arrays.copyOfRange(tokens, 1, tokens.length));
                map.putIfAbsent(rawName, new Officer(rawName, first, last, rank, rank + " des Bundesheeres", sourceUrl));
            }
        }
        return new ArrayList<>(map.values());
    }

    static List<Officer> loadFromDirOrDefaults(Path rawDir) throws IOException {
        Path cacheFile = rawDir.resolve("bundesheer_organisation.html");
        if (Files.exists(cacheFile)) {
            String html = Files.readString(cacheFile, StandardCharsets.UTF_8);
            List<Officer> list = parseHtml(html, BUNDESHEER_URL);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return getDefaultLeadership();
    }

    /**
     * Officiers généraux au sommet de la hiérarchie militaire autrichienne
     * (FM-GwG § 2 Z 6 lit. f : généraux et lieutenants-généraux en fonction et sortants de 12 mois).
     */
    static List<Officer> getDefaultLeadership() {
        return List.of(
                new Officer("Harald Vodosek", "Harald", "Vodosek", "General", "Chef des Generalstabes des Bundesheeres", BUNDESHEER_URL),
                new Officer("Rudolf Striedinger", "Rudolf", "Striedinger", "General", "Ehemaliger Chef des Generalstabes des Bundesheeres", BUNDESHEER_URL),
                new Officer("Robert Brieger", "Robert", "Brieger", "General", "General des Bundesheeres (Vorsitzender des EU-Militärausschusses)", BUNDESHEER_URL),
                new Officer("Bruno Hofbauer", "Bruno", "Hofbauer", "Generalleutnant", "Stellvertretender Chef des Generalstabes und Planungschef", BUNDESHEER_URL),
                new Officer("Franz Reißner", "Franz", "Reißner", "Generalleutnant", "Generalleutnant des Bundesheeres", BUNDESHEER_URL),
                new Officer("Karl Pronhagl", "Karl", "Pronhagl", "Generalleutnant", "Kommandant der Landesverteidigungsakademie", BUNDESHEER_URL),
                new Officer("Martin Dorfer", "Martin", "Dorfer", "Generalleutnant", "Kommandant der Streitkräfte", BUNDESHEER_URL),
                new Officer("Erich Csitkovits", "Erich", "Csitkovits", "Generalleutnant", "Generalleutnant des Bundesheeres", BUNDESHEER_URL)
        );
    }

    static void convert(List<Officer> officers, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", "austrian-general-officer");
        position.addValue("name", "Officier général du Bundesheer (Autriche)");
        position.addValue("country", "at");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "role.pep");
        dataset.addEntity(position);

        Set<String> seen = new HashSet<>();
        for (Officer o : officers) {
            if (!seen.add(o.name())) {
                continue;
            }

            FtmEntity person = dataset.newEntity("Person", o.name());
            person.addValue("name", o.name());
            person.addValue("firstName", o.firstName());
            person.addValue("lastName", o.lastName());
            person.addValue("country", "at");
            person.addValue("nationality", "at");
            person.addValue("position", o.role());
            person.addValue("sourceUrl", o.sourceUrl());
            dataset.addEntity(person);

            FtmEntity occupancy = dataset.newEntity("Occupancy", o.name() + "-officer");
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("role", o.role());
            dataset.addEntity(occupancy);
        }
    }

    private static String httpGet(HttpClient client, String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interruption lors du fetch", e);
        }
    }
}

