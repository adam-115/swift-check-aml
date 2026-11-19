package com.swiftcheck.aml.plugins.sanction.common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Téléchargements des plugins {@code PLUGIN_MANAGED} : navigateur courant comme User-Agent, redirections suivies,
 * trois essais espacés en cas d'erreur réseau ou de réponse 5xx / 429.
 */
public final class Http {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private Http() {
    }

    public static byte[] get(String url) throws IOException, InterruptedException {
        return get(url, Map.of());
    }

    /** Corps de la réponse ; exception si le serveur ne répond pas 2xx après trois essais. */
    public static byte[] get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en,fr;q=0.8")
                    .GET();
            if (!headers.containsKey("Accept")) {
                request.header("Accept", "*/*");
            }
            headers.forEach(request::header);
            try {
                HttpResponse<byte[]> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                last = new IOException("HTTP " + status + " pour " + url);
                if (status < 500 && status != 429) {
                    throw last; // 403, 404 : inutile d'insister
                }
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().startsWith("HTTP 4") && !e.getMessage().startsWith("HTTP 429")) {
                    throw e;
                }
            }
            Thread.sleep(2000L * attempt);
        }
        throw last;
    }

    public static String getString(String url) throws IOException, InterruptedException {
        return new String(get(url), StandardCharsets.UTF_8);
    }

    /** Télécharge et enregistre la réponse brute dans {@code rawDir/fileName} (obligatoire en PLUGIN_MANAGED). */
    public static Path save(String url, Path rawDir, String fileName) throws IOException, InterruptedException {
        return save(url, Map.of(), rawDir, fileName);
    }

    public static Path save(String url, Map<String, String> headers, Path rawDir, String fileName)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Path target = rawDir.resolve(fileName);
        Files.write(target, get(url, headers));
        return target;
    }

    /** Pause entre deux requêtes vers le même site (paramètre {@code delayMs}, 500 ms par défaut). */
    public static void pause(Map<String, String> parameters) throws InterruptedException {
        String raw = parameters.getOrDefault("delayMs", "500");
        Thread.sleep(Long.parseLong(raw.trim()));
    }
}
