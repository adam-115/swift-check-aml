package com.swiftcheck.aml.plugins.frpep.senate;

import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDate;
import static com.swiftcheck.aml.sdk.DateNormalizer.toIsoDateOrFail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.swiftcheck.aml.sdk.CsvRecordReader;

/**
 * Fichiers open data des sénateurs (data.senat.fr), CSV séparés par des virgules, encodés en Windows-1252,
 * précédés de lignes de commentaire commençant par {@code %} (la requête qui a produit le fichier) :
 * <ul>
 *   <li>{@value #GENERAL_FILE} : un sénateur par ligne, actuels et anciens depuis 1959 ;
 * <pre>
 * Matricule,Qualité,Nom usuel,Prénom usuel,État,Date naissance,Date de décès,Groupe politique,…,Circonscription,Fonction au Bureau du Sénat,…
 * 86034E,M.,Larcher,Gérard,ACTIF,1949-09-14 00:00:00.0,,Les Républicains,,Aucune,Yvelines,Président du Sénat,…
 * </pre></li>
 *   <li>{@value #MANDATES_FILE} : un mandat de sénateur par ligne ;
 * <pre>
 * Matricule,…,Identifiant mandat,Date d'élection,Date de début de mandat,…,Date de fin de mandat,…,Motif fin de mandat,…
 * 14133K,…,2020-10-01 00:00:00.0,…,2026-09-30 00:00:00.0,,Ne se représente pas,
 * </pre></li>
 * </ul>
 */
final class SenateFiles {

    static final String GENERAL_FILE = "ODSEN_GENERAL.csv";
    static final String MANDATES_FILE = "ODSEN_ELUSEN.csv";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - senateurs)";

    /** Un sénateur, actuel ou ancien. */
    record Senator(String id, String title, String firstName, String lastName, boolean active, String birthDate,
            String deathDate, String group, String constituency, String bureauFunction) {
    }

    /** Un mandat de sénateur ; fin {@code null} = en cours. */
    record Mandate(String senatorId, String id, String start, String end) {

        boolean current() {
            return end == null;
        }
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    private SenateFiles() {
    }

    /** Télécharge les deux fichiers dans {@code rawDir}, où le core les signe et les archive. */
    static void download(Fetcher fetcher, String generalUrl, String mandatesUrl, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve(GENERAL_FILE), fetcher.get(generalUrl));
        Files.write(rawDir.resolve(MANDATES_FILE), fetcher.get(mandatesUrl));
    }

    static Map<String, Senator> senators(Path rawDir) throws Exception {
        Map<String, Senator> senators = new LinkedHashMap<>();
        CsvRecordReader.readEach(withoutComments(rawDir.resolve(GENERAL_FILE)), row -> {
            String where = GENERAL_FILE + " ligne " + row.lineNumber();
            senators.put(row.get("Matricule"), new Senator(row.get("Matricule"), row.get("Qualité"),
                    row.get("Prénom usuel"), row.get("Nom usuel"), "ACTIF".equals(row.get("État")),
                    toIsoDate(row.get("Date naissance")), toIsoDateOrFail(row.get("Date de décès"), where),
                    row.get("Groupe politique"), row.get("Circonscription"), row.get("Fonction au Bureau du Sénat")));
        });
        if (senators.isEmpty()) {
            throw new IllegalStateException(GENERAL_FILE + " ne contient aucun sénateur : fichier vide ou format changé.");
        }
        return senators;
    }

    /** Mandats par matricule. Une date de fin illisible arrête la lecture : elle ferait passer le mandat pour « en cours ». */
    static Map<String, List<Mandate>> mandates(Path rawDir) throws Exception {
        Map<String, List<Mandate>> mandates = new LinkedHashMap<>();
        CsvRecordReader.readEach(withoutComments(rawDir.resolve(MANDATES_FILE)), row -> {
            String where = MANDATES_FILE + " ligne " + row.lineNumber();
            mandates.computeIfAbsent(row.get("Matricule"), k -> new ArrayList<>()).add(new Mandate(row.get("Matricule"),
                    row.get("Identifiant mandat"), toIsoDate(row.get("Date de début de mandat")),
                    toIsoDateOrFail(row.get("Date de fin de mandat"), where)));
        });
        return mandates;
    }

    /** Copie du fichier sans les lignes {@code %} : la première ligne restante est l'en-tête. */
    private static Path withoutComments(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalStateException(file.getFileName() + " absent de " + file.getParent()
                    + " : le téléchargement n'a pas eu lieu.");
        }
        byte[] bytes = Files.readAllBytes(file);
        int start = 0;
        while (start < bytes.length && bytes[start] == '%') {
            while (start < bytes.length && bytes[start] != '\n') {
                start++;
            }
            start++;
        }
        Path copy = Files.createTempFile("odsen-", ".csv");
        copy.toFile().deleteOnExit();
        Files.write(copy, java.util.Arrays.copyOfRange(bytes, Math.min(start, bytes.length), bytes.length));
        return copy;
    }

    static Fetcher httpFetcher() {
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
