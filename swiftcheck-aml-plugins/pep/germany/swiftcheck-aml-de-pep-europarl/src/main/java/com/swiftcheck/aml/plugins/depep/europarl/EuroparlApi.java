package com.swiftcheck.aml.plugins.depep.europarl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * API open data du Parlement européen ({@code data.europarl.europa.eu/api/v2}, JSON-LD).
 *
 * <p>Conforme aux spécifications de la Story 17 :
 * <ul>
 *   <li>{@value #CURRENT_FILE} : députés en fonction élus en Allemagne ({@code meps/show-current?country-of-representation=DE}) ;</li>
 *   <li>{@value #TERM_FILE} : députés par législature ({@code meps?country-of-representation=DE&parliamentary-term=...}) ;</li>
 *   <li>{@code mep-<id>.json} : fiche détaillée unitaire avec date de naissance ({@code bday}), genre, mandats et fonctions exécutives au Bureau (H.1).</li>
 * </ul>
 */
final class EuroparlApi {

    private static final Logger log = LoggerFactory.getLogger(EuroparlApi.class);

    static final String CURRENT_FILE = "current.json";
    static final String TERM_FILE = "term.json";

    /** En-tête User-Agent officiel requis par la Story 17. */
    static final String USER_AGENT = "SwiftCheck-AML-Ingestion/1.0";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un député des listes ; {@code group} n'est connu que pour les députés en fonction. */
    record Mep(String id, String name, String firstName, String lastName, String group) {
    }

    /** Un mandat de député européen ; fin {@code null} = en cours. */
    record Mandate(String id, String term, String start, String end) {
    }

    /** Fiche d'un député : naissance, genre FtM ({@code male}/{@code female}), mandats et fonction au Bureau (H.1). */
    record Detail(String birthDate, String birthPlace, String gender, List<Mandate> mandates, String bureauRole) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    private EuroparlApi() {
    }

    /**
     * Télécharge les deux listes, puis la fiche de chaque député avec temporisation anti-saturation.
     *
     * @param detailUrl adresse de la fiche, avec {@code {id}} à la place de l'identifiant
     * @return les adresses des fiches qui n'ont pas pu être lues
     */
    static List<String> download(Fetcher fetcher, String currentUrl, String termUrl, String detailUrl, long delayMillis,
            Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve(CURRENT_FILE), fetcher.get(currentUrl));
        Files.write(rawDir.resolve(TERM_FILE), fetcher.get(termUrl));
        List<String> failed = new ArrayList<>();
        for (String id : meps(rawDir).keySet()) {
            Thread.sleep(delayMillis);
            String url = detailUrl.replace("{id}", id);
            try {
                Files.write(rawDir.resolve(detailFile(id)), fetcher.get(url));
            } catch (IOException e) {
                log.warn("Fiche non lue : {} ({})", url, e.getMessage());
                failed.add(url);
            }
        }
        return failed;
    }

    static String detailFile(String id) {
        return "mep-" + id + ".json";
    }

    /** Députés des deux listes, ceux en fonction d'abord (avec leur groupe). */
    static Map<String, Mep> meps(Path rawDir) throws IOException {
        Map<String, Mep> meps = new LinkedHashMap<>();
        for (String file : List.of(CURRENT_FILE, TERM_FILE)) {
            Path path = rawDir.resolve(file);
            if (!Files.exists(path)) {
                throw new IllegalStateException(file + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            for (JsonNode m : JSON.readTree(path.toFile()).path("data")) {
                String id = text(m.path("identifier"));
                if (id != null && !meps.containsKey(id)) {
                    meps.put(id, new Mep(id, text(m.path("label")), text(m.path("givenName")), text(m.path("familyName")),
                            CURRENT_FILE.equals(file) ? text(m.path("api:political-group")) : null));
                }
            }
        }
        return meps;
    }

    /** Ids des députés en fonction. */
    static List<String> currentIds(Path rawDir) throws IOException {
        List<String> ids = new ArrayList<>();
        JSON.readTree(rawDir.resolve(CURRENT_FILE).toFile()).path("data").forEach(m -> ids.add(text(m.path("identifier"))));
        return ids;
    }

    /**
     * Fiche du député, ou {@code null} si elle n'a pas été téléchargée.
     *
     * @param country code pays de l'Office des publications ({@code DEU})
     */
    static Detail detail(Path rawDir, String id, String country) throws IOException {
        Path path = rawDir.resolve(detailFile(id));
        if (!Files.exists(path)) {
            return null;
        }
        JsonNode d = JSON.readTree(path.toFile()).path("data").path(0);
        List<Mandate> mandates = new ArrayList<>();
        String bureauRole = null;

        for (JsonNode m : d.path("hasMembership")) {
            boolean forCountry = false;
            for (JsonNode r : m.path("represents")) {
                forCountry |= r.asText().endsWith("/country/" + country);
            }
            String role = text(m.path("role"));
            if ("def/ep-roles/MEMBER_PARLIAMENT".equals(role) && forCountry) {
                String organization = text(m.path("organization"));
                mandates.add(new Mandate(text(m.path("identifier")),
                        organization == null ? null : organization.replace("org/ep-", ""),
                        text(m.path("memberDuring").path("startDate")), text(m.path("memberDuring").path("endDate"))));
            }
            // Détection des fonctions de direction au Bureau du Parlement européen (Classe H.1)
            if (role != null) {
                if (role.contains("VICE_PRESIDENT")) {
                    bureauRole = "Vizepräsidentin/Vizepräsident des Europäischen Parlaments";
                } else if (role.contains("PRESIDENT")) {
                    bureauRole = "Präsidentin/Präsident des Europäischen Parlaments";
                } else if (role.contains("QUAESTOR")) {
                    bureauRole = "Quästor des Europäischen Parlaments";
                }
            }
        }
        String sex = text(d.path("hasGender"));
        String gender = sex == null ? null : sex.endsWith("/FEMALE") ? "female" : sex.endsWith("/MALE") ? "male" : null;
        return new Detail(text(d.path("bday")), text(d.path("placeOfBirth")), gender, mandates, bureauRole);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Client HTTP avec User-Agent officiel et Retry exponentiel sur HTTP 429 et 5xx (AC2.3).
     */
    static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            int maxRetries = 3;
            long waitMs = 1000;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/ld+json, application/json")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                if ((status == 429 || status >= 500) && attempt < maxRetries) {
                    log.warn("HTTP {} sur {} - nouvel essai ({}/{}) après {} ms", status, url, attempt, maxRetries, waitMs);
                    Thread.sleep(waitMs);
                    waitMs *= 2;
                    continue;
                }
                throw new IOException("HTTP " + status + " : " + url);
            }
            throw new IOException("Echec du téléchargement après " + maxRetries + " essais : " + url);
        };
    }
}
