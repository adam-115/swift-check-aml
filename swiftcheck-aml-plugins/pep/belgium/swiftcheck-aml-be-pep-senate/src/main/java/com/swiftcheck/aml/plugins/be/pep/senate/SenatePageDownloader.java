package com.swiftcheck.aml.plugins.be.pep.senate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Télécharge la page officielle des sénateurs en fonction sur {@code senate.be}
 * en convertissant le contenu ISO-8859-1 en UTF-8 et en supprimant immédiatement
 * les lignes de correspondance privée (adresses postales, téléphones, courriels).
 */
final class SenatePageDownloader {

    private static final Logger log = LoggerFactory.getLogger(SenatePageDownloader.class);

    static final String DEFAULT_URL = "https://www.senate.be/www/?MIval=/WieIsWie/LijstDerSenatoren&LANG=fr";
    static final String FILE_NAME = "senate-list.html";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - senate.be)";

    private SenatePageDownloader() {}

    static Path download(String sourceUrl, Path rawDir) throws IOException {
        Files.createDirectories(rawDir);
        String url = (sourceUrl != null && !sourceUrl.isBlank() && !sourceUrl.contains("ListeSenateurs"))
                ? sourceUrl.trim()
                : DEFAULT_URL;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-BE,fr;q=0.9,nl;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " sur " + url);
            }
            byte[] rawBytes = decompressIfNeeded(response);
            Charset charset = detectCharset(response, rawBytes);
            String html = new String(rawBytes, charset);
            String sanitized = stripPrivateCorrespondence(html);

            Path target = rawDir.resolve(FILE_NAME);
            Files.writeString(target, sanitized, StandardCharsets.UTF_8);
            log.info("[be_pep_senate] Page téléchargée et nettoyée : {} ({} caractères)", FILE_NAME, sanitized.length());
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Téléchargement interrompu : " + url, e);
        }
    }

    static String stripPrivateCorrespondence(String html) {
        if (html == null) return "";
        return html.replaceAll(
                "(?is)<TR[^>]*>\\s*<TD><B>(?:Correspondance|Priv[^<]*|Bureau|Site web)\\s*:</B>.*?</TR>",
                "");
    }

    private static byte[] decompressIfNeeded(HttpResponse<byte[]> response) throws IOException {
        String encoding = response.headers().firstValue("Content-Encoding").orElse("");
        byte[] body = response.body();
        if ("gzip".equalsIgnoreCase(encoding.trim())
                || (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B)) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return gis.readAllBytes();
            }
        }
        return body;
    }

    private static Charset detectCharset(HttpResponse<byte[]> response, byte[] bytes) {
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("utf-8")) {
            return StandardCharsets.UTF_8;
        }
        if (contentType.contains("iso-8859-1") || contentType.contains("latin1")) {
            return StandardCharsets.ISO_8859_1;
        }
        String preview = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        if (preview.contains("charset=utf-8")) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.ISO_8859_1;
    }
}

