package com.swiftcheck.aml.plugins.lupep.rca;

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

/**
 * Interroge le point SPARQL de Wikidata et dépose les deux réponses brutes dans {@code rawDir}, où le core les
 * signe et les archive :
 * <ol>
 *   <li>{@value #PEPS_FILE} : les PEP luxembourgeois vivants, en fonction ou sortis depuis moins de N ans ;</li>
 *   <li>{@value #RELATIVES_FILE} : leurs proches vivants (conjoint actuel, enfants, père, mère, frères et sœurs).</li>
 * </ol>
 * Deux requêtes plutôt qu'une : la requête combinée dépasse le délai du serveur (60 s), alors que la seconde,
 * qui part de la liste explicite des PEP, répond en quelques secondes.
 */
class WikidataRcaDownloader {

    private static final Logger log = LoggerFactory.getLogger(WikidataRcaDownloader.class);

    static final String PEPS_FILE = "peps.json";
    static final String RELATIVES_FILE = "relatives.json";

    /** Identifiant Wikidata : seule forme acceptée dans les paramètres, insérée telle quelle dans la requête. */
    private static final Pattern QID = Pattern.compile("Q\\d+");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Luxembourg - famille des PEP)";
    private static final int MAX_ATTEMPTS = 3;

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
        String peps = post(endpoint, pepQuery(query));
        Files.writeString(rawDir.resolve(PEPS_FILE), peps, StandardCharsets.UTF_8);

        List<String> pepIds = qids(json.readTree(peps), "pep");
        String relatives = pepIds.isEmpty()
                ? "{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}"
                : post(endpoint, relativesQuery(pepIds));
        Files.writeString(rawDir.resolve(RELATIVES_FILE), relatives, StandardCharsets.UTF_8);

        int links = json.readTree(relatives).path("results").path("bindings").size();
        log.info("Wikidata : {} PEP luxembourgeois, {} liens familiaux", pepIds.size(), links);
        return new int[] { pepIds.size(), links };
    }

    /**
     * Ce qui définit un « PEP luxembourgeois » dans Wikidata : une personne vivante qui occupe ou a occupé depuis
     * {@code since} une fonction (P39) dont la juridiction (P1001) est le pays, ou une fonction ajoutée
     * explicitement. Les fonctions exclues et les postes d'ambassadeur (étrangers en poste au Luxembourg) sont écartés.
     */
    record Query(String countryQid, LocalDate since, List<String> extraPositions, List<String> excludedPositions) {

        Query {
            requireQid(countryQid);
            extraPositions.forEach(WikidataRcaDownloader::requireQid);
            excludedPositions.forEach(WikidataRcaDownloader::requireQid);
        }
    }

    static String pepQuery(Query q) {
        String extra = q.extraPositions().isEmpty() ? ""
                : " UNION { VALUES ?pos { " + wd(q.extraPositions()) + " } }";
        String excluded = q.excludedPositions().isEmpty() ? ""
                : "FILTER(?pos NOT IN (" + q.excludedPositions().stream().map(s -> "wd:" + s)
                        .collect(Collectors.joining(", ")) + "))\n";
        return """
                SELECT ?pep ?pepLabel (GROUP_CONCAT(DISTINCT ?posLabel; separator=" | ") AS ?positions) WHERE {
                  {
                    SELECT DISTINCT ?pep ?pos WHERE {
                      { ?pos wdt:P1001 wd:%s }%s
                      %sFILTER NOT EXISTS { ?pos wdt:P31/wdt:P279* wd:Q121998 }
                      FILTER NOT EXISTS { ?pos wdt:P279+ wd:Q121998 }
                      ?held ps:P39 ?pos .
                      ?pep p:P39 ?held .
                      OPTIONAL { ?held pq:P582 ?end }
                      FILTER(!BOUND(?end) || ?end >= "%sT00:00:00Z"^^xsd:dateTime)
                      FILTER NOT EXISTS { ?pep wdt:P570 ?death }
                    }
                  }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,lb,de,en".
                    ?pep rdfs:label ?pepLabel . ?pos rdfs:label ?posLabel . }
                } GROUP BY ?pep ?pepLabel
                """.formatted(q.countryQid(), extra, excluded, q.since());
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
                    ?pep wdt:P40 ?relative . BIND("child" AS ?relationship)
                  } UNION {
                    ?pep wdt:P22 ?relative . BIND("father" AS ?relationship)
                  } UNION {
                    ?pep wdt:P25 ?relative . BIND("mother" AS ?relationship)
                  } UNION {
                    ?pep wdt:P3373 ?relative . BIND("sibling" AS ?relationship)
                  }
                  FILTER NOT EXISTS { ?relative wdt:P570 ?relativeDeath }
                  OPTIONAL { ?relative p:P569/psv:P569 ?birth .
                             ?birth wikibase:timeValue ?relativeBirth ; wikibase:timePrecision ?birthPrecision . }
                  OPTIONAL { ?relative wdt:P21 ?relativeGender }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,lb,de,en". ?relative rdfs:label ?relativeLabel . }
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
