package com.swiftcheck.aml.plugins.bepep.moniteur;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Télécharge les sommaires et actes officiels de nomination publiés au Moniteur belge ({@code ejustice.just.fgov.be})
 * et les dépose dans {@code rawDir} (source {@code PLUGIN_MANAGED}).
 */
final class MoniteurDownloader {

    private static final Logger log = LoggerFactory.getLogger(MoniteurDownloader.class);

    private static final String DEFAULT_BASE_URL = "https://www.ejustice.just.fgov.be/cgi";
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (collecte des nominations au Moniteur belge)";
    private static final int MAX_ATTEMPTS = 3;

    /** Filtre les rubriques ou titres d'actes pertinents pour les hautes fonctions PEP belges. */
    private static final Pattern RELEVANT_TITLE = Pattern.compile(
            "ordre judiciaire|conseil d'etat|conseil d’etat|cour de cassation|cour d'appel|cour d’appel"
                    + "|cour du travail|force armée|force armee|ambassade|affaires étrangères|affaires etrangeres"
                    + "|gouvernement|commissaire du gouvernement|nomination|démission|demission|mandat",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ARTICLE_LINK = Pattern.compile(
            "href=[\"'](article\\.pl\\?[^\"']*numac_search=(\\d+)[^\"']*)[\"'][^>]*>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    record ArticleMeta(String numac, String url, String title) {
    }

    private final HttpClient client;
    private final Duration delay;

    MoniteurDownloader(Duration delay) {
        this.delay = delay;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Télécharge les sommaires quotidiens depuis {@code since} jusqu'à aujourd'hui, puis chaque article correspondant
     * à une haute fonction ou nomination/démission.
     *
     * @return la liste des articles enregistrés
     */
    List<ArticleMeta> download(String baseUrl, LocalDate since, LocalDate until, Path rawDir) throws Exception {
        String base = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.replaceAll("/+$", "") : DEFAULT_BASE_URL;
        Files.createDirectories(rawDir);

        List<ArticleMeta> allArticles = new ArrayList<>();
        Set<String> seenNumac = new LinkedHashSet<>();

        LocalDate current = since;
        while (!current.isAfter(until)) {
            // Uniquement les jours ouvrables (le Moniteur paraît du lundi au vendredi)
            int dayOfWeek = current.getDayOfWeek().getValue();
            if (dayOfWeek <= 5) {
                String dateStr = current.format(DateTimeFormatter.ISO_LOCAL_DATE);
                String summaryUrl = base + "/summary.pl?language=fr&sum_date=" + dateStr;
                Path summaryFile = rawDir.resolve("summary-" + dateStr + ".html");

                byte[] summaryBytes;
                try {
                    summaryBytes = get(URI.create(summaryUrl));
                    Files.write(summaryFile, summaryBytes);
                    log.info("Sommaire Moniteur {} téléchargé ({} octets)", dateStr, summaryBytes.length);
                } catch (Exception e) {
                    log.warn("Impossible de récupérer le sommaire Moniteur du {} : {}", dateStr, e.getMessage());
                    summaryBytes = null;
                }

                if (summaryBytes != null) {
                    String html = new String(summaryBytes, StandardCharsets.ISO_8859_1);
                    Matcher m = ARTICLE_LINK.matcher(html);
                    while (m.find()) {
                        String relUrl = m.group(1).replace("&amp;", "&");
                        String numac = m.group(2);
                        String title = m.group(3).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();

                        if (RELEVANT_TITLE.matcher(title).find() && seenNumac.add(numac)) {
                            String articleUrl = relUrl.startsWith("http") ? relUrl : base + "/" + relUrl;
                            Path articleFile = rawDir.resolve("article-" + numac + ".html");
                            try {
                                if (delay != null && !delay.isZero()) {
                                    Thread.sleep(delay.toMillis());
                                }
                                byte[] artBytes = get(URI.create(articleUrl));
                                Files.write(articleFile, artBytes);
                                allArticles.add(new ArticleMeta(numac, articleUrl, title));
                                log.debug("Acte Moniteur {} téléchargé : {}", numac, title);
                            } catch (Exception e) {
                                log.warn("Erreur téléchargement acte {} ({}) : {}", numac, articleUrl, e.getMessage());
                            }
                        }
                    }
                }
                if (delay != null && !delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
            }
            current = current.plusDays(1);
        }
        return allArticles;
    }

    private byte[] get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-BE,fr;q=0.9,nl;q=0.8,en;q=0.7")
                .GET()
                .build();

        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    byte[] body = response.body();
                    String preview = new String(body, 0, Math.min(body.length, 1000), StandardCharsets.ISO_8859_1);
                    if (preview.contains("TSPD_101") || preview.contains("/TSPD/?type=25")) {
                        throw new IOException("Protection anti-bot TSPD rencontrée sur " + uri);
                    }
                    return body;
                }
                if (status >= 400 && status < 500) {
                    throw new IOException("Requête refusée par le Moniteur belge (HTTP " + status + ") : " + uri);
                }
                lastError = new IOException("Erreur serveur Moniteur belge (HTTP " + status + ") : " + uri);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("Requête refusée")) {
                    throw e;
                }
                lastError = e;
            }
            Thread.sleep(1000L * attempt);
        }
        throw lastError;
    }
}

