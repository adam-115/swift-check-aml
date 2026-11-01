package com.swiftcheck.aml.plugins.frpep.wikidatapositions;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_wikidata_positions</b> – Titulaires actuels et récents de fonctions PEP françaises <b>à titulaire unique</b>
 * qui n'ont pas de source officielle exploitable, d'après Wikidata (P39 « fonction occupée »).
 *
 * <p>Premier usage : le <b>chef d'état-major de la Marine</b> (Q2961994, catégorie FR-f). defense.gouv.fr n'a pas de
 * biographie à jour : la seule page trouvée (octobre 2026) présente encore l'amiral Vaujour, remplacé le 15 juillet
 * 2026. D'autres fonctions s'ajoutent par le paramètre {@code positions}, sans code.
 *
 * <p>Paramètres : {@code {"endpoint": "https://query.wikidata.org/sparql", "positions": "Q2961994",
 * "monthsAfterEnd": "12"}}.
 *
 * <p>Choix du titulaire actuel, fonction par fonction (un seul titulaire à la fois) :
 * <ol>
 *   <li>mandat terminé : gardé comme ancien s'il a pris fin depuis moins de {@code monthsAfterEnd} mois ;</li>
 *   <li>mandats sans fin : celui au rang « préféré » s'il y en a un, sinon celui qui a commencé le plus tard ; les
 *       autres sont remplacés. Un mandat sans date (ex. Jean-Louis Battet, CEMM de 2001 à 2005, enregistré sans
 *       dates) n'est donc jamais pris pour le titulaire actuel s'il existe un mandat daté.</li>
 * </ol>
 */
