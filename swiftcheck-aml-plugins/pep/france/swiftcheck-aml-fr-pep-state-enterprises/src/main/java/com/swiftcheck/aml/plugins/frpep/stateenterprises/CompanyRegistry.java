package com.swiftcheck.aml.plugins.frpep.stateenterprises;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * API Recherche d'entreprises (DINUM, données RNE et Sirene, Licence ouverte) : chaque réponse est déposée telle quelle
 * à la racine de {@code rawDir} (préfixe {@code companies-}), puis relue sans réseau. Le core n'archive que les
 * fichiers de la racine : un sous-dossier ne serait pas conservé comme preuve. Une réponse {@code /search} contient, pour chaque entreprise,
 * ses dirigeants inscrits au registre national des entreprises (nom, prénoms, mois de naissance, qualité).
 */
final class CompanyRegistry {

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Préfixe des fichiers déposés à la racine de {@code rawDir}. */
    static final String PREFIX = "companies-";

    /** L'API accepte 7 requêtes par seconde : une toutes les 200 ms (0 dans les tests). */
    static long pauseMs = 200;

    private static final int PER_PAGE = 25;
    private static final int MAX_PAGES = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CompanyRegistry() {
    }

    /** Fiche d'une entreprise, par son SIREN : {@code companies-siren-<siren>.json}. */
    static void downloadSiren(Fetcher fetcher, String api, String siren, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve(PREFIX + "siren-" + siren + ".json"), fetcher.get(api + "?per_page=1&q=" + siren));
        Thread.sleep(pauseMs);
    }

    /**
     * Toutes les entreprises actives répondant au filtre (ex. {@code nature_juridique=4110}), page par page :
     * {@code companies-<name>-<page>.json}.
     *
     * @return le nombre total d'entreprises annoncé par l'API
     */
    static int downloadFilter(Fetcher fetcher, String api, String name, String filter, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        int total = 0;
        for (int page = 1, pages = 1; page <= pages && page <= MAX_PAGES; page++) {
            byte[] body = fetcher.get(api + "?" + filter + "&etat_administratif=A&per_page=" + PER_PAGE + "&page=" + page);
            Files.write(rawDir.resolve(PREFIX + name + "-" + page + ".json"), body);
            JsonNode root = JSON.readTree(body);
            pages = root.path("total_pages").asInt(1);
            total = root.path("total_results").asInt(0);
            Thread.sleep(pauseMs);
        }
        return total;
    }

    /** Fiche déposée par {@link #downloadSiren}, ou {@code null} si l'API n'a pas trouvé ce SIREN. */
    static JsonNode readSiren(Path rawDir, String siren) throws IOException {
        Path file = rawDir.resolve(PREFIX + "siren-" + siren + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        for (JsonNode company : JSON.readTree(file.toFile()).path("results")) {
            if (siren.equals(company.path("siren").asText())) {
                return company;
            }
        }
        return null;
    }

    /** Entreprises déposées par {@link #downloadFilter} sous ce nom, par SIREN, dans l'ordre des pages. */
    static Map<String, JsonNode> readFilter(Path rawDir, String name) throws IOException {
        Map<String, JsonNode> companies = new LinkedHashMap<>();
        if (!Files.isDirectory(rawDir)) {
            return companies;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rawDir, PREFIX + name + "-*.json")) {
            stream.forEach(files::add);
        }
        files.sort((a, b) -> Integer.compare(page(a, name), page(b, name)));
        for (Path file : files) {
            for (JsonNode company : JSON.readTree(file.toFile()).path("results")) {
                companies.putIfAbsent(company.path("siren").asText(), company);
            }
        }
        return companies;
    }

    private static int page(Path file, String name) {
        String n = file.getFileName().toString();
        try {
            return Integer.parseInt(n.substring(PREFIX.length() + name.length() + 1, n.length() - ".json".length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
