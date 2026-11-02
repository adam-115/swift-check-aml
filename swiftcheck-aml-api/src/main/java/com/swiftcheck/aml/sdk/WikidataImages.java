package com.swiftcheck.aml.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Photos de Wikimedia Commons pour des personnes connues par un identifiant externe : par exemple l'identifiant du
 * Parlement européen (propriété Wikidata {@code P1186}) → image principale de la personne ({@code P18}).
 *
 * <p>Le plugin déclare ensuite ces photos avec {@link FtmDatasetBuilder#addMedia} ; le core les télécharge, les signe
 * et lit leur licence et leur auteur sur Commons. La réponse de Wikidata est rendue telle quelle pour être déposée
 * dans {@code rawDir} avec les autres fichiers source.
 *
 * <p>Format TSV : une ligne par personne, {@code "124831"<TAB><http://commons.wikimedia.org/wiki/Special:FilePath/X.jpg>}.
 */
public final class WikidataImages {

    public static final String ENDPOINT = "https://query.wikidata.org/sparql";
    /** Paramètre de parser : adresse du point SPARQL ; vide pour ne pas chercher de photos. */
    public static final String PARAM_ENDPOINT = "wikidataEndpoint";
    /** Fichier de la réponse de Wikidata, déposé dans {@code rawDir} avec les autres fichiers source. */
    public static final String PHOTOS_FILE = "wikidata-images.tsv";
    /** Identification par défaut, avec un contact générique (jamais l'adresse d'un utilisateur). */
    public static final String DEFAULT_USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP - photos Wikimedia Commons)";
    /** « Propriété » des personnes connues par leur QID Wikidata (l'élément lui-même : {@code Q615}). */
    public static final String QID = "QID";
    /** Identifiants par requête : la requête reste courte, le service répond vite. */
    static final int BATCH = 250;
    private static final Pattern PROPERTY = Pattern.compile("P\\d+");
    private static final Pattern ITEM = Pattern.compile("Q\\d+");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]+");

    /** Envoi d'une requête SPARQL ; remplacé dans les tests. */
    @FunctionalInterface
    public interface Fetcher {
        String post(String query) throws IOException, InterruptedException;
    }

    /** Réponse brute (à archiver) et photos trouvées : identifiant externe → adresse de l'image sur Commons. */
    public record Result(String raw, Map<String, String> images) {
    }

    private WikidataImages() {
    }

    /**
     * Cherche l'image principale de chaque identifiant. Les identifiants qui ne sont pas des suites de lettres,
     * chiffres, « . », « _ » ou « - » sont ignorés (ils seraient insérés dans la requête).
     */
    public static Result byExternalId(String property, Collection<String> ids, Fetcher fetcher)
            throws IOException, InterruptedException {
        if (!QID.equals(property) && !PROPERTY.matcher(property).matches()) {
            throw new IllegalArgumentException("Propriété Wikidata invalide : " + property);
        }
        Pattern allowed = QID.equals(property) ? ITEM : SAFE_ID;
        List<String> safe = ids.stream().filter(id -> id != null && allowed.matcher(id).matches()).distinct().toList();
        StringBuilder raw = new StringBuilder();
        Map<String, String> images = new LinkedHashMap<>();
        for (int i = 0; i < safe.size(); i += BATCH) {
            String tsv = fetcher.post(query(property, safe.subList(i, Math.min(i + BATCH, safe.size()))));
            raw.append(tsv).append(tsv.endsWith("\n") ? "" : "\n");
            parseTsv(tsv).forEach(images::putIfAbsent);
        }
        return new Result(raw.toString(), images);
    }

    /**
     * Déclare la photo de chaque personne du dataset dont l'ID FtM est {@code dataset.buildEntityId(identifiant)}.
     * La réponse de Wikidata est écrite dans {@code saveTo} (s'il n'est pas {@code null}) pour être archivée avec la
     * source. Les photos sont un complément : une erreur est rendue dans le bilan, jamais levée.
     *
     * @return le bilan pour le message de l'exécution, vide s'il n'y a personne à chercher
     */
    public static String addPhotos(FtmDatasetBuilder dataset, String property, Collection<String> externalIds,
            Fetcher fetcher, Path saveTo) {
        java.util.Set<String> present = new java.util.HashSet<>();
        dataset.entities().forEach(e -> present.add(e.getId()));
        List<String> ids = externalIds.stream().filter(id -> present.contains(dataset.buildEntityId(id))).distinct().toList();
        if (ids.isEmpty() || fetcher == null) {
            return "";
        }
        try {
            Result result = byExternalId(property, ids, fetcher);
            if (saveTo != null) {
                Files.createDirectories(saveTo.toAbsolutePath().getParent());
                Files.writeString(saveTo, result.raw(), StandardCharsets.UTF_8);
            }
            // Licence et auteur : lus sur Commons par le core au téléchargement
            result.images().forEach((id, url) -> dataset.addMedia(dataset.buildEntityId(id), url, filePage(url), null));
            return String.format("Photos Wikimedia Commons : %d sur %d.", result.images().size(), ids.size());
        } catch (IOException | RuntimeException e) {
            return "Photos non cherchées (" + e.getMessage() + ").";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Photos non cherchées (interrompu).";
        }
    }

    /**
     * Fin de l'ID de chaque personne du dataset ({@code fr-pep-rca-Q3052772} → {@code Q3052772}) : l'identifiant
     * externe des plugins dont l'ID de personne est un seul morceau (QID, identifiant du Parlement européen).
     */
    public static List<String> personIdSuffixes(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getId().substring(e.getId().lastIndexOf('-') + 1)).toList();
    }

    /** Une image par identifiant ({@code wdt:} = meilleur rang : l'image « préférée » si elle est marquée). */
    static String query(String property, List<String> ids) {
        if (QID.equals(property)) {
            StringBuilder items = new StringBuilder();
            ids.forEach(id -> items.append(" wd:").append(id));
            return """
                    SELECT ?id (SAMPLE(?img) AS ?image) WHERE {
                      VALUES ?item {%s }
                      ?item wdt:P18 ?img .
                      BIND(STRAFTER(STR(?item), "/entity/") AS ?id)
                    }
                    GROUP BY ?id
                    """.formatted(items);
        }
        StringBuilder values = new StringBuilder();
        ids.forEach(id -> values.append(" \"").append(id).append('"'));
        return """
                SELECT ?id (SAMPLE(?img) AS ?image) WHERE {
                  VALUES ?id {%s }
                  ?person wdt:%s ?id ; wdt:P18 ?img .
                }
                GROUP BY ?id
                """.formatted(values, property);
    }

    /** Lit la réponse TSV ; les lignes illisibles sont ignorées. */
    static Map<String, String> parseTsv(String tsv) {
        Map<String, String> images = new LinkedHashMap<>();
        for (String line : tsv.split("\r?\n")) {
            String[] cells = line.split("\t");
            if (cells.length < 2 || !cells[0].startsWith("\"")) {
                continue;                               // en-tête « ?id ?image » ou ligne vide
            }
            String id = cells[0].replaceAll("^\"|\"(\\^\\^.*|@.*)?$", "");
            String url = commonsUrl(cells[1].replaceAll("^<|>$", ""));
            if (!id.isEmpty() && url != null) {
                images.put(id, url);
            }
        }
        return images;
    }

    /** Adresse https d'un fichier de Commons ({@code Special:FilePath}), ou {@code null} si ce n'en est pas une. */
    public static String commonsUrl(String url) {
        if (url == null) {
            return null;
        }
        String https = url.replaceFirst("^http://", "https://");
        return https.startsWith("https://commons.wikimedia.org/wiki/Special:FilePath/") ? https : null;
    }

    /** Page du fichier sur Commons (auteur, licence) : {@code …/Special:FilePath/X.jpg} → {@code …/wiki/File:X.jpg}. */
    public static String filePage(String commonsUrl) {
        return commonsUrl.replace("/wiki/Special:FilePath/", "/wiki/File:");
    }

    /**
     * Client d'après le paramètre {@value #PARAM_ENDPOINT} du parser : absent = {@value #ENDPOINT}, vide = pas de
     * photos ({@code null}, que {@link #addPhotos} accepte).
     */
    public static Fetcher fromParameters(Map<String, String> parameters, String userAgent) {
        String endpoint = parameters == null ? null : parameters.get(PARAM_ENDPOINT);
        if (endpoint == null) {
            return httpFetcher(ENDPOINT, userAgent);
        }
        return endpoint.isBlank() ? null : httpFetcher(endpoint.trim(), userAgent);
    }

    /**
     * Client HTTP vers le point SPARQL de Wikidata, en POST, avec trois essais sur les erreurs passagères (429, 5xx).
     *
     * @param userAgent identification du plugin, avec un contact générique (jamais l'adresse d'un utilisateur)
     */
    public static Fetcher httpFetcher(String endpoint, String userAgent) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return query -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(90))
                    .header("Accept", "text/tab-separated-values")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .build();
            IOException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IOException("Wikidata a répondu HTTP " + response.statusCode());
                if (response.statusCode() != 429 && response.statusCode() < 500) {
                    break;
                }
                Thread.sleep(5_000L * attempt);
            }
            throw last;
        };
    }

}
