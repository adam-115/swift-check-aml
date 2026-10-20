package com.swiftcheck.aml.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Télécharge un fichier en HTTP(S) vers un fichier local.
 *
 * <p>Règles :
 * <ul>
 *   <li>3 tentatives en cas d'erreur réseau ou d'erreur serveur (5xx), avec une pause croissante ;</li>
 *   <li>pas de nouvelle tentative pour une erreur 4xx (URL fausse, accès refusé) : elle ne se corrigera pas seule ;</li>
 *   <li><b>jamais de copie de secours</b> : si le téléchargement échoue, l'exécution échoue. Réutiliser un ancien
 *       fichier sans le dire ferait croire qu'une liste est à jour alors qu'elle ne l'est pas.</li>
 * </ul>
 * Sans état : le client HTTP est immuable et peut servir à plusieurs téléchargements en parallèle.
 */
@Component
public class HttpDownloader {

    private static final Logger log = LoggerFactory.getLogger(HttpDownloader.class);
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Identité envoyée aux sites officiels : certains refusent un client qui ne se présente pas comme un
     * navigateur. Même valeur que l'ancien SourceDownloadService.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)          // certains sites officiels gèrent mal HTTP/2
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Type de contenu demandé, déduit de l'URL. Indispensable pour la Commission européenne : à une requête
     * qui accepte du HTML (comme celle d'un navigateur), webgate.ec.europa.eu répond HTTP 500 ; en demandant
     * du XML, il renvoie le fichier. Mêmes valeurs que l'ancien SourceDownloadService.
     */
    static String acceptHeader(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("xml")) {
            return "application/xml, text/xml;q=0.9, */*;q=0.8";
        }
        if (lower.contains("json")) {
            return "application/json, */*;q=0.8";
        }
        return "*/*";
    }

    /**
     * Vrai si l'adresse configurée désigne une page web ({@code .html} ou {@code .htm}, paramètres ignorés) :
     * une réponse HTML est alors normale. Pour toute autre adresse (CSV, XML, lien data.public.lu…),
     * une réponse HTML reste une erreur.
     */
    static boolean isHtmlPage(String url) {
        String path = URI.create(url).getPath();
        String lower = path == null ? "" : path.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /**
     * @param url    adresse du fichier
     * @param target fichier local à créer (remplacé s'il existe)
     * @throws IOException si le fichier n'a pas pu être téléchargé après toutes les tentatives
     */
    public void download(String url, Path target) throws IOException, InterruptedException {
        download(url, target, false);
    }

    /**
     * @param url          adresse du fichier
     * @param target       fichier local à créer (remplacé s'il existe)
     * @param htmlExpected vrai si la source est déclarée comme une page web (ParserKind.WEB_PAGE)
     * @throws IOException si le fichier n'a pas pu être téléchargé après toutes les tentatives
     */
    public void download(String url, Path target, boolean htmlExpected) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", acceptHeader(url))
                .header("Accept-Encoding", "identity")    // fichier brut, non compressé : l'empreinte SHA-256 porte sur le vrai contenu
                .GET()
                .build();

        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
                int status = response.statusCode();
                if (status == 200) {
                    // Une page HTML à la place du fichier (page d'erreur ou de connexion) : inutile de réessayer
                    // ni de donner cette page au plugin. Sauf si la source est elle-même une page web
                    // (ex. gouvernement.lu/fr/gouvernement.html ou parser WEB_PAGE) : le HTML est alors le fichier attendu.
                    String type = response.headers().firstValue("Content-Type").orElse("");
                    if (type.toLowerCase().startsWith("text/html") && !htmlExpected && !isHtmlPage(url)) {
                        throw new IOException("Le serveur renvoie une page HTML au lieu du fichier : " + response.uri());
                    }
                    return;
                }
                if (status >= 400 && status < 500) {
                    throw new IOException("Le serveur a refusé le téléchargement (HTTP " + status + ") : " + url);
                }
                lastError = new IOException("Erreur du serveur (HTTP " + status + ") : " + url);
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().startsWith("Le serveur a refusé")
                        || e.getMessage().startsWith("Le serveur renvoie une page HTML"))) {
                    throw e;
                }
                lastError = e;
            }
            log.warn("Téléchargement {} : tentative {}/{} échouée ({})", url, attempt, MAX_ATTEMPTS, lastError.getMessage());
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(2000L * attempt);
            }
        }
        throw lastError;
    }
}
