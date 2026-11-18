package com.swiftcheck.aml.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;

/**
 * Tient à jour le manifest de Yente ({@code manifest.yml}) : la liste des datasets que Yente doit indexer.
 *
 * <p>Format géré :
 * <pre>
 * datasets:
 *   - name: default                 # collection interrogée par défaut : tous les datasets
 *     title: SwiftCheck – toutes les listes
 *     children: [eu_sanctions, un_sanctions]
 *   - name: eu_sanctions
 *     title: Sanctions financières UE
 *     path: /data/eu_sanctions/eu_sanctions.ndjson   # chemin vu depuis le conteneur Yente
 * </pre>
 * Le fichier est créé s'il n'existe pas. Les autres clés éventuellement présentes sont conservées.
 *
 * <p>Sans état : la configuration est passée à chaque appel. Les méthodes qui écrivent le fichier sont
 * {@code synchronized} pour que deux publications simultanées ne l'écrivent pas en même temps.
 */
@Service
public class YenteManifestService {

    private static final Logger log = LoggerFactory.getLogger(YenteManifestService.class);
    private static final String DEFAULT_COLLECTION = "default";
    private static final Pattern VALID_DATASET_NAME = Pattern.compile("^[a-z0-9_\\-]+$");
    private static final int TOUCH_ATTEMPTS = 5;

    private final ObjectMapper yaml = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Ajoute ou met à jour un dataset dans le manifest. Yente n'est pas appelé : avec
     * {@code YENTE_AUTO_REINDEX=true}, sa tâche planifiée ({@code YENTE_CRONTAB}, toutes les 2 minutes) relit
     * {@code manifest.yml} et réindexe seulement les datasets dont le fichier a changé (version = date de
     * modification du {@code .ndjson}).
     */
    public synchronized void addDataset(String datasetName, String title, ApplicationConfigEntity config)
            throws IOException {
        Map<String, Object> manifest = read(config);
        List<Map<String, Object>> datasets = datasets(manifest);

        datasets.removeIf(d -> datasetName.equals(d.get("name")));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", datasetName);
        entry.put("title", title == null || title.isBlank() ? datasetName : title);
        entry.put("path", containerPath(datasetName, config));
        datasets.add(entry);

        List<String> children = defaultChildren(datasets);
        boolean newChild = !children.contains(datasetName);
        if (newChild) {
            children.add(datasetName);
        }
        write(manifest, config);
    }

    /**
     * Retire un dataset du manifest (le fichier publié reste sur le disque). Au passage suivant de sa tâche
     * planifiée, Yente ne le sert plus et supprime son index.
     */
    public synchronized void removeDataset(String datasetName, ApplicationConfigEntity config) throws IOException {
        Map<String, Object> manifest = read(config);
        List<Map<String, Object>> datasets = datasets(manifest);
        boolean removed = datasets.removeIf(d -> datasetName.equals(d.get("name")));
        defaultChildren(datasets).remove(datasetName);
        if (removed) {
            write(manifest, config);
        }
    }

