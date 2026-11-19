package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.DetailedSearchResults;

public class LobbyregisterApiClient {

    private static final Logger log = LoggerFactory.getLogger(LobbyregisterApiClient.class);
    private static final String DEFAULT_USER_AGENT = "SwiftCheck-AML/1.0 (Lobbyregister Bund Ingestion - GwG § 1 Abs. 14)";
    public static final String DEFAULT_TEST_API_KEY = "5bHB2zrUuHR6YdPoZygQhWfg2CBrjUOi";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    private static final int MAX_ATTEMPTS = 3;

    public LobbyregisterApiClient() {
        // HTTP/1.1 : en HTTP/2, le serveur annule parfois le flux d'une réponse aussi longue
        // (« Received RST_STREAM: Stream cancelled »).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public LobbyregisterApiClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public record IngestionResult(DetailedSearchResults searchResults, String sha256Hex, int statusCode, Path savedRawFile) {
    }

    public IngestionResult fetchAndSave(String apiUrl, String apiKey, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String key = resolveApiKey(apiKey);

        // Le registre complet pèse plusieurs centaines de Mo et met plusieurs minutes à arriver : la réponse est
        // écrite directement sur le disque (pas de tableau d'octets en mémoire), avec un délai large.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/json")
                .header("apiKey", key)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        Path targetFile = rawDir.resolve("lobbyregister.json");
        Path partFile = rawDir.resolve("lobbyregister.json.part");
        log.info("Appel de l'API Lobbyregister : {} (cle : {}...)", apiUrl, key.substring(0, Math.min(6, key.length())));
        int statusCode = 0;
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(partFile));
                statusCode = response.statusCode();
                if (statusCode >= 400 && statusCode < 500) {
                    Files.deleteIfExists(partFile);
                    throw new IOException("L'API Lobbyregister a renvoye une erreur HTTP " + statusCode + " sur " + apiUrl);
                }
                if (statusCode < 400) {
                    break;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    Files.deleteIfExists(partFile);
                    throw new IOException("L'API Lobbyregister a renvoye une erreur HTTP " + statusCode + " sur " + apiUrl);
                }
                log.warn("API Lobbyregister : HTTP {} (essai {}/{})", statusCode, attempt, MAX_ATTEMPTS);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("L'API Lobbyregister a renvoye")) {
                    throw e;
                }
                Files.deleteIfExists(partFile);
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("API Lobbyregister : téléchargement interrompu ({}), essai {}/{}", e.getMessage(), attempt, MAX_ATTEMPTS);
            }
            Thread.sleep(30_000L * attempt);
        }

        if (!Files.exists(partFile) || Files.size(partFile) == 0) {
            Files.deleteIfExists(partFile);
            log.error("Reponse vide recue de l'API Lobbyregister : {}", apiUrl);
            throw new IOException("Payload vide recu depuis l'API Lobbyregister : " + apiUrl);
        }
        Files.move(partFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String sha256 = computeSha256Hex(targetFile);
        DetailedSearchResults results = parseRawFile(targetFile);
        log.info("Lobbyregister ingere avec succes : {} entites, SHA-256 = {}", results.resultCount, sha256);

        return new IngestionResult(results, sha256, statusCode, targetFile);
    }

    public DetailedSearchResults parseRawFile(Path rawFile) throws IOException {
        return objectMapper.readValue(rawFile.toFile(), DetailedSearchResults.class);
    }

    /** Empreinte SHA-256 d'un fichier, lue par blocs (le registre complet ne tient pas confortablement en mémoire). */
    public static String computeSha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme SHA-256 indisponible", e);
        }
    }

    public static String computeSha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithme SHA-256 indisponible", e);
        }
    }

    private static String resolveApiKey(String explicitKey) {
        if (explicitKey != null && !explicitKey.isBlank()) {
            return explicitKey.trim();
        }
        String envKey = System.getenv("LOBBYREGISTER_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        return DEFAULT_TEST_API_KEY;
    }
}

