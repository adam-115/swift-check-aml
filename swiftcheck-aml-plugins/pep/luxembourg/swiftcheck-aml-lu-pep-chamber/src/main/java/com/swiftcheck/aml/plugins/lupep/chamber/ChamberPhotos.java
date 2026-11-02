package com.swiftcheck.aml.plugins.lupep.chamber;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portraits officiels des députés, lus sur la page « Députés » de chd.lu : le jeu de données de data.public.lu n'en
 * donne pas. Chaque portrait porte le nom du député :
 * <pre>
 * &lt;img src="https://wdocs-pub.chd.lu/photos/821.jpg" class="img-fluid mh-100" alt="Diane Adehm"&gt;
 * </pre>
 * Rapprochement par prénom et nom exacts (sans accents ni casse) : un député sans portrait au même nom n'a pas de
 * photo, plutôt qu'une photo d'un autre.
 */
final class ChamberPhotos {

    static final String DEFAULT_URL = "https://www.chd.lu/fr/deputies";
    private static final Pattern PORTRAIT = Pattern.compile(
            "<img\\s+src=\"(https://wdocs-pub\\.chd\\.lu/photos/\\d+\\.(?:jpe?g|png))\"[^>]*\\salt=\"([^\"]+)\"");
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Luxembourg - Chambre des Députés)";

    private ChamberPhotos() {
    }

    /** Portrait par clé de nom ({@link #key}). Un nom porté par deux portraits différents est écarté. */
    static Map<String, String> read(String html) {
        Map<String, String> photos = new HashMap<>();
        Matcher m = PORTRAIT.matcher(html);
        while (m.find()) {
            String key = key(m.group(2).replace("&#039;", "'").replace("&amp;", "&"));
            String previous = photos.putIfAbsent(key, m.group(1));
            if (previous != null && !previous.equals(m.group(1))) {
                photos.put(key, null);              // homonymes : aucune photo plutôt qu'une au hasard
            }
        }
        photos.values().removeIf(v -> v == null);
        return photos;
    }

    /** « Diane Adehm » et « ADEHM Diane » → « adehm diane ». */
    static String key(String name) {
        String folded = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", " ").trim();
        String[] words = folded.split("\\s+");
        java.util.Arrays.sort(words);
        return String.join(" ", words);
    }

    static String download(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(1)).header("User-Agent", USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " : " + url);
        }
        return response.body();
    }
}