    /**
     * « Réindexer ce dataset » : met la date de modification du fichier publié à maintenant. Yente y voit une
     * nouvelle version et ne reconstruit que cet index à son prochain passage ({@code YENTE_CRONTAB}).
     * Le contenu du fichier n'est pas modifié.
     *
     * @throws IllegalStateException nom invalide, aucun fichier publié, ou dataset absent du manifest
     *                               (message affiché tel quel)
     */
    public synchronized void reindexDataset(String datasetName, ApplicationConfigEntity config) {
        // Le nom vient de l'URL : il ne doit désigner aucun autre fichier que <dossier>/<nom>/<nom>.ndjson
        if (datasetName == null || !VALID_DATASET_NAME.matcher(datasetName).matches()) {
            throw new IllegalStateException("Nom de dataset invalide : « " + datasetName + " ».");
        }
        Path file = Path.of(config.getYenteDataFolder(), datasetName, datasetName + ".ndjson");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Aucun fichier publié pour " + datasetName + " : lancez d'abord le parser.");
        }
        try {
            boolean inManifest = datasets(read(config)).stream().anyMatch(d -> datasetName.equals(d.get("name")));
            if (!inManifest) {
                throw new IllegalStateException(datasetName + " n'est pas dans le manifest de Yente (parser désactivé ?).");
            }
            touch(file);
        } catch (IOException e) {
            throw new IllegalStateException("Réindexation de " + datasetName + " impossible : " + e.getMessage());
        }
        log.info("Réindexation de {} demandée : Yente la fera à son prochain passage", datasetName);
    }

    /**
     * Sous Windows, un fichier ouvert par un autre programme (Yente qui le lit, antivirus) peut refuser le
     * changement de date quelques secondes : quelques nouvelles tentatives, comme {@code DatasetPublisher}.
     */
    private static void touch(Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= TOUCH_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Bouton « Tout reconstruire » : {@code POST /updatez}, qui force la reconstruction de <b>tous</b> les index
     * (même inchangés). Long : à réserver à une réparation. Yente répond tout de suite et reconstruit en tâche
     * de fond.
     *
     * @throws IllegalStateException si Yente est injoignable ou refuse (message affiché tel quel)
     */
    public void reindex(ApplicationConfigEntity config) {
        HttpResponse<String> response;
        try {
            response = callUpdatez(config);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Réindexation interrompue.");
        } catch (Exception e) {
            throw new IllegalStateException("Yente injoignable (" + config.getYenteUrl() + ") : " + e.getMessage());
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException("Yente refuse le jeton de mise à jour (HTTP " + status
                    + ") : il doit être identique à YENTE_UPDATE_TOKEN (écran Configuration).");
        }
        if (status >= 300) {
            throw new IllegalStateException("Yente a refusé la réindexation (HTTP " + status + ") : " + response.body());
        }
        log.info("Réindexation de Yente demandée ({})", config.getYenteUrl());
    }

    /**
     * {@code POST /updatez?token=...} : Yente réindexe les listes du manifest <b>tel qu'il l'a lu en dernier</b>
     * (au démarrage ou au passage de {@code YENTE_CRONTAB}) ; cet appel ne relit pas {@code manifest.yml}.
     */
    private HttpResponse<String> callUpdatez(ApplicationConfigEntity config) throws IOException, InterruptedException {
        String url = trimSlash(config.getYenteUrl()) + "/updatez?token="
                + URLEncoder.encode(nullToEmpty(config.getYenteUpdateToken()), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Chemin du fichier publié tel que Yente le voit, ex. {@code /data/eu_sanctions/eu_sanctions.ndjson}. */
    static String containerPath(String datasetName, ApplicationConfigEntity config) {
        return trimSlash(config.getYenteContainerDataPath()) + "/" + datasetName + "/" + datasetName + ".ndjson";
    }

    private Map<String, Object> read(ApplicationConfigEntity config) throws IOException {
        Path file = Path.of(config.getYenteManifestPath());
        if (!Files.exists(file) || Files.size(file) == 0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> manifest = yaml.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
        });
        return manifest == null ? new LinkedHashMap<>() : manifest;
    }

    private void write(Map<String, Object> manifest, ApplicationConfigEntity config) throws IOException {
        Path file = Path.of(config.getYenteManifestPath());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        yaml.writeValue(file.toFile(), manifest);
    }

    /** Liste {@code datasets} du manifest, créée si absente. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> datasets(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.computeIfAbsent("datasets", k -> new ArrayList<>());
    }

    /** Enfants de la collection « default » (créée en tête de liste si absente). */
    @SuppressWarnings("unchecked")
    private static List<String> defaultChildren(List<Map<String, Object>> datasets) {
        for (Map<String, Object> dataset : datasets) {
            if (DEFAULT_COLLECTION.equals(dataset.get("name"))) {
                return (List<String>) dataset.computeIfAbsent("children", k -> new ArrayList<>());
            }
        }
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("name", DEFAULT_COLLECTION);
        collection.put("title", "SwiftCheck – toutes les listes");
        collection.put("children", new ArrayList<String>());
        datasets.add(0, collection);
        return defaultChildren(datasets);
    }

    private static String trimSlash(String value) {
        String v = nullToEmpty(value).trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
