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

    public LobbyregisterApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/json")
                .header("apiKey", key)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        log.info("Appel de l'API Lobbyregister : {} (cle : {}...)", apiUrl, key.substring(0, Math.min(6, key.length())));
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            log.error("Erreur HTTP de l'API Lobbyregister : status {}", statusCode);
            throw new IOException("L'API Lobbyregister a renvoye une erreur HTTP " + statusCode + " sur " + apiUrl);
        }

        byte[] body = response.body();
        if (body == null || body.length == 0) {
            log.error("Reponse vide recue de l'API Lobbyregister : {}", apiUrl);
            throw new IOException("Payload vide recu depuis l'API Lobbyregister : " + apiUrl);
        }

        String sha256 = computeSha256Hex(body);
        Path targetFile = rawDir.resolve("lobbyregister.json");
        Files.write(targetFile, body);

        DetailedSearchResults results = objectMapper.readValue(body, DetailedSearchResults.class);
        log.info("Lobbyregister ingere avec succes : {} entites, SHA-256 = {}", results.resultCount, sha256);

        return new IngestionResult(results, sha256, statusCode, targetFile);
    }

    public DetailedSearchResults parseRawFile(Path rawFile) throws IOException {
        byte[] bytes = Files.readAllBytes(rawFile);
        return objectMapper.readValue(bytes, DetailedSearchResults.class);
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

