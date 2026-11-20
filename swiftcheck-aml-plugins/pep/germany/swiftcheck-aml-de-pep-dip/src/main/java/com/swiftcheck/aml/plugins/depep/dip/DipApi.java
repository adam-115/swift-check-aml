package com.swiftcheck.aml.plugins.depep.dip;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.StealthBrowserFetcher;

/**
 * Lecture de l'API DIP du Bundestag ({@code https://search.dip.bundestag.de/api/v1/person}) : pages de 100 personnes
 * reliées par un curseur. Chaque page est gardée dans {@code rawDir} avec son empreinte SHA-256.
 */
final class DipApi {

    private static final Logger log = LoggerFactory.getLogger(DipApi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String DEFAULT_ENDPOINT = "https://search.dip.bundestag.de/api/v1";
    /** Clé publique affichée sur https://dip.bundestag.de/über-dip/hilfe/api (« zunächst bis Ende Mai 2027 gültig »). */
    static final String DEFAULT_API_KEY = "R2BZaee.DjdCyihKZMf8AOjtScubP2EVydegzjmBIQ";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - DIP Bundestag)";
    static final int MAX_PAGES = 200;

    private DipApi() {
    }

    /** Lit une adresse de l'API et rend le texte de la réponse. */
    @FunctionalInterface
    interface Fetcher {
        String get(String url) throws IOException;
    }

    /** Une page lue : adresse (sans la clé), fichier gardé, empreinte, documents. */
    record Page(String url, Path file, String sha256, List<JsonNode> documents) {
    }

    /** Pages d'une législature et nombre de personnes annoncé par l'API. */
    record Download(List<Page> pages, int numFound, Instant retrievedAt) {
    }