@Extension
public class FrPepWikidataPositionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepWikidataPositionsParser.class);

    static final String ID_PREFIX = "fr-pep-wikidata-positions";
    static final String TOPIC = "role.pep";
    static final String HOLDERS_FILE = "holders.json";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_POSITIONS = "Q2961994";

    private static final Pattern QID = Pattern.compile("Q\\d+");
    private static final String PREFERRED_RANK = "http://wikiba.se/ontology#PreferredRank";
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - fonctions Wikidata)";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters();
        List<String> positions = qids(p.getOrDefault("positions", DEFAULT_POSITIONS));
        Files.createDirectories(ctx.rawDir());
        Files.writeString(ctx.rawDir().resolve(HOLDERS_FILE),
                post(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), query(positions)), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now().minusMonths(months(p)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now().minusMonths(months(ctx.parameters())), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Une déclaration « X occupe ou a occupé la fonction Y ». */
    record Statement(String id, String positionQid, String positionLabel, boolean preferred, String start, String end,
            String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    record Selection(List<Statement> current, List<Statement> former, int superseded, int endedLongAgo) {
    }

    record Report(int positions, List<String> current, List<String> former, int superseded, int endedLongAgo) {

        String message() {
            return String.format("%d fonctions lues. En fonction : %s. Sortis depuis moins de la durée retenue : %s."
                    + " Écartés : %d mandats remplacés, %d terminés depuis plus longtemps.", positions,
                    current.isEmpty() ? "aucun" : String.join(" ; ", current),
                    former.isEmpty() ? "aucun" : String.join(" ; ", former), superseded, endedLongAgo);
        }
    }

    Report convert(Path rawDir, LocalDate since, FtmDatasetBuilder dataset) throws Exception {
        List<Statement> statements = new ArrayList<>();
        for (Statement s : statements(json.readTree(Files.readString(rawDir.resolve(HOLDERS_FILE))))) {
            if (!s.personLabel().isBlank() && !s.personLabel().equals(s.personQid())) {
                statements.add(s);
            }
        }
        Selection selection = select(statements, since);
        if (selection.current().isEmpty()) {
            throw new IllegalStateException("Aucun titulaire en fonction dans la réponse de Wikidata : fonctions mal"
                    + " configurées ou réponse vide.");
        }
        List<String> current = new ArrayList<>();
        List<String> former = new ArrayList<>();
        for (Statement s : selection.current()) {
            write(dataset, s, true);
            current.add(s.personLabel() + " (" + s.positionLabel() + ", depuis " + s.start() + ")");
        }
        for (Statement s : selection.former()) {
            write(dataset, s, false);
            former.add(s.personLabel() + " (" + s.positionLabel() + ", jusqu'au " + s.end() + ")");
        }
        Set<String> positions = new LinkedHashSet<>();
        statements.forEach(s -> positions.add(s.positionQid()));
        Report report = new Report(positions.size(), current, former, selection.superseded(), selection.endedLongAgo());
        log.info(report.message());
        return report;
    }

    static List<Statement> statements(JsonNode response) {
        Map<String, Statement> byId = new LinkedHashMap<>();
        for (JsonNode b : response.path("results").path("bindings")) {
            String id = text(b, "statement");
            Statement s = byId.computeIfAbsent(id, k -> new Statement(id, qid(text(b, "pos")), text(b, "posLabel"),
                    PREFERRED_RANK.equals(text(b, "rank")), date(text(b, "start")), date(text(b, "end")),
                    qid(text(b, "person")), text(b, "personLabel"), new LinkedHashSet<>(), qid(text(b, "gender"))));
            String birth = birthDate(text(b, "birth"), text(b, "birthPrecision"));
            if (birth != null) {
                s.birthDates().add(birth);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Voir la description de la classe : un seul titulaire en cours par fonction. */
    static Selection select(List<Statement> statements, LocalDate since) {
        Map<String, List<Statement>> byPosition = new LinkedHashMap<>();
        statements.forEach(s -> byPosition.computeIfAbsent(s.positionQid(), k -> new ArrayList<>()).add(s));
        List<Statement> current = new ArrayList<>();
        List<Statement> former = new ArrayList<>();
        int superseded = 0;
        int endedLongAgo = 0;
        for (List<Statement> group : byPosition.values()) {
            Statement holder = null;
            for (Statement s : group) {
                if (s.end() != null) {
                    if (s.end().compareTo(since.toString()) >= 0) {
                        former.add(s);
                    } else {
                        endedLongAgo++;
                    }
                } else if (holder == null || better(s, holder)) {
                    holder = s;
                }
            }
            for (Statement s : group) {
                if (s.end() == null && s != holder) {
                    superseded++;
                }
            }
            if (holder != null) {
                current.add(holder);
            }
        }
        return new Selection(current, former, superseded, endedLongAgo);
    }

    /** Rang préféré d'abord, puis début le plus récent ; un mandat sans date de début passe après un mandat daté. */
    private static boolean better(Statement s, Statement holder) {
        if (s.preferred() != holder.preferred()) {
            return s.preferred();
        }
        if (holder.start() == null) {
            return s.start() != null;
        }
        return s.start() != null && s.start().compareTo(holder.start()) > 0;
    }

    private static void write(FtmDatasetBuilder dataset, Statement s, boolean isCurrent) {
        String label = Character.toUpperCase(s.positionLabel().charAt(0)) + s.positionLabel().substring(1);
        FtmEntity person = dataset.newEntity("Person", s.personQid());
        person.addValue("name", s.personLabel());
        person.addValue("wikidataId", s.personQid());
        s.birthDates().forEach(d -> person.addValue("birthDate", d));
        if ("Q6581097".equals(s.gender())) {
            person.addValue("gender", "male");
        } else if ("Q6581072".equals(s.gender())) {
            person.addValue("gender", "female");
        }
        person.addValue("country", "fr");
        person.addValue("position", isCurrent ? label : "Ancien : " + label);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.positionQid());
        position.addValue("name", label);
        position.addValue("country", "fr");
        position.addValue("wikidataId", s.positionQid());
        position.addValue("topics", "gov.national");
        position.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.positionQid());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", s.personQid(), s.positionQid(),
                s.start() == null ? "" : s.start());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", s.start());
        occupancy.addValue("endDate", s.end());
        occupancy.addValue("status", isCurrent ? "current" : "ended");
        occupancy.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(occupancy);
    }

    /** Titulaires (vivants, humains, rang non déprécié) des fonctions données, avec dates, rang et naissance. */
    static String query(List<String> positions) {
        positions.forEach(q -> {
            if (!QID.matcher(q).matches()) {
                throw new IllegalArgumentException("Identifiant Wikidata invalide : « " + q + " » (attendu : Q123)");
            }
        });
        return """
                SELECT ?pos ?posLabel ?statement ?rank ?start ?end ?person ?personLabel ?birth ?birthPrecision ?gender WHERE {
                  VALUES ?pos { %s }
                  ?person p:P39 ?statement .
                  ?statement ps:P39 ?pos ; wikibase:rank ?rank .
                  FILTER(?rank != wikibase:DeprecatedRank)
                  ?person wdt:P31 wd:Q5 .
                  FILTER NOT EXISTS { ?person wdt:P570 ?death }
                  OPTIONAL { ?statement pq:P580 ?start }
                  OPTIONAL { ?statement pq:P582 ?end }
                  OPTIONAL { ?person p:P569/psv:P569 ?birthValue .
                             ?birthValue wikibase:timeValue ?birth ; wikibase:timePrecision ?birthPrecision . }
                  OPTIONAL { ?person wdt:P21 ?gender }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,mul,en".
                    ?pos rdfs:label ?posLabel . ?person rdfs:label ?personLabel . }
                }
                """.formatted(String.join(" ", positions.stream().map(q -> "wd:" + q).toList()));
    }

    private String post(String endpoint, String query) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(90))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Wikidata a répondu HTTP " + response.statusCode() + " (" + endpoint + ")");
        }
        return response.body();
    }

    static List<String> qids(String value) {
        return Arrays.stream(value.split("[\\s,;]+")).filter(s -> !s.isBlank()).toList();
    }

    /** Date de naissance selon sa précision Wikidata (11 jour, 10 mois, 9 année), comme {@code fr_pep_rca}. */
    static String birthDate(String value, String precision) {
        if (value == null || value.length() < 10 || value.startsWith("-")) {
            return null;
        }
        int p;
        try {
            p = Integer.parseInt(precision);
        } catch (NumberFormatException e) {
            p = 11;
        }
        return p >= 11 ? value.substring(0, 10) : p == 10 ? value.substring(0, 7) : p == 9 ? value.substring(0, 4) : null;
    }

    private static String date(String value) {
        return value.length() < 10 ? null : value.substring(0, 10);
    }

    private static String text(JsonNode binding, String column) {
        return binding.path(column).path("value").asText("");
    }

    private static String qid(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    static int months(Map<String, String> parameters) {
        try {
            return Integer.parseInt(parameters.getOrDefault("monthsAfterEnd", "12").trim());
        } catch (NumberFormatException e) {
            return 12;
        }
    }
}
