package com.swiftcheck.aml.ingestion;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lecture du champ {@code Parser.parameters} : un objet JSON dont les valeurs sont transmises au plugin.
 * <pre>{@code {"endpoint": "https://query.wikidata.org/sparql", "limit": 5000}}</pre>
 * Les valeurs non textuelles (nombres, booléens) sont converties en texte.
 */
public final class ParserParameters {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ParserParameters() {
    }

    /**
     * @param json contenu du champ, éventuellement vide
     * @return les paramètres ; une table vide si le champ est vide
     * @throws IllegalArgumentException si le texte n'est pas un objet JSON
     */
    public static Map<String, String> parse(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Paramètres du parser : JSON invalide (" + e.getOriginalMessage() + ")");
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Paramètres du parser : un objet JSON {\"nom\": \"valeur\"} est attendu");
        }
        node.fields().forEachRemaining(field -> result.put(field.getKey(),
                field.getValue().isValueNode() ? field.getValue().asText() : field.getValue().toString()));
        return result;
    }
}
