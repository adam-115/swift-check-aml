package com.swiftcheck.aml.parsertest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestFailure;
import com.swiftcheck.aml.entities.ParserTestStatus;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.entities.TestFailureReason;
import com.swiftcheck.aml.ingestion.DatasetFile;
import com.swiftcheck.aml.ingestion.DatasetPublisher;
import com.swiftcheck.aml.ingestion.ParserParameters;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.ParserTestFailureRepository;
import com.swiftcheck.aml.repositories.ParserTestRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.PluginManagementService;

/**
 * Test d'un parser : prouve que chaque élément du fichier source (personne, organisation, navire…)
 * est bien présent dans Yente et retrouvé par son nom.
 *
 * <pre>
 *  1. Fichier source       le fichier archivé (signé SHA-256) de la dernière publication réussie
 *  2. Lecture de la source le plugin relit ce fichier : liste des éléments attendus (ID, schéma, nom)
 *  3. Conversion           l'élément est-il dans le NDJSON publié ?          non → NOT_CONVERTED
 *  4. Recherche par nom    POST /match : l'ID attendu est-il dans les résultats ? oui → trouvé
 *  5. Recherche par ID     sinon, GET /entities/{id} : 404 → NOT_INDEXED, 200 → NOT_MATCHED
 * </pre>
 * Le test est PASSED si rien ne manque (conversion, indexation, erreurs) et si au moins
 * {@code swiftcheck.test.min-match-percent} % des éléments sont retrouvés par leur nom.
 *
 * <p>Service sans état : tout ce qui concerne un test vit dans les variables locales de {@link #run}.
 */
@Service
public class ParserTestService {

    private static final Logger log = LoggerFactory.getLogger(ParserTestService.class);

    /** Au-delà, les échecs sont comptés mais pas tous enregistrés (la liste resterait illisible). */
    static final int MAX_SAVED_FAILURES = 1000;

    private final ParserRepository parsersRepository;
    private final ParserExecutionRepository executionsRepository;
    private final SourceFileRepository sourceFilesRepository;
    private final ParserTestRepository testsRepository;
    private final ParserTestFailureRepository failuresRepository;
    private final PluginManagementService pluginsManagementService;
    private final ApplicationConfigService configService;
    private final DatasetPublisher publisher;
    private final YenteLookup yente;

    private final double minMatchPercent;
    private final int batchSize;
    private final int threads;

    public ParserTestService(ParserRepository parsersRepository, ParserExecutionRepository executionsRepository,
            SourceFileRepository sourceFilesRepository, ParserTestRepository testsRepository,
            ParserTestFailureRepository failuresRepository, PluginManagementService pluginsManagementService,
            ApplicationConfigService configService, DatasetPublisher publisher, YenteLookup yente,
            @Value("${swiftcheck.test.min-match-percent:99}") double minMatchPercent,
            @Value("${swiftcheck.test.batch-size:25}") int batchSize,
            @Value("${swiftcheck.test.threads:4}") int threads) {
        this.parsersRepository = parsersRepository;
        this.executionsRepository = executionsRepository;
        this.sourceFilesRepository = sourceFilesRepository;
        this.testsRepository = testsRepository;
        this.failuresRepository = failuresRepository;
        this.pluginsManagementService = pluginsManagementService;
        this.configService = configService;
        this.publisher = publisher;
        this.yente = yente;
        this.minMatchPercent = minMatchPercent;
        this.batchSize = batchSize;
        this.threads = threads;
    }

    /** Résultat de la vérification d'un élément : {@code reason == null} = trouvé. */
    private record Outcome(SourceRecord record, TestFailureReason reason, String detail) {
    }

