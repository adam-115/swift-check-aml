package com.swiftcheck.aml.plugins.depep.localexecutives;

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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_local_executives</b> – Maires (Oberbürgermeister) des villes allemandes d'au moins 50 000 habitants et chefs
 * de l'exécutif des 294 Landkreise (Landräte).
 * <p>
 * Catégorie <b>DE-AMLR</b> (Règlement UE 2024/1624, applicable le 10 juillet 2027). Sources officielles uniquement :
 * <ul>
 * <li>Landräte : page « Landkreise und Landräte » du Deutscher Landkreistag (paramètre {@code landraeteUrl}) ;</li>
 * <li>population des communes : répertoire des communes de Destatis (paramètre {@code populationUrl}) ;</li>
 * <li>maires : listes publiées par les Länder (paramètre {@code mayorSources}, tableau JSON : {@code land}, {@code publisher},
 * {@code url}, {@code format} « xlsx », {@code sheet}, {@code agsPrefix}, {@code columns}, {@code nameOrder},
 * {@code roles}). Les villes d'au moins {@code minPopulation} habitants d'un Land sans liste lue sont signalées dans le
 * message d'exécution.</li>
 * </ul>
 * Autres paramètres : {@code minPopulation} (50 000), {@code delayMs} (1000), {@code landratRoles} (tableau JSON
 * Landkreis vers titre, pour « Region Hannover » ou « StädteRegion Aachen »), {@code crawl} ({@code false} pour ne lire
 * qu'un fichier d'entrée). Un fichier d'entrée (lignes « Ville | type | population | fonction | nom | parti », ou JSON)
 * remplace la lecture en ligne.
 */
