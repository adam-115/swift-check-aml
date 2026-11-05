package com.swiftcheck.aml.plugins.espep.boe;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fonctions PEP reconnues dans les titres du BOE, lues dans {@code boe-functions.json} (livré dans le JAR) : une
 * entrée par fonction ou famille de fonctions, avec le bloc du référentiel (loi 10/2010, art. 14) et une expression
 * sur le libellé de la fonction.
 */
final class BoeFunctions {

    static final String RESOURCE = "/boe-functions.json";

    /** Une fonction reconnue. */
    record Function(String key, String category, String organization, List<String> topics, String group,
            Pattern pattern, int labelGroup) {

        /** Libellé de la fonction : le groupe de capture demandé, sinon le libellé complet. */
        String label(String cargo) {
            if (labelGroup > 0) {
                Matcher m = pattern.matcher(cargo);
                if (m.find() && m.groupCount() >= labelGroup && m.group(labelGroup) != null) {
                    return m.group(labelGroup);
                }
            }
            return cargo;
        }
    }

    private final List<Function> functions;

    BoeFunctions(List<Function> functions) {
        this.functions = List.copyOf(functions);
    }

    static BoeFunctions load() throws IOException {
        try (InputStream in = BoeFunctions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " introuvable dans le plugin.");
            }
            return parse(new ObjectMapper().readTree(in));
        }
    }

    static BoeFunctions parse(JsonNode root) {
        List<Function> list = new ArrayList<>();
        for (JsonNode f : root.path("functions")) {
            List<String> topics = new ArrayList<>();
            f.path("topics").forEach(t -> topics.add(t.asText()));
            list.add(new Function(f.path("key").asText(), f.path("category").asText(),
                    f.path("organization").asText(), topics, f.path("group").asText(f.path("key").asText()),
                    Pattern.compile(f.path("pattern").asText(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                    f.path("labelGroup").asInt(0)));
        }
        return new BoeFunctions(list);
    }

    /** La première fonction dont l'expression reconnaît ce libellé ; l'ordre du fichier fait foi. */
    Optional<Function> match(String cargo) {
        if (cargo == null) {
            return Optional.empty();
        }
        return functions.stream().filter(f -> f.pattern().matcher(cargo).find()).findFirst();
    }

    int size() {
        return functions.size();
    }
}
