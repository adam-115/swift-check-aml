package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    static List<Organization> load() throws IOException {
        return loadDefault();
    }

    static List<Organization> load(Map<String, String> parameters) throws IOException {
        if (parameters != null) {
            // 1. JSON brut directement passé dans parameters (ex: configJson ou parameters.config)
            String configJson = parameters.get("configJson");
            if (configJson != null && !configJson.isBlank()) {
                try (InputStream in = new ByteArrayInputStream(configJson.getBytes(StandardCharsets.UTF_8))) {
                    return parse(in);
                }
            }
            // 2. URL distante où télécharger la configuration dynamique
            String configUrl = parameters.get("configUrl");
            if (configUrl != null && !configUrl.isBlank()) {
                try {
                    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(configUrl.trim()))
                            .timeout(Duration.ofSeconds(20))
                            .header("Accept", "application/json")
                            .header("User-Agent", "Mozilla/5.0")
                            .GET().build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() == 200) {
                        try (InputStream in = new ByteArrayInputStream(resp.body().getBytes(StandardCharsets.UTF_8))) {
                            return parse(in);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Téléchargement interrompu depuis configUrl : " + configUrl, e);
                }
            }
            // 3. Fichier externe si précisé
            String configFile = parameters.get("configFile");
            if (configFile != null && !configFile.isBlank()) {
                try (InputStream in = Files.newInputStream(Path.of(configFile.trim()))) {
                    return parse(in);
                }
            }
        }
        return loadDefault();
    }

    static List<Organization> loadFromFile(String configFile) throws IOException {
        if (configFile != null && !configFile.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(configFile.trim()))) {
                return parse(in);
            }
        }
        return loadDefault();
    }

    private static List<Organization> loadDefault() throws IOException {
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