@Extension
public class DePepLocalExecutivesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLocalExecutivesParser.class);

    static final String ID_PREFIX = "de-pep-local-exec";
    static final String TOPIC = "role.pep";
    static final long DEFAULT_MIN_POPULATION = 50_000L;
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Exécutifs locaux)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern ACADEMIC_TITLE = Pattern.compile("^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    /** Format ligne : « Köln | KREISFREIE_STADT | 1084831 | Oberbürgermeisterin | Henriette Reker | parteilos ». */
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*(\\d+)\\s*[|:;]\\s*([^|:;]+?)\\s*[|:;]\\s*([^|:;]+?)(?:\\s*[|:;]\\s*(.*))?$",
            Pattern.CASE_INSENSITIVE);

    record LocalExecutive(String entityName, String type, long population, String role,
                          String fullName, String title, String cleanName, String party) {
    }

    /** Accès HTTP binaire, remplaçable dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException;
    }

    /** Résultat de la lecture en ligne : exécutifs et partie du message d'exécution. */
    record Reading(List<OfficialLists.Executive> executives, String message) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long minPop = parseLong(p.get("minPopulation"), DEFAULT_MIN_POPULATION);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message;
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            List<LocalExecutive> executives = parseContent(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), minPop);
            if (executives.isEmpty()) {
                throw new IllegalStateException("Aucun exécutif local (Oberbürgermeister / Landrat) extrait du fichier fourni.");
            }
            convert(executives, dataset);
            message = executives.size() + " maires et Landräte de collectivités >= " + minPop + " hab. extraits.";
        } else {
            if ("false".equalsIgnoreCase(p.get("crawl"))) {
                throw new IllegalStateException("Aucun fichier d'entrée et lecture en ligne désactivée (crawl=false).");
            }
            if (ctx.rawDir() != null) {
                Files.createDirectories(ctx.rawDir());
            }
            Reading reading = read(p, http(parseLong(p.get("delayMs"), 1000)), ctx.rawDir());
            if (reading.executives().isEmpty()) {
                throw new IllegalStateException("Aucun exécutif local lu :" + reading.message());
            }
            convertOfficial(reading.executives(), dataset);
            message = reading.message().trim();
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    // ------------------------------------------------------------------ lecture des listes officielles

    /**
     * Lit les Landräte, la population des communes et les listes de maires des Länder. Une source illisible est
     * signalée dans le message sans arrêter les autres.
     */
    static Reading read(Map<String, String> p, Fetcher web, Path rawDir) {
        long minPop = parseLong(p.get("minPopulation"), DEFAULT_MIN_POPULATION);
        List<OfficialLists.Executive> out = new ArrayList<>();
        StringBuilder message = new StringBuilder();

        String landraeteUrl = p.get("landraeteUrl");
        if (landraeteUrl == null || landraeteUrl.isBlank()) {
            message.append(" ALERT : paramètre landraeteUrl absent, Landräte non lus.");
        } else {
            try {
                byte[] html = web.get(landraeteUrl);
                save(rawDir, "landraete.html", html);
                List<OfficialLists.Executive> landraete = OfficialLists.landraete(new String(html, StandardCharsets.UTF_8), landraeteUrl,
                        jsonMap(p.get("landratRoles")));
                out.addAll(landraete);
                message.append(" ").append(landraete.size()).append(" Landräte lus (Deutscher Landkreistag)")
                        .append(landraete.size() < 290 ? " ALERT STRUCTURE_CHANGED : moins de 290 Landkreise lus." : ".");
            } catch (IOException | RuntimeException e) {
                log.warn("SOURCE_BROKEN: Landräte non lus : {}", e.toString());
                message.append(" ALERT SOURCE_BROKEN : Landräte non lus (").append(e.getMessage()).append(").");
            }
        }

        Map<String, OfficialLists.Municipality> municipalities = Map.of();
        String populationUrl = p.get("populationUrl");
        try {
            if (populationUrl == null || populationUrl.isBlank()) {
                throw new IOException("paramètre populationUrl absent");
            }
            byte[] xlsx = web.get(populationUrl);
            save(rawDir, "gemeindeverzeichnis.xlsx", xlsx);
            municipalities = OfficialLists.municipalities(xlsx);
        } catch (IOException | RuntimeException e) {
            log.warn("SOURCE_BROKEN: répertoire des communes non lu : {}", e.toString());
            message.append(" ALERT SOURCE_BROKEN : population des communes non lue (").append(e.getMessage())
                    .append("), maires non publiés.");
        }

        if (!municipalities.isEmpty()) {
            Map<String, OfficialLists.Municipality> bigCities = new LinkedHashMap<>();
            municipalities.values().stream().filter(m -> m.population() >= minPop).forEach(m -> bigCities.put(m.ags(), m));
            List<OfficialLists.MayorSource> sources = mayorSources(p.get("mayorSources"));
            Map<String, Integer> perLand = new TreeMap<>();
            List<String> covered = new ArrayList<>();
            int index = 0;
            for (OfficialLists.MayorSource s : sources) {
                index++;
                try {
                    List<OfficialLists.Executive> read;
                    if ("perCity".equals(s.format())) {
                        read = perCity(s, bigCities, web, rawDir, index, message);
                    } else if ("xlsx".equals(s.format())) {
                        byte[] content = web.get(s.url());
                        save(rawDir, String.format("maires-%02d-%s.xlsx", index, s.land()), content);
                        read = OfficialLists.mayorsFromXlsx(content, s);
                    } else {
                        throw new IOException("format « " + s.format() + " » non pris en charge");
                    }
                    int kept = 0;
                    for (OfficialLists.Executive e : read) {
                        OfficialLists.Municipality m = bigCities.get(e.key());
                        if (m == null) {
                            continue;
                        }
                        out.add(new OfficialLists.Executive(e.land(), e.key(), m.shortName(), "Stadt", m.population(), e.role(),
                                e.firstName(), e.lastName(), e.title(), e.gender(), e.birthYear(), e.party(), e.start(), e.sourceUrl()));
                        kept++;
                    }
                    perLand.merge(s.land(), kept, Integer::sum);
                    covered.add(s.land());
                } catch (IOException | RuntimeException e) {
                    log.warn("SOURCE_BROKEN: liste des maires {} non lue : {}", s.land(), e.toString());
                    message.append(" ALERT SOURCE_BROKEN : liste des maires ").append(s.land()).append(" non lue (")
                            .append(e.getMessage()).append(").");
                }
            }
            int mayors = perLand.values().stream().mapToInt(Integer::intValue).sum();
            message.append(" ").append(mayors).append(" maires de villes d'au moins ").append(minPop).append(" hab. lus")
                    .append(perLand.isEmpty() ? "" : " (" + String.join(", ", perLand.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()) + ")")
                    .append(".");
            Map<String, Integer> missing = new TreeMap<>();
            Map<String, OfficialLists.Municipality> uncovered = new LinkedHashMap<>();
            java.util.Set<String> found = new java.util.HashSet<>();
            out.forEach(e -> found.add(e.key()));
            for (OfficialLists.Municipality m : bigCities.values()) {
                // Berlin, Hambourg et Brême : chefs de gouvernement du Land (dataset de_pep_land_governments)
                if (List.of("BE", "HH", "HB").contains(m.land()) || found.contains(m.ags())) {
                    continue;
                }
                // ville d'un Land sans liste, ou absente de la liste de son Land (Gütersloh, sans élection en 2025)
                uncovered.put(m.ags(), m);
                if (!covered.contains(m.land())) {
                    missing.merge(m.land(), 1, Integer::sum);
                }
            }
            if (!missing.isEmpty()) {
                message.append(" Villes d'au moins ").append(minPop).append(" hab. sans liste officielle de maires lue : ")
                        .append(missing.values().stream().mapToInt(Integer::intValue).sum()).append(" (")
                        .append(String.join(", ", missing.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList())).append(").");
            }
            if (!uncovered.isEmpty()) {
                if (!"false".equalsIgnoreCase(p.get("wikidataFallback"))) {
                    out.addAll(wikidataComplement(p, uncovered, web, rawDir, message));
                }
            }
        }
        return new Reading(out, message.toString());
    }

    /**
     * Complément Wikidata pour les villes des Länder sans liste officielle lue (désactivé par {@code wikidataFallback=false}).
     * Une requête en échec est signalée : les sources officielles restent publiées.
     */
    static List<OfficialLists.Executive> wikidataComplement(Map<String, String> p, Map<String, OfficialLists.Municipality> uncovered,
                                                            Fetcher web, Path rawDir, StringBuilder message) {
        try {
            String endpoint = p.getOrDefault("endpoint", WikidataMayors.DEFAULT_ENDPOINT).trim();
            byte[] json = web.get(WikidataMayors.queryUrl(endpoint, uncovered.keySet()));
            save(rawDir, "wikidata-maires.json", json);
            List<String> ambiguous = new ArrayList<>();
            Map<String, OfficialLists.Executive> found = WikidataMayors.parse(new String(json, StandardCharsets.UTF_8), uncovered, ambiguous);
            message.append(" Complément Wikidata (à confirmer) : ").append(found.size()).append(" maires sur ").append(uncovered.size())
                    .append(" villes").append(ambiguous.isEmpty() ? "" : " ; plusieurs titulaires possibles, non publiés : "
                            + String.join(", ", ambiguous)).append(".");
            return new ArrayList<>(found.values());
        } catch (IOException | RuntimeException e) {
            log.warn("Complément Wikidata non lu : {}", e.toString());
            message.append(" ALERT SOURCE_BROKEN : complément Wikidata non lu (").append(e.getMessage()).append(").");
            return List.of();
        }
    }

    /**
     * Source « un fichier par ville » (résultats officiels d'élection de la NRW) : l'adresse est construite à partir de la
     * clé AGS de chaque ville d'au moins {@code minPopulation} habitants du Land ({@code {ags8}}, ou {@code {ags6}} sans le
     * code du Land). Une ville illisible est signalée, les autres sont lues.
     */
    static List<OfficialLists.Executive> perCity(OfficialLists.MayorSource s, Map<String, OfficialLists.Municipality> bigCities,
                                                 Fetcher web, Path rawDir, int index, StringBuilder message) throws IOException {
        List<OfficialLists.Executive> out = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (OfficialLists.Municipality m : bigCities.values()) {
            if (!s.land().equals(m.land())) {
                continue;
            }
            // Aix-la-Chapelle garde dans les résultats de la NRW son ancienne clé de ville sans Kreis (313000)
            String ags6 = s.keyAliases() != null && s.keyAliases().containsKey(m.ags()) ? s.keyAliases().get(m.ags()) : m.ags().substring(2);
            String url = s.url().replace("{ags8}", m.ags()).replace("{ags6}", ags6);
            try {
                byte[] content = web.get(url);
                save(rawDir, String.format("maires-%02d-%s-%s.txt", index, s.land(), m.ags()), content);
                OfficialLists.Executive e = OfficialLists.electedMayor(new String(content, StandardCharsets.UTF_8), m.ags(), s, url);
                if (e == null) {
                    failed.add(m.shortName() + " (élu introuvable)");
                } else {
                    out.add(e);
                }
            } catch (IOException | RuntimeException e) {
                failed.add(m.shortName() + " (" + e.getMessage() + ")");
            }
        }
        if (!failed.isEmpty()) {
            message.append(" ALERT : maires ").append(s.land()).append(" non lus pour ").append(String.join(", ", failed)).append(".");
        }
        if (out.isEmpty()) {
            throw new IOException("aucun élu lu");
        }
        return out;
    }

    static List<OfficialLists.MayorSource> mayorSources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<OfficialLists.MayorSource>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("Paramètre mayorSources illisible : " + e.getMessage(), e);
        }
    }

    private static Map<String, String> jsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (IOException e) {
            throw new IllegalArgumentException("Paramètre landratRoles illisible : " + e.getMessage(), e);
        }
    }

    private static void save(Path rawDir, String name, byte[] content) throws IOException {
        if (rawDir != null) {
            Files.write(rawDir.resolve(name), content);
        }
    }

    static Fetcher http(long delayMs) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Thread.sleep(delayMs * attempt);
                    HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                            .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200) {
                        return resp.body();
                    }
                    last = new IOException("HTTP " + resp.statusCode() + " pour " + url);
                    if (resp.statusCode() != 429 && resp.statusCode() < 500) {
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

    // ------------------------------------------------------------------ publication

    /** Publie les exécutifs lus en ligne. */
    static void convertOfficial(List<OfficialLists.Executive> executives, FtmDatasetBuilder dataset) {
        for (OfficialLists.Executive e : executives) {
            String place = e.place();
            String posKey = place.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase() + "-exec";
            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", e.role() + " (" + place + ")");
            position.addValue("country", "de");
            position.addValue("organization", place);
            position.addValue("topics", "gov.muni");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", place, e.fullName());
            person.addValue("name", e.fullName());
            person.addValue("title", e.title());
            person.addValue("firstName", e.firstName());
            person.addValue("lastName", e.lastName());
            person.addValue("gender", e.gender());
            person.addValue("birthDate", e.birthYear());
            person.addValue("country", "de");
            if (e.party() != null && !e.party().isBlank()) {
                person.addValue("political", e.party());
            }
            person.addValue("position", e.role() + " (" + place + ")");
            person.addValue("sourceUrl", e.sourceUrl());
            person.addValue("notes", e.note());
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", place, e.fullName(), "exec");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("startDate", e.start());
            occ.addValue("description", e.role() + " von " + place + (e.population() > 0 ? " (Pop: " + e.population() + ")" : ""));
            occ.addValue("sourceUrl", e.sourceUrl());
            dataset.addEntity(occ);
        }
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        long minPop = parseLong(p.get("minPopulation"), DEFAULT_MIN_POPULATION);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            for (LocalExecutive e : parseContent(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), minPop)) {
                records.add(new SourceRecord(ids.buildEntityId(e.entityName(), e.cleanName()), "Person", e.fullName(),
                        e.entityName() + " (" + e.role() + ")"));
            }
            return records;
        }
        for (OfficialLists.Executive e : read(p, http(parseLong(p.get("delayMs"), 1000)), null).executives()) {
            records.add(new SourceRecord(ids.buildEntityId(e.place(), e.fullName()), "Person", e.fullName(), e.place() + " (" + e.role() + ")"));
        }
        return records;
    }

    // ------------------------------------------------------------------ fichier d'entrée

    static List<LocalExecutive> parseContent(String content, long minPopulation) {
        List<LocalExecutive> list = new ArrayList<>();
        String trimmed = content.trim();

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                JsonNode root = JSON.readTree(content);
                JsonNode items = root.isArray() ? root : root.path("data");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String name = text(item, "name");
                        String entity = text(item, "city");
                        String type = text(item, "type");
                        String role = text(item, "role");
                        long pop = item.path("population").asLong(0);
                        if (name == null || entity == null || (pop > 0 && pop < minPopulation)) {
                            continue;
                        }
                        if (role == null) {
                            role = type != null && type.toLowerCase().contains("kreis") ? "Landrat" : "Oberbürgermeister";
                        }
                        addExecutive(list, entity, type, pop, role, name, text(item, "party"));
                    }
                    return list;
                }
            } catch (IOException e) {
                log.debug("Format JSON non parsable : {}", e.getMessage());
            }
        }

        for (String line : content.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            Matcher m = LINE_PATTERN.matcher(l);
            if (m.find()) {
                long pop = Long.parseLong(m.group(3).trim());
                if (pop >= minPopulation) {
                    addExecutive(list, m.group(1).trim(), m.group(2).trim(), pop, m.group(4).trim(), m.group(5).trim(),
                            m.group(6) != null ? m.group(6).trim() : null);
                }
            }
        }
        return list;
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static void addExecutive(List<LocalExecutive> list, String entity, String type, long pop,
                                     String role, String rawName, String party) {
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new LocalExecutive(entity, type, pop, role, rawName, title, cleanName, party));
    }

    private void convert(List<LocalExecutive> executives, FtmDatasetBuilder dataset) {
        for (LocalExecutive e : executives) {
            String posKey = e.entityName().replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase() + "-exec";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", e.role() + " (" + e.entityName() + ")");
            position.addValue("country", "de");
            position.addValue("organization", e.entityName());
            position.addValue("topics", "gov.muni");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", e.entityName(), e.cleanName());
            person.addValue("name", e.cleanName());
            person.addValue("title", e.title());
            String[] parts = e.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            if (e.party() != null && !e.party().isBlank()) {
                person.addValue("political", e.party());
            }
            person.addValue("position", e.role() + " (" + e.entityName() + ")");
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", e.entityName(), e.cleanName(), "exec");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", e.role() + " von " + e.entityName() + " (Pop: " + e.population() + ")");
            dataset.addEntity(occ);
        }
    }
}
