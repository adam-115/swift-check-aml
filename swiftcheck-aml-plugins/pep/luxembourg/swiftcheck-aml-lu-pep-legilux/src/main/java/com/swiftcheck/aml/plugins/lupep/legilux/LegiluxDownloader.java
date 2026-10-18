package com.swiftcheck.aml.plugins.lupep.legilux;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Récupère les données brutes sur Legilux et les dépose dans {@code rawDir}, où le core les signe et les archive
 * (source {@code PLUGIN_MANAGED}) :
 * <ol>
 *   <li>{@value LegiluxActs#ACT_LIST_FILE} : la réponse du point SPARQL, la liste des publications de nomination
 *       et de démission ;</li>
 *   <li>{@value LegiluxActs#TEXTS_FILE} : le texte XML officiel de chacune, réuni dans une seule archive ZIP,
 *       plutôt que 1 500 petits fichiers. Les entrées sont rangées par nom et datées du 1er janvier 1970 :
 *       deux téléchargements identiques donnent le même fichier, donc la même empreinte (statut UNCHANGED).</li>
 * </ol>
 * Seule l'adresse du point SPARQL est configurée (paramètre {@code endpoint}) ; l'adresse du texte d'un acte se
 * déduit de son identifiant ELI.
 */
final class LegiluxDownloader {

    /**
     * Publications administratives (type PA) de nomination, de renouvellement ou de démission dans les institutions
     * dont les dirigeants sont PEP. {@code {since}} est remplacé par la date de début.
     */
    static final String QUERY = """
            PREFIX jolux: <http://data.legilux.public.lu/resource/ontology/jolux#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            SELECT DISTINCT ?act ?date ?title WHERE {
              ?act a jolux:Act ;
                   jolux:typeDocument <http://data.legilux.public.lu/resource/authority/resource-type/PA> ;
                   jolux:dateDocument ?date ;
                   jolux:isRealizedBy ?expression .
              ?expression jolux:title ?title .
              FILTER(?date >= "{since}"^^xsd:date)
              FILTER(REGEX(?title, "^ *(Conseil d.[ÉE]tat|Magistrature|Cour des comptes|Banque centrale du Luxembourg|Commission de surveillance du secteur financier|Commissariat aux assurances)", "i"))
              FILTER(REGEX(?title, "nomination|démission", "i"))
            }
            ORDER BY ?date ?act
            """;

    private static final int MAX_ATTEMPTS = 3;

    /** Pause entre deux téléchargements de texte : on ne surcharge pas le serveur de Legilux. */
    private static final Duration PAUSE = Duration.ofMillis(50);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)          // http → https
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Télécharge la liste des actes publiés depuis {@code since}, puis le texte de chacun.
     *
     * @return le nombre d'actes dont le texte n'a pas pu être téléchargé (actes sans version XML)
     */
    int download(String endpoint, LocalDate since, Path rawDir) throws Exception {
        // 1. Liste des actes (réponse SPARQL au format CSV, gardée telle quelle)
        String query = QUERY.replace("{since}", since.toString());
        URI listUri = URI.create(endpoint + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        Path actList = rawDir.resolve(LegiluxActs.ACT_LIST_FILE);
        Files.write(actList, get(listUri, "text/csv"));

        // 2. Texte XML de chaque acte, dans une seule archive
        List<LegiluxActs.Act> acts = LegiluxActs.readActList(actList);
        List<LegiluxActs.Act> sorted = new ArrayList<>(acts);
        sorted.sort((a, b) -> LegiluxActs.textEntryName(a.uri()).compareTo(LegiluxActs.textEntryName(b.uri())));
        int missing = 0;
        try (OutputStream file = Files.newOutputStream(rawDir.resolve(LegiluxActs.TEXTS_FILE));
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (LegiluxActs.Act act : sorted) {
                byte[] xml;
                try {
                    xml = get(LegiluxActs.textUri(act.uri()), "application/xml");
                } catch (IOException e) {
                    missing++;                                    // l'acte sera signalé « sans texte »
                    continue;
                }
                ZipEntry entry = new ZipEntry(LegiluxActs.textEntryName(act.uri()));
                entry.setLastModifiedTime(FileTime.fromMillis(0));   // archive identique d'un jour à l'autre
                zip.putNextEntry(entry);
                zip.write(xml);
                zip.closeEntry();
                Thread.sleep(PAUSE.toMillis());
            }
        }
        return missing;
    }

    /** GET avec 3 tentatives pour une erreur réseau ou serveur ; une erreur 4xx n'est pas retentée. */
    private byte[] get(URI uri, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Accept", accept)
                .header("User-Agent", "SwiftCheck-AML (collecte des nominations publiées au Journal officiel)")
                .GET()
                .build();
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                if (status >= 400 && status < 500) {
                    throw new IOException("Legilux a refusé la requête (HTTP " + status + ") : " + uri);
                }
                lastError = new IOException("Erreur du serveur Legilux (HTTP " + status + ") : " + uri);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("Legilux a refusé")) {
                    throw e;
                }
                lastError = e;
            }
            Thread.sleep(2000L * attempt);
        }
        throw lastError;
    }
}
