package com.swiftcheck.aml.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Récupère le contenu d'un site web protégé par des sécurités anti-robots (Cloudflare,
 * DataDome, Radware, Akamai, etc.) en pilotant un moteur de navigation réel (Chromium / Edge
 * avec module Stealth) ou via un sidecar local.
 *
 * <p>Ordre de résolution :
 * <ol>
 *   <li>Sidecar HTTP local {@code http://127.0.0.1:8099/fetch?url=...} (s'il est démarré) ;</li>
 *   <li>Exécution locale de {@code node fetch.js <url> <targetFile>} ;</li>
 *   <li>Repli sur client HTTP standard avec en-têtes réalistes de navigateur.</li>
 * </ol>
 */
public final class StealthBrowserFetcher {

    private static final String SIDECAR_URL = "http://127.0.0.1:8099";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private static final List<Path> CANDIDATE_SCRIPT_PATHS = List.of(
            Path.of("swiftcheck-aml-tools/crawler-stealth/fetch.js"),
            Path.of("../swiftcheck-aml-tools/crawler-stealth/fetch.js"),
            Path.of("../../swiftcheck-aml-tools/crawler-stealth/fetch.js"),
            Path.of("../../../swiftcheck-aml-tools/crawler-stealth/fetch.js"),
            Path.of("D:/projects/swift-check-aml/swiftcheck-aml-tools/crawler-stealth/fetch.js")
    );

    private StealthBrowserFetcher() {
    }

    /**
     * Télécharge l'URL demandée vers le fichier cible spécifié.
     */
    public static void fetchToFile(String url, Path targetFile) throws IOException, InterruptedException {
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 1. Tenter le sidecar HTTP s'il est actif
        if (tryFetchSidecar(url, targetFile)) {
            return;
        }

        // 2. Tenter le script CLI fetch.js avec node
        Path script = resolveScriptPath();
        if (script != null && Files.isRegularFile(script)) {
            if (tryFetchCli(script, url, targetFile)) {
                return;
            }
        }

        // 3. Repli sur requête HTTP standard
        fetchStandardHttp(url, targetFile);
    }

    /**
     * Télécharge l'URL demandée et retourne les octets.
     */
    public static byte[] fetchBytes(String url) throws IOException, InterruptedException {
        Path temp = Files.createTempFile("stealth-download-", ".tmp");
        try {
            fetchToFile(url, temp);
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Télécharge l'URL demandée et retourne le HTML sous forme de chaîne UTF-8.
     */
    public static String fetchHtml(String url) throws IOException, InterruptedException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static boolean tryFetchSidecar(String url, Path targetFile) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            // Vérifier santé du sidecar
            HttpRequest healthReq = HttpRequest.newBuilder(URI.create(SIDECAR_URL + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
            if (healthResp.statusCode() != 200) {
                return false;
            }

            String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8);
            HttpRequest fetchReq = HttpRequest.newBuilder(URI.create(SIDECAR_URL + "/fetch?url=" + encoded))
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(fetchReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                String body = resp.body();
                // Extraire le champ "html" sans dépendance externe
                int idx = body.indexOf("\"html\":\"");
                if (idx > 0) {
                    String htmlRaw = body.substring(idx + 8);
                    if (htmlRaw.endsWith("\"}")) {
                        htmlRaw = htmlRaw.substring(0, htmlRaw.length() - 2);
                    } else if (htmlRaw.endsWith("\"")) {
                        htmlRaw = htmlRaw.substring(0, htmlRaw.length() - 1);
                    }
                    String unescaped = unescapeJson(htmlRaw);
                    Files.writeString(targetFile, unescaped, StandardCharsets.UTF_8);
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Sidecar non disponible, on passe au CLI
        }
        return false;
    }

    private static boolean tryFetchCli(Path script, String url, Path targetFile) {
        try {
            Path tempOut = Files.createTempFile("stealth-cli-", ".html");
            ProcessBuilder pb = new ProcessBuilder(
                    "node",
                    script.toAbsolutePath().toString(),
                    url,
                    tempOut.toAbsolutePath().toString(),
                    "10"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(50, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Files.deleteIfExists(tempOut);
                return false;
            }

            if (proc.exitValue() == 0 && Files.exists(tempOut) && Files.size(tempOut) > 0) {
                Files.move(tempOut, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            Files.deleteIfExists(tempOut);
        } catch (Exception ignored) {
            // Repli suivant
        }
        return false;
    }

    private static void fetchStandardHttp(String url, Path targetFile) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Sec-CH-UA", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Sec-CH-UA-Platform", "\"Windows\"")
                .GET()
                .build();

        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() == 200) {
            String encoding = resp.headers().firstValue("Content-Encoding").orElse("");
            try (InputStream in = "gzip".equalsIgnoreCase(encoding.trim()) ? new GZIPInputStream(resp.body()) : resp.body()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " lors du téléchargement de " + url);
        }
    }

    private static Path resolveScriptPath() {
        String env = System.getenv("CRAWLER_STEALTH_PATH");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isRegularFile(p)) return p;
        }
        String prop = System.getProperty("crawler.stealth.path");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.isRegularFile(p)) return p;
        }
        for (Path c : CANDIDATE_SCRIPT_PATHS) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    private static String unescapeJson(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { b.append('\n'); i++; }
                    case 'r' -> { b.append('\r'); i++; }
                    case 't' -> { b.append('\t'); i++; }
                    case '"' -> { b.append('"'); i++; }
                    case '\\' -> { b.append('\\'); i++; }
                    case '/' -> { b.append('/'); i++; }
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            try {
                                b.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                b.append(c);
                            }
                        } else {
                            b.append(c);
                        }
                    }
                    default -> b.append(c);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}

