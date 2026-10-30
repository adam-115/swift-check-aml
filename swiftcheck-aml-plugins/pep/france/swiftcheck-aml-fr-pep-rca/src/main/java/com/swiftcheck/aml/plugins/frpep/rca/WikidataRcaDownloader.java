package com.swiftcheck.aml.plugins.frpep.rca;

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
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Interroge le point SPARQL de Wikidata et dépose les deux réponses brutes dans {@code rawDir}, où le core les
 * signe et les archive :
 * <ol>
 *   <li>{@value #PEPS_FILE} : les PEP français vivants, en fonction ou sortis depuis moins de N ans ;</li>
 *   <li>{@value #RELATIVES_FILE} : leurs proches vivants (conjoint actuel, partenaire, enfants et leurs conjoints, père, mère).</li>
 * </ol>
 * Deux requêtes plutôt qu'une : la requête combinée dépasse le délai du serveur (60 s), alors que la seconde,
 * qui part de la liste explicite des PEP, répond en quelques secondes.
 */
class WikidataRcaDownloader {

    private static final Logger log = LoggerFactory.getLogger(WikidataRcaDownloader.class);

    static final String PEPS_FILE = "peps.json";
    static final String RELATIVES_FILE = "relatives.json";
    /** Fonctions trouvées par classe (ex. toutes les fonctions de ministre français), pour la traçabilité. */
    static final String POSITIONS_FILE = "positions.json";

    /** Identifiant Wikidata : seule forme acceptée dans les paramètres, insérée telle quelle dans la requête. */
    private static final Pattern QID = Pattern.compile("Q\\d+");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - famille des PEP)";
    private static final int MAX_ATTEMPTS = 3;

    /** Nombre de PEP par requête de proches : au-delà, le serveur risque de dépasser son délai (60 s). */
    static final int BATCH_SIZE = 400;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @return le nombre de PEP et de liens familiaux trouvés, pour le journal
     */
    int[] download(String endpoint, Query query, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        query = resolveClasses(endpoint, query, rawDir);
        String peps = post(endpoint, pepQuery(query));
        Files.writeString(rawDir.resolve(PEPS_FILE), peps, StandardCharsets.UTF_8);

        // Des milliers de PEP en France : les proches sont demandés par lots, puis réunis dans un seul fichier
        List<String> pepIds = qids(json.readTree(peps), "pep");
        ObjectNode merged = (ObjectNode) json.readTree("{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}");
        ArrayNode bindings = (ArrayNode) merged.path("results").path("bindings");
        for (int from = 0; from < pepIds.size(); from += BATCH_SIZE) {
            JsonNode batch = json.readTree(post(endpoint,
                    relativesQuery(pepIds.subList(from, Math.min(from + BATCH_SIZE, pepIds.size())))));
            merged.set("head", batch.path("head"));
            batch.path("results").path("bindings").forEach(bindings::add);
        }
        String relatives = json.writeValueAsString(merged);
        Files.writeString(rawDir.resolve(RELATIVES_FILE), relatives, StandardCharsets.UTF_8);

        int links = bindings.size();
        log.info("Wikidata : {} PEP français, {} liens familiaux", pepIds.size(), links);
        return new int[] { pepIds.size(), links };
    }

    /**
     * Remplace les {@code positionClasses} par la liste explicite de leurs fonctions dans le pays, demandée à part
     * et gardée dans {@value #POSITIONS_FILE}. Dans la requête des PEP, « ?pos wdt:P1001 pays . ?pos wdt:P279+
     * classe » dépasse le délai du serveur (HTTP 504) ; seule, la sous-requête des sous-classes répond en
     * une quinzaine de secondes, et la requête des PEP sur une liste explicite en une ou deux.
     */
    private Query resolveClasses(String endpoint, Query query, Path rawDir) throws IOException, InterruptedException {
        if (query.positionClasses().isEmpty()) {
            return query;
        }
        ObjectNode merged = (ObjectNode) json.readTree("{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}");
        ArrayNode bindings = (ArrayNode) merged.path("results").path("bindings");
        for (String c : query.positionClasses()) {
            JsonNode result = json.readTree(post(endpoint, classPositionsQuery(query.countryQid(), c)));
            merged.set("head", result.path("head"));
            result.path("results").path("bindings").forEach(bindings::add);
        }
        Files.writeString(rawDir.resolve(POSITIONS_FILE), json.writeValueAsString(merged), StandardCharsets.UTF_8);
        List<String> positions = java.util.stream.Stream.concat(query.positions().stream(), qids(merged, "pos").stream())
                .distinct().toList();
        log.info("Wikidata : {} fonctions retenues, dont {} par classe", positions.size(),
                positions.size() - query.positions().size());
        return new Query(query.countryQid(), query.since(), query.extraPositions(), query.excludedPositions(),
                positions, List.of());
    }

    /** Fonctions de juridiction {@code countryQid} qui sont une sorte de {@code classQid} (sous-classes d'abord). */
    static String classPositionsQuery(String countryQid, String classQid) {
        requireQid(countryQid);
        requireQid(classQid);
        return """
                SELECT DISTINCT ?pos WHERE {
                  { SELECT ?pos WHERE { ?pos wdt:P279+ wd:%s } }
                  ?pos wdt:P1001 wd:%s .
                }
                """.formatted(classQid, countryQid);
    }

    /**
     * Ce qui définit un « PEP français » dans Wikidata : une personne vivante qui occupe ou a occupé depuis
     * {@code since} une fonction (P39) retenue. Deux modes :
     * <ul>
     *   <li><b>liste autorisée</b> (France), si {@code positions} ou {@code positionClasses} est renseigné : les
     *       fonctions citées, et les fonctions de juridiction (P1001) le pays qui sont une sorte de l'une des
     *       {@code positionClasses} (ex. Q83307 « ministre ») ;</li>
     *   <li>sinon, comme {@code lu_pep_rca} : toute fonction dont la juridiction est le pays, plus
     *       {@code extraPositions}, moins {@code excludedPositions}.</li>
     * </ul>
     * Les postes d'ambassadeur étrangers en France sont toujours écartés. En France, la juridiction seule ramène
     * des fonctions hors R. 561-18 (chercheurs du CNRS, professeurs, préfets, conseillers municipaux…), d'où la
     * liste autorisée.
     */
    record Query(String countryQid, LocalDate since, List<String> extraPositions, List<String> excludedPositions,
            List<String> positions, List<String> positionClasses) {

        Query {
            requireQid(countryQid);
            extraPositions.forEach(WikidataRcaDownloader::requireQid);
            excludedPositions.forEach(WikidataRcaDownloader::requireQid);
            positions.forEach(WikidataRcaDownloader::requireQid);
            positionClasses.forEach(WikidataRcaDownloader::requireQid);
        }

        boolean allowList() {
            return !positions.isEmpty() || !positionClasses.isEmpty();
        }
    }

    static String pepQuery(Query q) {
        String selection;
        if (q.allowList()) {
            List<String> parts = new java.util.ArrayList<>();
            if (!q.positions().isEmpty()) {
                parts.add("{ VALUES ?pos { " + wd(q.positions()) + " } }");
            }
            for (String c : q.positionClasses()) {
                parts.add("{ ?pos wdt:P1001 wd:" + q.countryQid() + " . ?pos wdt:P279+ wd:" + c + " }");
            }
            selection = String.join(" UNION ", parts);
        } else {
            selection = "{ ?pos wdt:P1001 wd:" + q.countryQid() + " }"
                    + (q.extraPositions().isEmpty() ? "" : " UNION { VALUES ?pos { " + wd(q.extraPositions()) + " } }");
        }
        String excluded = q.excludedPositions().isEmpty() ? ""
                : "FILTER(?pos NOT IN (" + q.excludedPositions().stream().map(s -> "wd:" + s)
                        .collect(Collectors.joining(", ")) + "))\n";
        return """
                SELECT ?pep ?pepLabel (GROUP_CONCAT(DISTINCT ?posLabel; separator=" | ") AS ?positions) WHERE {
                  {
                    SELECT DISTINCT ?pep ?pos WHERE {
                      %s
                      %sFILTER NOT EXISTS { ?pos wdt:P31/wdt:P279* wd:Q121998 }
                      FILTER NOT EXISTS { ?pos wdt:P279+ wd:Q121998 }
                      ?held ps:P39 ?pos .
                      ?pep p:P39 ?held .
                      OPTIONAL { ?held pq:P582 ?end }
                      FILTER(!BOUND(?end) || ?end >= "%sT00:00:00Z"^^xsd:dateTime)
                      FILTER NOT EXISTS { ?pep wdt:P570 ?death }
                    }
                  }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,mul,en".
                    ?pep rdfs:label ?pepLabel . ?pos rdfs:label ?posLabel . }
                } GROUP BY ?pep ?pepLabel
                """.formatted(selection, excluded, q.since());
    }

    /**
     * Proches vivants des PEP donnés. Un conjoint dont le mariage a une date de fin (P582) n'est pas retenu.
     * La précision de la date de naissance est demandée pour ne pas inventer un jour ou un mois.
     */
    static String relativesQuery(List<String> pepQids) {
        pepQids.forEach(WikidataRcaDownloader::requireQid);
        return """
                SELECT ?pep ?relationship ?relative ?relativeLabel ?relativeBirth ?birthPrecision ?relativeGender WHERE {
                  VALUES ?pep { %s }
                  {
                    ?pep p:P26 ?st . ?st ps:P26 ?relative . BIND("spouse" AS ?relationship)
                    FILTER NOT EXISTS { ?st pq:P582 ?ended }
                  } UNION {
                    ?pep p:P451 ?pt . ?pt ps:P451 ?relative . BIND("partner" AS ?relationship)
                    FILTER NOT EXISTS { ?pt pq:P582 ?ptEnded }
                  } UNION {
                    ?pep wdt:P40 ?relative . BIND("child" AS ?relationship)
                  } UNION {
                    ?pep wdt:P40 ?child . ?child p:P26 ?cs . ?cs ps:P26 ?relative . BIND("childSpouse" AS ?relationship)
                    FILTER NOT EXISTS { ?cs pq:P582 ?csEnded }
                  } UNION {
                    ?pep wdt:P22 ?relative . BIND("father" AS ?relationship)
                  } UNION {
                    ?pep wdt:P25 ?relative . BIND("mother" AS ?relationship)
                  }
                  FILTER NOT EXISTS { ?relative wdt:P570 ?relativeDeath }
                  OPTIONAL { ?relative p:P569/psv:P569 ?birth .
                             ?birth wikibase:timeValue ?relativeBirth ; wikibase:timePrecision ?birthPrecision . }
                  OPTIONAL { ?relative wdt:P21 ?relativeGender }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,mul,en". ?relative rdfs:label ?relativeLabel . }
                }
                """.formatted(wd(pepQids));
    }

    /** QID final d'une colonne d'URI (« http://www.wikidata.org/entity/Q42 » → « Q42 »), sans doublon. */
    static List<String> qids(JsonNode sparqlResult, String column) {
        return sparqlResult.path("results").path("bindings").findValues(column).stream()
                .map(node -> node.path("value").asText(""))
                .map(uri -> uri.substring(uri.lastIndexOf('/') + 1))
                .filter(s -> QID.matcher(s).matches())
                .distinct()
                .toList();
    }

    /** Requête envoyée en POST (la liste des PEP dépasse la longueur raisonnable d'une URL). */
    private String post(String endpoint, String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/sparql-results+json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return response.body();
            }
            last = new IOException("Wikidata a répondu HTTP " + response.statusCode() + " (" + endpoint + ")");
            if (response.statusCode() != 429 && response.statusCode() < 500) {
                break;          // requête refusée : inutile de réessayer
            }
            log.warn("Wikidata : tentative {}/{} échouée ({})", attempt, MAX_ATTEMPTS, last.getMessage());
            Thread.sleep(5000L * attempt);
        }
        throw last;
    }

    private static String wd(List<String> qids) {
        return qids.stream().map(s -> "wd:" + s).collect(Collectors.joining(" "));
    }

    static void requireQid(String value) {
        if (value == null || !QID.matcher(value).matches()) {
            throw new IllegalArgumentException("Identifiant Wikidata invalide : « " + value + " » (attendu : Q123)");
        }
    }
}
