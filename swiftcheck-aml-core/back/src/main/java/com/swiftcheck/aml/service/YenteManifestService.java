package com.swiftcheck.aml.service;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

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

    private final ObjectMapper yaml = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Délai entre une modification du manifest et la demande de réindexation : il doit dépasser l'intervalle
     * de relecture du manifest par Yente ({@code YENTE_CRONTAB}, chaque minute).
     */
    @Value("${swiftcheck.yente.manifest-refresh-delay:PT90S}")
    private Duration manifestRefreshDelay = Duration.ofSeconds(90);

    /** Un seul thread, en tâche de fond, pour les réindexations différées. */
    private final ScheduledExecutorService delayedReload = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yente-delayed-reload");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Ajoute ou met à jour un dataset dans le manifest, puis demande à Yente de réindexer.
     *
     * <p>Si l'entrée du manifest change (nouveau dataset, autre titre…), la réindexation est demandée
     * après {@link #manifestRefreshDelay} : Yente ne relit {@code manifest.yml} qu'à son démarrage et au passage
     * de sa tâche planifiée ({@code YENTE_CRONTAB}, chaque minute dans notre docker-compose). Un appel immédiat
     * réindexerait avec l'ancien manifest, sans le nouveau dataset. Si seule la liste publiée a changé,
     * l'entrée est identique et la réindexation est demandée tout de suite.
     */
    public synchronized void addDataset(String datasetName, String title, ApplicationConfigEntity config)
            throws IOException {
        Map<String, Object> manifest = read(config);
        List<Map<String, Object>> datasets = datasets(manifest);

        Map<String, Object> previous = datasets.stream()
                .filter(d -> datasetName.equals(d.get("name")))
                .findFirst()
                .orElse(null);
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

        if (newChild || !entry.equals(previous)) {
            reloadYenteAfterManifestRefresh(config);
        } else {
            reloadYente(config);
        }
    }

    /** Retire un dataset du manifest (le fichier publié reste sur le disque), puis réindexe Yente. */
    public synchronized void removeDataset(String datasetName, ApplicationConfigEntity config) throws IOException {
        Map<String, Object> manifest = read(config);
        List<Map<String, Object>> datasets = datasets(manifest);
        boolean removed = datasets.removeIf(d -> datasetName.equals(d.get("name")));
        defaultChildren(datasets).remove(datasetName);
        if (removed) {
            write(manifest, config);
            reloadYenteAfterManifestRefresh(config);   // le manifest a changé : même raison que dans addDataset
        }
    }

    /**
     * Demande la réindexation une fois que Yente a eu le temps de relire le manifest. L'appel se fait en
     * tâche de fond : la publication n'attend pas.
     */
    private void reloadYenteAfterManifestRefresh(ApplicationConfigEntity config) {
        log.info("Manifest modifié : réindexation de Yente demandée dans {} s, le temps qu'il relise manifest.yml",
                manifestRefreshDelay.toSeconds());
        delayedReload.schedule(() -> reloadYente(config), manifestRefreshDelay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Pour les tests : délai plus court que le délai de production. */
    void setManifestRefreshDelay(Duration delay) {
        this.manifestRefreshDelay = delay;
    }

    @PreDestroy
    void stopDelayedReload() {
        delayedReload.shutdownNow();
    }

    /**
     * Demande à Yente de réindexer ({@code POST /updatez?token=...}).
     * Un échec est seulement journalisé : le fichier est publié, et Yente le prendra en compte
     * à son prochain rechargement.
     */
    public void reloadYente(ApplicationConfigEntity config) {
        try {
            HttpResponse<String> response = callUpdatez(config);
            if (response.statusCode() >= 300) {
                log.warn("Yente a refusé le rechargement (HTTP {}) : {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Rechargement de Yente impossible ({}) : {}", config.getYenteUrl(), e.getMessage());
        }
    }

    /**
     * Bouton « Réindexer Yente » : même appel que {@link #reloadYente}, mais une erreur est renvoyée à
     * l'utilisateur au lieu d'être seulement journalisée. Yente répond tout de suite et réindexe en tâche de fond.
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
