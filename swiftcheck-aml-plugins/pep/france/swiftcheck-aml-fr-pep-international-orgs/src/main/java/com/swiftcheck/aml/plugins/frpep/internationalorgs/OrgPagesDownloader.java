package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugins.frpep.internationalorgs.OrgConfig.Organization;

/**
 * Télécharge la page officielle de chaque organisation et la dépose dans {@code rawDir} ({@code <clé>-<n>.html}), où
 * le core la signe et l'archive. Une page inaccessible n'arrête pas l'exécution : elle est citée dans le message, et
 * les autres organisations sont publiées (le contrôle qualité du core bloque une baisse de plus de 20 %).
 */
class OrgPagesDownloader {

    private static final Logger log = LoggerFactory.getLogger(OrgPagesDownloader.class);

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - organisations internationales)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** @return les pages qui n'ont pas pu être lues (« clé : raison ») */
    List<String> download(List<Organization> orgs, long delayMs, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        List<String> failed = new ArrayList<>();
        boolean first = true;
        for (Organization org : orgs) {
            for (int i = 0; i < org.pages().size(); i++) {
                String url = org.pages().get(i).url();
                if (!first) {
                    Thread.sleep(delayMs);
                }
                first = false;
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                            .header("User-Agent", USER_AGENT).header("Accept-Language", "fr,en;q=0.8").GET().build();
                    HttpResponse<String> response = http.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (response.statusCode() != 200) {
                        failed.add(org.key() + " : HTTP " + response.statusCode());
                        continue;
                    }
                    Files.writeString(rawDir.resolve(OrgConfig.pageFile(org, i)), response.body(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    failed.add(org.key() + " : " + e.getMessage());
                }
            }
        }
        log.info("Pages des organisations internationales : {} lues, {} en échec", countPages(orgs) - failed.size(),
                failed.size());
        return failed;
    }

    private static int countPages(List<Organization> orgs) {
        return orgs.stream().mapToInt(o -> o.pages().size()).sum();
    }
}