    /**
     * Lance le test et attend la fin. Les problèmes de prérequis (aucune publication, plugin qui ne sait pas
     * relire sa source…) sont refusés immédiatement ; une erreur pendant le test le termine en FAILED.
     *
     * @throws IllegalArgumentException si le parser n'existe pas
     * @throws IllegalStateException    si le test ne peut pas être lancé (message affiché tel quel)
     */
    public ParserTest run(long parserId) {
        Parser parser = parsersRepository.findById(parserId)
                .orElseThrow(() -> new IllegalArgumentException("Parser introuvable : " + parserId));
        if (testsRepository.existsByParserIdAndStatus(parserId, ParserTestStatus.RUNNING)) {
            throw new IllegalStateException("Un test de ce parser est déjà en cours.");
        }
        // On teste ce qui a été publié : le fichier source de la dernière exécution réussie.
        ParserExecution publication = executionsRepository
                .findFirstByParserIdAndStatusOrderByStartedAtDesc(parserId, ParserExecutionStatus.SUCCESS)
                .orElseThrow(() -> new IllegalStateException(
                        "Aucune publication réussie pour ce parser : lancez-le d'abord, puis testez."));
        Iparser plugin = pluginsManagementService.findParser(parser)
                .orElseThrow(() -> new IllegalStateException("Plugin « " + parser.getPluginId() + " » non chargé."));

        ParserTest test = new ParserTest();
        test.setParser(parser);
        test.setExecutionId(publication.getId());
        test.setStatus(ParserTestStatus.RUNNING);
        test.setStartedAt(Instant.now());
        test = testsRepository.save(test);

        ApplicationConfigEntity config = configService.current();
        Path workDir = Path.of(config.getWorkFolder(), "test-" + test.getId());
        try {
            Files.createDirectories(workDir);
            execute(parser, plugin, publication, test, workDir, config);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Test {} du parser {} en échec", test.getId(), parser.getDatasetName(), e);
            test.setStatus(ParserTestStatus.FAILED);
            test.setMessage(e.getClass().getSimpleName() + " : " + e.getMessage());
        } finally {
            test.setFinishedAt(Instant.now());
            test = testsRepository.save(test);
            deleteQuietly(workDir);
        }
        log.info("Test du parser {} : {} – {}", parser.getDatasetName(), test.getStatus(), test.getMessage());
        return test;
    }