    /**
     * Client HTTP : clé dans l'en-tête {@code Authorization: ApiKey …}. Une clé refusée (401/403) est une erreur nette
     * (clé expirée) ; une autre erreur ou un défi anti-robot passe par le service navigateur (clé dans l'adresse,
     * seule forme qu'il sait transmettre).
     */
    static Fetcher http(String apiKey) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            String direct;
            try {
                HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json").header("Authorization", "ApiKey " + apiKey).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    throw new IOException("clé API DIP refusée (HTTP " + resp.statusCode()
                            + ") : clé expirée ou invalide, mettre à jour le paramètre apiKey");
                }
                if (resp.statusCode() == 200 && !StealthBrowserFetcher.isChallenge(resp)) {
                    return new String(resp.body(), StandardCharsets.UTF_8);
                }
                direct = "HTTP " + resp.statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompu", e);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("clé API DIP refusée")) {
                    throw e;
                }
                direct = e.getMessage();
            }
            log.warn("{} : {} ; essai par le service navigateur", url, direct);
            try {
                String sep = url.contains("?") ? "&" : "?";
                return new String(StealthBrowserFetcher.downloadBytes(url + sep + "apikey="
                        + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompu", e);
            } catch (IOException e) {
                throw new IOException("lecture impossible de " + url + " : direct " + direct + " ; " + e.getMessage(), e);
            }
        };
    }

    /**
     * Lit toutes les pages des personnes d'une législature. Lève une erreur si l'API ne rend rien ou si le curseur
     * s'arrête avant le nombre annoncé : une lecture incomplète ne doit pas remplacer la publication précédente.
     */
    static Download download(Fetcher fetcher, String endpoint, int wahlperiode, Path rawDir, long delayMs)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint)
                + "/person?format=json&f.wahlperiode=" + wahlperiode;
        Instant retrievedAt = Instant.now();
        List<Page> pages = new ArrayList<>();
        String cursor = null;
        int numFound = -1;
        int read = 0;
        for (int i = 0; i < MAX_PAGES; i++) {
            String url = cursor == null ? base : base + "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            String json = fetcher.get(url);
            Path file = rawDir.resolve("person-wp" + wahlperiode + "-" + i + ".json");
            Files.writeString(file, json, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(json);
            if (!root.has("documents")) {
                throw new IOException("réponse DIP inattendue (pas de « documents ») sur " + url);
            }
            numFound = root.path("numFound").asInt(-1);
            List<JsonNode> docs = new ArrayList<>();
            root.path("documents").forEach(docs::add);
            if (docs.isEmpty()) {
                break;
            }
            pages.add(new Page(url, file, sha256(json), docs));
            read += docs.size();
            String next = root.path("cursor").asText(null);
            if (next == null || next.equals(cursor) || (numFound >= 0 && read >= numFound)) {
                break;
            }
            cursor = next;
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        if (read == 0) {
            throw new IOException("l'API DIP n'a rendu aucune personne pour la législature " + wahlperiode
                    + " : publication précédente conservée");
        }
        if (numFound > 0 && read < numFound) {
            throw new IOException("lecture DIP incomplète : " + read + " personnes sur " + numFound + " annoncées");
        }
        return new Download(pages, numFound, retrievedAt);
    }

    /**
     * Titre de la dernière activité d'une personne (ex. « Michael Kellner, MdB, BÜNDNIS 90/DIE GRÜNEN ») : il donne la
     * fonction exercée à cette date, alors que la fiche personne peut garder une fonction terminée.
     */
    @FunctionalInterface
    interface Activities {
        /** @return le titre et la date de la dernière activité, ou {@code null} si la personne n'en a aucune */
        String[] latest(String personId) throws IOException;
    }

    /** Dernière activité lue dans l'API ({@code /aktivitaet?f.person_id=…}), réponse gardée dans {@code rawDir}. */
    static Activities liveActivities(Fetcher fetcher, String endpoint, Path rawDir, long delayMs) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return personId -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrompu", e);
                }
            }
            String json = fetcher.get(base + "/aktivitaet?format=json&f.person_id="
                    + URLEncoder.encode(personId, StandardCharsets.UTF_8));
            Files.writeString(rawDir.resolve("aktivitaet-" + personId + ".json"), json, StandardCharsets.UTF_8);
            return latest(json);
        };
    }

    /** Dernière activité relue dans un dossier ; {@code null} si le fichier n'existe pas (aucun contrôle). */
    static Activities savedActivities(Path dir) {
        return personId -> {
            Path file = dir.resolve("aktivitaet-" + personId + ".json");
            return Files.isRegularFile(file) ? latest(Files.readString(file, StandardCharsets.UTF_8)) : null;
        };
    }

    /** Activité la plus récente d'une réponse {@code /aktivitaet} : {titre, date}, ou {@code null}. */
    static String[] latest(String json) throws IOException {
        JsonNode best = null;
        for (JsonNode d : MAPPER.readTree(json).path("documents")) {
            if (best == null || d.path("datum").asText("").compareTo(best.path("datum").asText("")) > 0) {
                best = d;
            }
        }
        return best == null || best.path("titel").asText("").isBlank() ? null
                : new String[] {best.path("titel").asText(), best.path("datum").asText(null)};
    }

    /** Relit les pages déjà gardées dans un dossier ({@code person-wp<n>-<i>.json}), dans l'ordre. */
    static Download readSaved(Path dir, int wahlperiode) throws IOException {
        List<Page> pages = new ArrayList<>();
        int numFound = -1;
        for (int i = 0; ; i++) {
            Path file = dir.resolve("person-wp" + wahlperiode + "-" + i + ".json");
            if (!Files.isRegularFile(file)) {
                break;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(json);
            numFound = root.path("numFound").asInt(-1);
            List<JsonNode> docs = new ArrayList<>();
            root.path("documents").forEach(docs::add);
            if (!docs.isEmpty()) {
                pages.add(new Page(file.getFileName().toString(), file, sha256(json), docs));
            }
        }
        if (pages.isEmpty()) {
            throw new IOException("aucune page DIP person-wp" + wahlperiode + "-*.json dans " + dir);
        }
        return new Download(pages, numFound, Files.getLastModifiedTime(pages.get(0).file()).toInstant());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
