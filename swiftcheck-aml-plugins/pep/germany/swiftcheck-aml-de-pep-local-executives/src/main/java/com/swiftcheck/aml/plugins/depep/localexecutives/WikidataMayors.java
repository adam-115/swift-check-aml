package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Complément Wikidata pour les maires des villes d'un Land dont aucune liste officielle n'est encore lue. Les villes
 * viennent du répertoire Destatis et sont cherchées par leur clé AGS (propriété P439), pas par leur nom ; le maire est
 * le titulaire de P6 (chef de l'exécutif) sans date de fin et au début le plus récent. Une ville avec plusieurs
 * titulaires possibles n'est pas publiée. Chaque fiche porte une note : source collaborative, non confirmée.
 */
final class WikidataMayors {

    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final ObjectMapper JSON = new ObjectMapper();

    private WikidataMayors() {
    }

    /** Adresse GET de la requête SPARQL pour ces clés AGS. */
    static String queryUrl(String endpoint, Collection<String> ags) {
        String query = """
                SELECT ?ags ?person ?personLabel ?start ?partyLabel WHERE {
                  VALUES ?ags { %s }
                  ?entity wdt:P439 ?ags ; p:P6 ?statement .
                  ?statement ps:P6 ?person .
                  FILTER NOT EXISTS { ?statement pq:P582 ?end }
                  OPTIONAL { ?statement pq:P580 ?start }
                  OPTIONAL { ?person wdt:P102 ?party }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
                }""".formatted(String.join(" ", ags.stream().map(a -> "\"" + a + "\"").toList()));
        return endpoint + "?format=json&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    /**
     * Maire de chaque ville de la réponse, par clé AGS. Une ville dont deux personnes différentes ont le début le plus
     * récent (ou aucune date) est écartée et nommée dans {@code ambiguous}.
     */
    static Map<String, OfficialLists.Executive> parse(String json, Map<String, OfficialLists.Municipality> cities, List<String> ambiguous)
            throws IOException {
        Map<String, List<JsonNode>> byAgs = new LinkedHashMap<>();
        for (JsonNode b : JSON.readTree(json).path("results").path("bindings")) {
            byAgs.computeIfAbsent(b.path("ags").path("value").asText(), k -> new ArrayList<>()).add(b);
        }
        Map<String, OfficialLists.Executive> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<JsonNode>> e : byAgs.entrySet()) {
            OfficialLists.Municipality city = cities.get(e.getKey());
            if (city == null) {
                continue;
            }
            String latest = e.getValue().stream().map(b -> b.path("start").path("value").asText("")).max(String::compareTo).orElse("");
            List<JsonNode> holders = e.getValue().stream().filter(b -> b.path("start").path("value").asText("").equals(latest)).toList();
            long people = holders.stream().map(b -> b.path("person").path("value").asText()).distinct().count();
            String name = holders.get(0).path("personLabel").path("value").asText("");
            if (people != 1 || name.isBlank() || name.matches("Q\\d+")) {
                ambiguous.add(city.shortName());
                continue;
            }
            String[] parts = OfficialLists.splitFirstLast(name);
            String party = holders.get(0).path("partyLabel").path("value").asText(null);
            out.put(e.getKey(), new OfficialLists.Executive(city.land(), city.ags(), city.shortName(), "Stadt", city.population(),
                    "Oberbürgermeister", parts[1], parts[2], parts[0], null, null, party,
                    latest.length() >= 10 ? latest.substring(0, 10) : null, holders.get(0).path("person").path("value").asText(),
                    "Source : Wikidata (source collaborative), aucune liste officielle du Land lue ; à confirmer."));
        }
        return out;
    }
}
