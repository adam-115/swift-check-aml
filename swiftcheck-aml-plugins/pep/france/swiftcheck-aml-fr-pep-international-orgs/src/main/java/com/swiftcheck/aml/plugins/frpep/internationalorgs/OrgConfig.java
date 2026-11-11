package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Configuration des organisations lues, fournie par le paramètre {@code configJson} du parser (écran du parser ou
 * colonne {@code parser.parameters}), sans fichier embarqué dans le plugin.
 *
 * <p>Pour chaque fonction, {@code pattern} est une expression régulière appliquée au texte de la page (une ligne par
 * paragraphe, voir {@link PageText}). Le nom est capturé par le groupe {@code name}, ou par {@code first} et
 * {@code last}. {@code {NAME}} est remplacé par {@link #NAME} : deux à cinq mots commençant par une majuscule.
 */
final class OrgConfig {

    static final String CONFIG_PARAMETER = "configJson";

    /** Nom propre : « Khaled El-Enany », « Nico HORN », « Julia Marton-Lefèvre », « H.E. Hailemariam Dessalegn Boshe ». */
    static final String NAME = "(?<name>\\p{Lu}[\\p{L}'’.-]*(?:[ \\t]+(?:\\p{Lu}[\\p{L}'’.-]*|de|da|del|della|di|du|van|von"
            + "|bin|al|el|y)){1,4})";

    record Function(String label, Pattern pattern) {
    }

    /** @param photo mode de lecture des portraits ({@link PagePhotos}), ou {@code null} : pas de photo */
    record Page(String url, List<Function> functions, String photo) {
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

    /**
     * Lit la configuration dans le paramètre {@code configJson} du parser (objet JSON {@code {"organizations": [...]}}),
     * modifiable depuis l'écran du parser ou directement en base. Aucune configuration n'est embarquée dans le plugin :
     * sans ce paramètre, l'exécution échoue avec un message qui dit quoi faire.
     */
    static List<Organization> load(Map<String, String> parameters) throws IOException {
        String configJson = parameters == null ? null : parameters.get(CONFIG_PARAMETER);
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalStateException("Paramètre « " + CONFIG_PARAMETER + " » absent : la liste des organisations"
                    + " et des motifs de lecture se configure dans les paramètres du parser (écran « Paramètres » ou"
                    + " colonne parser.parameters).");
        }
        List<Organization> orgs;
        try (InputStream in = new ByteArrayInputStream(configJson.getBytes(StandardCharsets.UTF_8))) {
            orgs = parse(in);
        } catch (IOException | PatternSyntaxException e) {
            throw new IllegalStateException("Paramètre « " + CONFIG_PARAMETER + " » invalide : " + e.getMessage(), e);
        }
        if (orgs.isEmpty()) {
            throw new IllegalStateException("Paramètre « " + CONFIG_PARAMETER + " » : aucune organisation dans"
                    + " « organizations ».");
        }
        return orgs;
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
                pages.add(new Page(p.path("url").asText(), functions,
                        p.hasNonNull("photo") ? p.path("photo").asText() : null));
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
