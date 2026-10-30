package com.swiftcheck.aml.plugins.frpep.diplomats;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Récupère les deux listes publiées par le ministère de l'Europe et des Affaires étrangères et les dépose dans
 * {@code rawDir}, où le core les signe et les archive :
 * <ol>
 *   <li>la page « Ambassades », et le PDF « liste des chefs de poste » qu'elle cite ;</li>
 *   <li>la page « Ambassadeurs et ambassadrices thématiques », et le PDF de la liste qu'elle cite.</li>
 * </ol>
 * L'adresse des PDF change à chaque mise à jour (elle contient la date : {@code 260522_liste_chefs_de_poste.pdf}) :
 * elle est lue dans la page à chaque exécution, jamais écrite dans la configuration.
 */
class DiplomatsDownloader {

    private static final Logger log = LoggerFactory.getLogger(DiplomatsDownloader.class);

    static final String EMBASSIES_PAGE = "ambassades.html";
    static final String HEADS_OF_POST_PDF = "chefs-de-poste.pdf";
    static final String THEMATIC_PAGE = "ambassadeurs-thematiques.html";
    static final String THEMATIC_PDF = "ambassadeurs-thematiques.pdf";
    /** Adresses des deux PDF lus, une par ligne : elles servent de {@code sourceUrl} aux entités. */
    static final String SOURCES_FILE = "sources.txt";

    /** Lien du PDF des chefs de poste dans la page « Ambassades ». */
    static final Pattern HEADS_OF_POST_LINK = Pattern.compile("href=\"([^\"]*chefs_de_poste[^\"]*\\.pdf)\"",
            Pattern.CASE_INSENSITIVE);
    /** Lien du PDF des ambassadeurs thématiques dans leur page. */
    static final Pattern THEMATIC_LINK = Pattern.compile("href=\"([^\"]*amb[^\"]*thematiques[^\"]*\\.pdf)\"",
            Pattern.CASE_INSENSITIVE);

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - ambassadeurs)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    void download(String embassiesUrl, String thematicUrl, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String headsUrl = fetchPageAndPdf(embassiesUrl, HEADS_OF_POST_LINK, rawDir.resolve(EMBASSIES_PAGE),
                rawDir.resolve(HEADS_OF_POST_PDF));
        Thread.sleep(delayMs);
        String thematicPdfUrl = fetchPageAndPdf(thematicUrl, THEMATIC_LINK, rawDir.resolve(THEMATIC_PAGE),
                rawDir.resolve(THEMATIC_PDF));
        Files.writeString(rawDir.resolve(SOURCES_FILE), headsUrl + "\n" + thematicPdfUrl + "\n", StandardCharsets.UTF_8);
        log.info("Listes du MEAE récupérées : {} et {}", headsUrl, thematicPdfUrl);
    }

    /** Télécharge la page, y trouve le lien du PDF, télécharge le PDF ; rend l'adresse du PDF. */
    private String fetchPageAndPdf(String pageUrl, Pattern link, Path pageFile, Path pdfFile)
            throws IOException, InterruptedException {
        byte[] page = get(pageUrl);
        Files.write(pageFile, page);
        String pdfUrl = pdfLink(new String(page, StandardCharsets.UTF_8), link, pageUrl);
        if (pdfUrl == null) {
            throw new IOException("Lien du PDF introuvable dans " + pageUrl + " : la page a probablement changé");
        }
        byte[] pdf = get(pdfUrl);
        if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IOException("Le fichier " + pdfUrl + " n'est pas un PDF");
        }
        Files.write(pdfFile, pdf);
        return pdfUrl;
    }

    /** Adresse absolue du premier lien qui correspond, ou {@code null}. */
    static String pdfLink(String html, Pattern link, String pageUrl) {
        Matcher m = link.matcher(html);
        return m.find() ? URI.create(pageUrl).resolve(m.group(1).replace("&amp;", "&")).toString() : null;
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("diplomatie.gouv.fr a répondu HTTP " + response.statusCode() + " (" + url + ")");
        }
        return response.body();
    }
}
