package com.swiftcheck.aml.media;

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
import java.util.HexFormat;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Téléchargement d'une image et archivage sous son empreinte SHA-256 ({@code <dossier>/<sha256>.<ext>}).
 *
 * <ul>
 *   <li>image seulement ({@code image/jpeg}, {@code png}, {@code webp}, {@code gif}), au plus {@value #MAX_BYTES} octets ;</li>
 *   <li>Wikimedia Commons : vignette de 400 px demandée, licence et auteur lus par l'API de Commons ;</li>
 *   <li>aucune requête avec un faux navigateur : un site qui refuse le client SwiftCheck (HTTP 403) n'est pas contourné.</li>
 * </ul>
 */
@Component
public class ImageFetcher {

    static final int MAX_BYTES = 5_000_000;
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", ".jpg", "image/png", ".png",
            "image/webp", ".webp", "image/gif", ".gif");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Identification du client, avec un contact générique configurable (jamais l'adresse d'un utilisateur). */
    @Value("${swiftcheck.media.user-agent:SwiftCheck-AML/1.0 (photos des PEP)}")
    private String userAgent = "SwiftCheck-AML/1.0 (photos des PEP)";

    /** Image téléchargée et archivée. */
    public record Fetched(Path file, String sha256, String contentType, long size) {
    }

    /** Image lue, non archivée (vignette d'un candidat de la recherche sur internet). */
    public record Image(byte[] bytes, String contentType) {
    }

    public Fetched fetch(String imageUrl, Path folder) throws IOException, InterruptedException {
        String url = isCommons(imageUrl) && !imageUrl.contains("width=") ? imageUrl + (imageUrl.contains("?") ? "&" : "?")
                + "width=400" : imageUrl;
        Image image = download(url);
        String sha = sha256(image.bytes());
        Files.createDirectories(folder);
        Path file = folder.resolve(sha + EXTENSIONS.get(image.contentType()));
        if (!Files.exists(file)) {
            Files.write(file, image.bytes());
        }
        return new Fetched(file, sha, image.contentType(), image.bytes().length);
    }

    /** Télécharge une image (http ou https), avec les mêmes contrôles de type et de taille, sans l'archiver. */
    public Image download(String url) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("adresse invalide");
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IOException("adresse refusée (" + uri.getScheme() + ")");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent).header("Accept", "image/*").header("Accept-Encoding", "identity").GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String type = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim().toLowerCase();
        if (!EXTENSIONS.containsKey(type)) {
            throw new IOException("pas une image (" + type + ")");
        }
        byte[] body = decoded(response.body(), response.headers().firstValue("Content-Encoding").orElse(""));
        if (body.length == 0 || body.length > MAX_BYTES) {
            throw new IOException("taille refusée (" + body.length + " octets)");
        }
        if (!looksLikeImage(body)) {
            throw new IOException("contenu reçu qui n'est pas une image (" + type + " annoncé)");
        }
        return new Image(body, type);
    }

    /**
     * Corps décompressé. Le client HTTP de Java ne décompresse pas : certains sites (bmvg.de) envoient l'image en gzip
     * même sans {@code Accept-Encoding}, et l'archive gardait alors un fichier gzip illisible par le navigateur.
     */
    static byte[] decoded(byte[] body, String contentEncoding) throws IOException {
        String enc = contentEncoding.trim().toLowerCase();
        boolean gzipMagic = body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
        if (enc.contains("gzip") || gzipMagic) {
            try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                return in.readNBytes(MAX_BYTES + 1);
            }
        }
        if (enc.contains("deflate")) {
            try (var in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(body))) {
                return in.readNBytes(MAX_BYTES + 1);
            }
        }
        return body;
    }

    /** Signature d'un fichier JPEG, PNG, GIF ou WebP. */
    static boolean looksLikeImage(byte[] b) {
        if (b.length < 12) {
            return false;
        }
        boolean jpeg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return jpeg || png || gif || webp;
    }

    static boolean isCommons(String url) {
        return url.contains("commons.wikimedia.org/wiki/Special:FilePath/")
                || url.contains("upload.wikimedia.org/wikipedia/commons/");
    }

    /**
     * Licence et auteur d'un fichier de Wikimedia Commons (« CC BY-SA 4.0 – Claude Truong-Ngoc »), ou {@code null}
     * si l'adresse n'est pas celle d'un fichier de Commons ou si l'API ne répond pas.
     */
    public String commonsLicence(String imageUrl) {
        int i = imageUrl.indexOf("Special:FilePath/");
        if (i < 0) {
            return null;
        }
        String fileName = java.net.URLDecoder.decode(imageUrl.substring(i + "Special:FilePath/".length()).split("\\?")[0],
                StandardCharsets.UTF_8);
        try {
            String api = "https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=extmetadata"
                    + "&format=json&titles=" + URLEncoder.encode("File:" + fileName, StandardCharsets.UTF_8);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", userAgent).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode meta = json.readTree(r.body()).path("query").path("pages").elements().next()
                    .path("imageinfo").path(0).path("extmetadata");
            String licence = text(meta, "LicenseShortName");
            String artist = text(meta, "Artist");
            if (licence.isEmpty() && artist.isEmpty()) {
                return null;
            }
            return (licence.isEmpty() ? "Licence non indiquée" : licence) + (artist.isEmpty() ? "" : " – " + artist)
                    + " (Wikimedia Commons)";
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String text(JsonNode meta, String key) {
        return meta.path(key).path("value").asText("").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
