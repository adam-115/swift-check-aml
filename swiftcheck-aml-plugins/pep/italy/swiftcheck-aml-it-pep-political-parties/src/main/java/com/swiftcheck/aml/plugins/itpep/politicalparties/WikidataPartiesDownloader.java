package com.swiftcheck.aml.plugins.itpep.politicalparties;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interroge le point SPARQL de Wikidata et dépose la réponse brute {@value #LEADERS_FILE} dans {@code rawDir}, où
 * le core la signe et l'archive. En cas d'échec réseau, utilise la ressource embarquée comme fallback.
 */
class WikidataPartiesDownloader {

    private static final Logger log = LoggerFactory.getLogger(WikidataPartiesDownloader.class);

    static final String LEADERS_FILE = "leaders.json";

    /** Identifiant Wikidata : seule forme acceptée dans les paramètres, insérée telle quelle dans la requête. */
    private static final Pattern QID = Pattern.compile("Q\\d+");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - dirigeants des partis politiques)";
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    void download(String endpoint, String countryQid, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Path target = rawDir.resolve(LEADERS_FILE);
        try {
            String leaders = post(endpoint, leadersQuery(countryQid));
            Files.writeString(target, leaders, StandardCharsets.UTF_8);
            log.info("Wikidata : réponse des dirigeants de partis italiens reçue ({} octets)", leaders.length());
        } catch (Exception e) {
            log.warn("Wikidata injoignable ({}), bascule sur le fichier de secours", e.getMessage());
            try (InputStream in = getClass().getResourceAsStream("/wikidata/" + LEADERS_FILE)) {
                if (in != null) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Wikidata secours chargé avec succès");
                    return;
                }
            }
            throw e;
        }
    }

    static String leadersQuery(String countryQid) {
        requireQid(countryQid);
        return """
                SELECT ?party ?partyLabel ?role ?statement ?rank ?start ?end ?person ?personLabel ?birth ?birthPrecision ?gender WHERE {
                  {
                    SELECT DISTINCT ?party WHERE {
                      ?party wdt:P31/wdt:P279* wd:Q7278 ; wdt:P17 wd:%1$s .
                      FILTER NOT EXISTS { ?party wdt:P576 ?dissolved }
                      FILTER NOT EXISTS { ?party wdt:P156|wdt:P1366 ?successor }
                    }
                  }
                  VALUES (?prop ?ps ?role) { (p:P488 ps:P488 "chair") (p:P3975 ps:P3975 "secretaryGeneral") (p:P1037 ps:P1037 "director") (p:P169 ps:P169 "director") }
                  ?party ?prop ?statement .
                  ?statement ?ps ?person ; wikibase:rank ?rank .
                  FILTER(?rank != wikibase:DeprecatedRank)
                  ?person wdt:P31 wd:Q5 .
                  FILTER NOT EXISTS { ?person wdt:P570 ?death }
                  OPTIONAL { ?statement pq:P580 ?start }
                  OPTIONAL { ?statement pq:P582 ?end }
                  OPTIONAL { ?person p:P569/psv:P569 ?birthValue .
                             ?birthValue wikibase:timeValue ?birth ; wikibase:timePrecision ?birthPrecision . }
                  OPTIONAL { ?person wdt:P21 ?gender }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "it,mul,en,fr".
                    ?party rdfs:label ?partyLabel . ?person rdfs:label ?personLabel . }
                }
                """.formatted(countryQid);
    }

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
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response.body();
                }
                log.warn("Wikidata tentative {}/{} : HTTP {}", attempt, MAX_ATTEMPTS, response.statusCode());
            } catch (IOException e) {
                last = e;
                log.warn("Wikidata tentative {}/{} : {}", attempt, MAX_ATTEMPTS, e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(2000L * attempt);
            }
        }
        throw last != null ? last : new IOException("Wikidata : aucun résultat après " + MAX_ATTEMPTS + " tentatives");
    }

    private static void requireQid(String qid) {
        if (!QID.matcher(qid).matches()) {
            throw new IllegalArgumentException("Identifiant Wikidata invalide : " + qid);
        }
    }
}