    private void execute(Parser parser, Iparser plugin, ParserExecution publication, ParserTest test, Path workDir,
            ApplicationConfigEntity config) throws Exception {
        // 1. Le fichier source archivé de la publication, donné au plugin comme lors de la conversion.
        ParserContext context = sourceContext(parser, publication, workDir);

        // 2. Le plugin relit la source. Un même ID vu deux fois (fiche en double) ne compte qu'une fois.
        List<SourceRecord> read = plugin.readSourceRecords(context);
        if (read == null) {
            // Cas fréquent : le JAR chargé a été construit avant l'ajout de readSourceRecords.
            throw new IllegalStateException("Le plugin « " + parser.getPluginId() + " » chargé ne sait pas relire"
                    + " sa source (readSourceRecords absente de ce JAR"
                    + (parser.getPluginUrl() != null ? " : " + parser.getPluginUrl() : "")
                    + "). S'il s'agit d'une ancienne version, reconstruisez le plugin puis rechargez-le"
                    + " (bouton « Vérifier » du parser).");
        }
        Map<String, SourceRecord> records = new LinkedHashMap<>();
        for (SourceRecord record : read) {
            if (record != null && record.entityId() != null) {
                records.putIfAbsent(record.entityId(), record);
            }
        }
        test.setSourceCount(records.size());
        if (records.isEmpty()) {
            throw new IllegalStateException("Aucun élément lu dans le fichier source.");
        }

        // 3. Absent du fichier publié = perdu par la conversion : inutile de le chercher dans Yente.
        Set<String> published = DatasetFile.readIfExists(publisher.publishedFile(parser, config)).entities().keySet();
        List<Outcome> outcomes = new ArrayList<>();
        List<SourceRecord> withName = new ArrayList<>();
        List<SourceRecord> withoutName = new ArrayList<>();
        for (SourceRecord record : records.values()) {
            if (!published.contains(record.entityId())) {
                outcomes.add(new Outcome(record, TestFailureReason.NOT_CONVERTED, "absent du fichier publié"));
            } else if (record.name() == null || record.name().isBlank()) {
                withoutName.add(record);
            } else {
                withName.add(record);
            }
        }

        // 4 et 5. Interroger Yente, en parallèle pour ne pas attendre des minutes sur une grande liste.
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            outcomes.addAll(checkInYente(parser.getDatasetName(), config.getYenteUrl(), withName, withoutName, pool));
        } finally {
            pool.shutdownNow();
        }
        record(test, outcomes);
    }

    /**
     * Le contexte donné au plugin pour relire la source : le fichier archivé (HTTP / local), ou pour
     * PLUGIN_MANAGED une copie des réponses brutes archivées, sous leur nom d'origine, dans {@code rawDir}.
     */
    private ParserContext sourceContext(Parser parser, ParserExecution publication, Path workDir) throws IOException {
        List<SourceFile> sources = sourceFilesRepository.findByExecutionIdOrderByIdAsc(publication.getId());
        if (sources.isEmpty()) {
            throw new IllegalStateException("La publication " + publication.getId() + " n'a pas de fichier source archivé.");
        }
        Path rawDir = Files.createDirectories(workDir.resolve("raw"));
        Path inputFile = null;
        if (parser.getDataSourceType() == DataSourceType.PLUGIN_MANAGED) {
            for (SourceFile source : sources) {
                Path name = Path.of(source.getOrigin()).getFileName();
                Files.copy(archived(source), rawDir.resolve(name.toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            inputFile = archived(sources.get(0));
        }
        return new ParserContext(parser.getDatasetName(), inputFile, rawDir, null,
                ParserParameters.parse(parser.getParameters()));
    }

    private static Path archived(SourceFile source) {
        Path path = Path.of(source.getArchivePath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Fichier source archivé introuvable : " + path);
        }
        return path;
    }

    /**
     * Recherche par nom par lots ; les éléments non retrouvés (et ceux sans nom) sont ensuite cherchés par ID
     * pour savoir s'ils sont absents de l'index ou seulement introuvables par leur nom.
     */
    private List<Outcome> checkInYente(String dataset, String yenteUrl, List<SourceRecord> withName,
            List<SourceRecord> withoutName, ExecutorService pool) throws InterruptedException, ExecutionException {
        List<Callable<List<Outcome>>> nameSearches = new ArrayList<>();
        for (int from = 0; from < withName.size(); from += batchSize) {
            List<SourceRecord> batch = withName.subList(from, Math.min(from + batchSize, withName.size()));
            nameSearches.add(() -> searchByName(dataset, yenteUrl, batch));
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<Outcome> toCheckById = new ArrayList<>();
        for (Future<List<Outcome>> future : pool.invokeAll(nameSearches)) {
            for (Outcome outcome : future.get()) {
                // NOT_MATCHED provisoire : à confirmer par l'ID (l'élément est peut-être absent de l'index).
                (outcome.reason() == TestFailureReason.NOT_MATCHED ? toCheckById : outcomes).add(outcome);
            }
        }
        for (SourceRecord record : withoutName) {
            toCheckById.add(new Outcome(record, null, null));
        }

        List<Callable<Outcome>> idChecks = new ArrayList<>();
        for (Outcome pending : toCheckById) {
            idChecks.add(() -> checkById(yenteUrl, pending));
        }
        for (Future<Outcome> future : pool.invokeAll(idChecks)) {
            outcomes.add(future.get());
        }
        return outcomes;
    }

    private List<Outcome> searchByName(String dataset, String yenteUrl, List<SourceRecord> batch) {
        List<Outcome> outcomes = new ArrayList<>();
        try {
            Map<String, YenteLookup.MatchOutcome> results = yente.match(yenteUrl, dataset, batch);
            for (SourceRecord record : batch) {
                YenteLookup.MatchOutcome result = results.get(record.entityId());
                if (result != null && result.found()) {
                    outcomes.add(new Outcome(record, null, null));
                } else {
                    String best = result == null || result.best() == null ? "aucun résultat" : result.best().toString();
                    outcomes.add(new Outcome(record, TestFailureReason.NOT_MATCHED, best));
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            for (SourceRecord record : batch) {
                outcomes.add(new Outcome(record, TestFailureReason.YENTE_ERROR, "recherche par nom : " + e.getMessage()));
            }
        }
        return outcomes;
    }

    /** 200 : indexé (introuvable par le nom s'il en a un) ; 404 : non indexé ; autre : erreur Yente. */
    private Outcome checkById(String yenteUrl, Outcome pending) {
        SourceRecord record = pending.record();
        try {
            int status = yente.entityStatus(yenteUrl, record.entityId());
            if (status == 200) {
                return new Outcome(record, pending.reason(), pending.detail()); // null (trouvé) ou NOT_MATCHED
            }
            if (status == 404) {
                return new Outcome(record, TestFailureReason.NOT_INDEXED, "GET /entities/" + record.entityId() + " → 404");
            }
            return new Outcome(record, TestFailureReason.YENTE_ERROR, "GET /entities → HTTP " + status);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Outcome(record, TestFailureReason.YENTE_ERROR, "recherche par ID : " + e.getMessage());
        }
    }

    /** Compteurs, statut, message et liste des éléments non retrouvés (limitée à MAX_SAVED_FAILURES). */
    private void record(ParserTest test, List<Outcome> outcomes) {
        List<ParserTestFailure> failures = new ArrayList<>();
        int found = 0, notConverted = 0, notIndexed = 0, notMatched = 0, errors = 0;
        for (Outcome outcome : outcomes) {
            if (outcome.reason() == null) {
                found++;
                continue;
            }
            switch (outcome.reason()) {
                case NOT_CONVERTED -> notConverted++;
                case NOT_INDEXED -> notIndexed++;
                case NOT_MATCHED -> notMatched++;
                case YENTE_ERROR -> errors++;
            }
            if (failures.size() < MAX_SAVED_FAILURES) {
                failures.add(failure(test.getId(), outcome));
            }
        }
        failuresRepository.saveAll(failures);

        test.setFoundCount(found);
        test.setNotConvertedCount(notConverted);
        test.setNotIndexedCount(notIndexed);
        test.setNotMatchedCount(notMatched);
        test.setErrorCount(errors);

        int total = test.getSourceCount();
        double foundPercent = total == 0 ? 0 : found * 100.0 / total;
        boolean passed = notConverted == 0 && notIndexed == 0 && errors == 0 && foundPercent >= minMatchPercent;
        test.setStatus(passed ? ParserTestStatus.PASSED : ParserTestStatus.FAILED);

        StringBuilder message = new StringBuilder(String.format(java.util.Locale.FRANCE,
                "%d éléments dans la source : %d retrouvés (%.1f %%), %d non convertis, %d non indexés, "
                        + "%d non retrouvés par le nom, %d erreurs Yente.",
                total, found, foundPercent, notConverted, notIndexed, notMatched, errors));
        if (notIndexed > 0 && notIndexed == total - notConverted) {
            message.append(" Aucun élément n'est indexé : Yente est peut-être encore en train d'indexer la liste,"
                    + " réessayez dans quelques minutes.");
        }
        if (failures.size() < total - found) {
            message.append(" Seuls les ").append(MAX_SAVED_FAILURES).append(" premiers échecs sont détaillés.");
        }
        test.setMessage(message.toString());
    }

    private static ParserTestFailure failure(long testId, Outcome outcome) {
        ParserTestFailure failure = new ParserTestFailure();
        failure.setTestId(testId);
        failure.setEntityId(outcome.record().entityId());
        failure.setEntitySchema(outcome.record().schema());
        failure.setName(truncate(outcome.record().name(), 1024));
        failure.setSourceRef(truncate(outcome.record().sourceRef(), 512));
        failure.setReason(outcome.reason());
        failure.setDetail(outcome.detail());
        return failure;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** Au démarrage : un test resté RUNNING après un arrêt brutal ne se terminera jamais. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedTests() {
        for (ParserTest test : testsRepository.findByStatus(ParserTestStatus.RUNNING)) {
            test.setStatus(ParserTestStatus.FAILED);
            test.setFinishedAt(Instant.now());
            test.setMessage("Test interrompu par un arrêt de l'application.");
            testsRepository.save(test);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Dossier de travail du test non supprimé : {} ({})", dir, e.getMessage());
        }
    }
}
