package com.swiftcheck.aml.yente;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.dto.YenteDatasetStatusDTO;
import com.swiftcheck.aml.dto.YenteEventDTO;
import com.swiftcheck.aml.dto.YenteStatusDTO;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.service.AlertService;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.YenteManifestService;
import com.swiftcheck.aml.yente.DatasetStateResolver.DatasetInputs;
import com.swiftcheck.aml.yente.YenteModels.AuditEvent;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.ClusterInfo;
import com.swiftcheck.aml.yente.YenteModels.Health;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/**
 * Écran « Yente » : rapproche, dataset par dataset, ce que le core attend (parsers actifs, publications, manifest,
 * fichiers publiés) et ce que Yente sert réellement (catalog, index, journal d'indexation).
 *
 * <p>Chaque source est lue séparément : si Yente ou Elasticsearch ne répond pas, l'erreur est renvoyée dans l'état
 * et le reste est calculé avec ce qui est connu. Le résultat est gardé 3 secondes (plusieurs écrans ouverts).
 */
@Service
public class YenteMonitorService {

    private static final Logger log = LoggerFactory.getLogger(YenteMonitorService.class);
    private static final Duration CACHE = Duration.ofSeconds(3);

    private final YenteMonitorClient client;
    private final ExpectedEntityCounter counter;
    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;
    private final ApplicationConfigService configService;
    private final YenteManifestService manifest;

    private YenteStatusDTO cached;
    private Instant cachedAt = Instant.EPOCH;

    public YenteMonitorService(YenteMonitorClient client, ExpectedEntityCounter counter, ParserRepository parsers,
            ParserExecutionRepository executions, ApplicationConfigService configService, YenteManifestService manifest) {
        this.client = client;
        this.counter = counter;
        this.parsers = parsers;
        this.executions = executions;
        this.configService = configService;
        this.manifest = manifest;
    }

    public synchronized YenteStatusDTO snapshot() {
        Instant now = Instant.now();
        if (cached != null && cachedAt.plus(CACHE).isAfter(now)) {
            return cached;
        }
        cached = compute(now);
        cachedAt = Instant.now();   // depuis la fin du calcul : une source lente ne relance pas un calcul par requête en attente
        return cached;
    }

    /**
     * Historique d'indexation d'un dataset, du plus récent au plus ancien.
     *
     * @throws IllegalStateException nom invalide, ou Elasticsearch injoignable (message affichable)
     */
    public List<YenteEventDTO> events(String dataset, int limit) {
        if (!YenteManifestService.isValidDatasetName(dataset)) {
            throw new IllegalStateException("Nom de dataset invalide : « " + dataset + " ».");
        }
        int bounded = Math.max(1, Math.min(200, limit));
        return client.events(configService.current(), dataset, bounded).stream()
                .map(e -> new YenteEventDTO(e.type(), e.version(), e.at(), e.message()))
                .toList();
    }

    private YenteStatusDTO compute(Instant now) {
        ApplicationConfigEntity config = configService.current();

        // --- ce que le core attend ---
        Map<String, Parser> activeParsers = new HashMap<>();
        for (Parser p : parsers.findAll()) {
            if (p.isEnabled() && p.getDatasetName() != null) {
                activeParsers.put(p.getDatasetName(), p);
            }
        }
        Set<Long> published = executions.findParserIdsWithStatusIn(AlertService.PUBLISHED);
        Set<String> manifestNames;
        String manifestError = null;
        try {
            manifestNames = manifest.datasetNames(config);
        } catch (Exception e) {
            log.warn("Lecture du manifest impossible : {}", e.getMessage());
            manifestError = "Lecture de manifest.yml impossible (" + config.getYenteManifestPath() + ") : " + e.getMessage();
            manifestNames = Set.of();
        }

        // --- ce que Yente sert ---
        Health health = client.health(config);
        String yenteError = health.error();
        Map<String, CatalogEntry> catalog = new HashMap<>();
        try {
            client.catalog(config).forEach(c -> catalog.put(c.name(), c));
        } catch (IllegalStateException e) {
            yenteError = e.getMessage();
        }

        String indexError = null;
        List<IndexInfo> indices = List.of();
        Map<String, AuditEvent> lastEvents = Map.of();
        ClusterInfo cluster = new ClusterInfo(null, null, null);
        Optional<Instant> lockSince = Optional.empty();
        Optional<Instant> lastCompleted = Optional.empty();
        try {
            indices = client.indices(config);
            lastEvents = client.lastEvents(config);
            cluster = client.cluster(config);
            lockSince = client.activeLockSince(config);
            lastCompleted = client.lastCompletedAt(config);
        } catch (IllegalStateException e) {
            indexError = e.getMessage();
        }

        Map<String, IndexInfo> served = new HashMap<>();
        Map<String, IndexInfo> newest = new HashMap<>();
        for (IndexInfo index : indices) {
            if (index.aliased()) {
                served.merge(index.dataset(), index, (a, b) -> a.version().compareTo(b.version()) >= 0 ? a : b);
            }
            newest.merge(index.dataset(), index, (a, b) -> a.version().compareTo(b.version()) >= 0 ? a : b);
        }

        // --- rapprochement ---
        Set<String> names = new TreeSet<>();
        names.addAll(activeParsers.keySet());
        names.addAll(manifestNames);
        names.addAll(catalog.keySet());
        names.addAll(served.keySet());

        List<YenteDatasetStatusDTO> rows = new ArrayList<>();
        for (String name : names) {
            Parser parser = activeParsers.get(name);
            IndexInfo current = served.get(name);
            IndexInfo latest = newest.get(name);
            IndexInfo building = latest != null && !latest.aliased()
                    && (current == null || latest.version().compareTo(current.version()) > 0) ? latest : null;
            Long expected = expectedEntities(config, name);
            rows.add(DatasetStateResolver.resolve(new DatasetInputs(name,
                    parser != null ? parser.getId() : null, parser != null ? parser.getName() : null,
                    parser != null, parser != null && published.contains(parser.getId()), manifestNames.contains(name),
                    catalog.get(name), current, building, lastEvents.get(name), expected, lockSince.isPresent())));
        }
        rows.sort(Comparator.comparing((YenteDatasetStatusDTO r) -> DatasetState.valueOf(r.state()).ordinal())
                .thenComparing(YenteDatasetStatusDTO::dataset));

        int expectedCount = 0;
        int okCount = 0;
        boolean anyIndexing = false;
        for (YenteDatasetStatusDTO row : rows) {
            DatasetState state = DatasetState.valueOf(row.state());
            if (state != DatasetState.NOT_PUBLISHED && state != DatasetState.ORPHAN) {
                expectedCount++;
            }
            okCount += state == DatasetState.OK ? 1 : 0;
            anyIndexing |= state == DatasetState.INDEXING;
        }
        Integer percent = expectedCount == 0 ? null : okCount * 100 / expectedCount;

        return new YenteStatusDTO(now, health.alive(), health.ready(), health.responseMs(), yenteError,
                cluster.health(), cluster.diskAvailableBytes(), cluster.diskUsedPercent(), indexError, manifestError,
                lockSince.isPresent() || anyIndexing, lockSince.orElse(null), lastCompleted.orElse(null),
                okCount, expectedCount, percent, rows);
    }

    private Long expectedEntities(ApplicationConfigEntity config, String dataset) {
        String folder = config.getYenteDataFolder();
        if (folder == null || folder.isBlank() || !YenteManifestService.isValidDatasetName(dataset)) {
            return null;
        }
        return counter.count(Path.of(folder, dataset, dataset + ".ndjson"));
    }
}
