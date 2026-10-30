package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Configuration des organisations lues : {@code international-orgs.json} dans le plugin, ou le fichier du paramètre
 * {@code configFile} (pour corriger un motif sans reconstruire le plugin).
 *
 * <p>Pour chaque fonction, {@code pattern} est une expression régulière appliquée au texte de la page (une ligne par
 * paragraphe, voir {@link PageText}). Le nom est capturé par le groupe {@code name}, ou par {@code first} et
 * {@code last}. {@code {NAME}} est remplacé par {@link #NAME} : deux à cinq mots commençant par une majuscule.
 */
final class OrgConfig {

    static final String RESOURCE = "/international-orgs.json";

    /** Nom propre : « Khaled El-Enany », « Nico HORN », « Julia Marton-Lefèvre », « H.E. Hailemariam Dessalegn Boshe ». */
    static final String NAME = "(?<name>\\p{Lu}[\\p{L}'’.-]*(?:[ \\t]+(?:\\p{Lu}[\\p{L}'’.-]*|de|da|del|della|di|du|van|von"
            + "|bin|al|el|y)){1,4})";

    record Function(String label, Pattern pattern) {
    }

    record Page(String url, List<Function> functions) {
    }

    /**
     * Une organisation de la liste officielle.
     *
     * @param pending fonctions de la liste qui ne sont pas lues, et pourquoi (affiché dans le message), ou
     *                {@code null}
     * @param none    vrai si la liste officielle n'y cite aucune fonction
     */
    record Organization(String key, String name, List<Page> pages, String pending, boolean none) {
    }

    private OrgConfig() {
    }

    static List<Organization> load(String configFile) throws IOException {
        if (configFile != null && !configFile.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(configFile.trim()))) {
                return parse(in);
            }
        }
        try (InputStream in = OrgConfig.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Configuration " + RESOURCE + " absente du plugin");
            }
            return parse(in);
        }
    }

    static List<Organization> parse(InputStream in) throws IOException {
        List<Organization> orgs = new ArrayList<>();
        for (JsonNode o : new ObjectMapper().readTree(in).path("organizations")) {
            List<Page> pages = new ArrayList<>();
            for (JsonNode p : o.path("pages")) {
                List<Function> functions = new ArrayList<>();
                for (JsonNode f : p.path("functions")) {
                    functions.add(new Function(f.path("label").asText(), Pattern.compile(
                            f.path("pattern").asText().replace("{NAME}", NAME), Pattern.MULTILINE)));
                }
                pages.add(new Page(p.path("url").asText(), functions));
            }
            orgs.add(new Organization(o.path("key").asText(), o.path("name").asText(), pages,
                    o.hasNonNull("pending") ? o.path("pending").asText() : null, o.path("none").asBoolean(false)));
        }
        return orgs;
    }

    /** Nom du fichier brut d'une page : {@code <clé>-<n° de page>.html}. */
    static String pageFile(Organization org, int index) {
        return org.key() + "-" + (index + 1) + ".html";
    }
}
