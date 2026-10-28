package com.swiftcheck.aml.plugins.frpep.government;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Récupère l'open data de la HATVP et le dépose dans {@code rawDir}, où le core le signe et l'archive :
 * <ol>
 *   <li>la liste des déclarations, enregistrée sous {@value #LIST_FILE} ;</li>
 *   <li>pour chaque membre du Gouvernement, sa déclaration la plus utile (voir {@link HatvpList.Member#bestDeclaration()}),
 *       enregistrée sous son nom de fichier ({@code lecornu-sebastien-di34789-gouvernement.xml}).</li>
 * </ol>
 * Les déclarations sont lues l'une après l'autre, avec une pause entre deux appels. Une déclaration illisible
 * n'arrête pas l'exécution : le membre est gardé sans date de naissance ni dates de fonction.
 */
class HatvpDownloader {

    private static final Logger log = LoggerFactory.getLogger(HatvpDownloader.class);

    static final String LIST_FILE = "liste.csv";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - membres du Gouvernement)";

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    private final Fetcher fetcher;
    private final long delayMillis;

    HatvpDownloader(long delayMillis) {
        this(httpFetcher(), delayMillis);
    }

    HatvpDownloader(Fetcher fetcher, long delayMillis) {
        this.fetcher = fetcher;
        this.delayMillis = delayMillis;
    }

    /** @return les adresses des déclarations qui n'ont pas pu être lues */
    List<String> download(String listUrl, String declarationsUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Path list = rawDir.resolve(LIST_FILE);
        Files.write(list, fetcher.get(listUrl));
        List<HatvpList.Member> members;
        try {
            members = HatvpList.members(list);
        } catch (Exception e) {
            throw new IOException("Liste HATVP illisible (" + listUrl + ") : " + e.getMessage(), e);
        }
        if (members.isEmpty()) {
            throw new IOException("Aucune déclaration « gouvernement » dans " + listUrl + " : le fichier a peut-être changé.");
        }

        String base = declarationsUrl.endsWith("/") ? declarationsUrl : declarationsUrl + "/";
        List<String> failed = new ArrayList<>();
        for (HatvpList.Member m : members) {
            Optional<HatvpList.Declaration> declaration = m.bestDeclaration();
            if (declaration.isEmpty()) {
                continue;                                   // déclarations pas encore publiées
            }
            Thread.sleep(delayMillis);
            String url = base + declaration.get().xmlFile();
            try {
                Files.write(rawDir.resolve(declaration.get().xmlFile()), fetcher.get(url));
            } catch (IOException e) {
                log.warn("Déclaration non lue : {} ({})", url, e.getMessage());
                failed.add(url);
            }
        }
        return failed;
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
