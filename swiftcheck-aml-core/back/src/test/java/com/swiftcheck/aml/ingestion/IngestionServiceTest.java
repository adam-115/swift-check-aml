package com.swiftcheck.aml.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.entities.AlertType;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.ParserAlert;
import com.swiftcheck.aml.entities.RetainedEntity;
import com.swiftcheck.aml.repositories.ParserAlertRepository;
import com.swiftcheck.aml.repositories.RetainedEntityRepository;
import com.swiftcheck.aml.service.AlertService;
import com.swiftcheck.aml.entities.ChangeType;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.DatasetChange;
import com.swiftcheck.aml.entities.ExecutionTriggerEnum;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.repositories.DatasetChangeRepository;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.PluginManagementService;
import com.swiftcheck.aml.service.YenteManifestService;

/**
 * Teste le déroulé complet d'une exécution avec un faux plugin et un fichier
 * source local.
 * Les services d'ingestion sont les vrais ; seuls la base (dépôts) et le
 * chargement des plugins sont simulés.
 * La base est remplacée par des listes en mémoire, pour vérifier ce qui y
 * serait enregistré.
 */
class IngestionServiceTest {

    @TempDir
    Path tmp;

    private final List<ParserExecution> savedExecutions = new ArrayList<>();
    private final List<SourceFile> savedFiles = new ArrayList<>();
    private final List<DatasetChange> savedChanges = new ArrayList<>();
    private final List<RetainedEntity> retainedRows = new ArrayList<>();
    private final List<ParserAlert> alertRows = new ArrayList<>();

    private Parser parser;
    private Path sourceFile;
    private ApplicationConfigEntity config;
    private PluginManagementService plugins;
    private ParserExecutionRepository executions;
    private IngestionService service;

    /** Lignes que le faux plugin écrira à la prochaine exécution. */
    private List<String> pluginOutput;

    @BeforeEach
    void setUp() throws Exception {
        sourceFile = tmp.resolve("liste.xml");
        Files.writeString(sourceFile, "<liste>v1</liste>");

        parser = new Parser();
        parser.setId(7);
        parser.setName("Liste de test");
        parser.setDatasetName("test_list");
        parser.setPluginId("test-parser");
        parser.setDataSourceType(DataSourceType.LOCAL_FILE);
        parser.setSourceLocation(sourceFile.toString());

        config = new ApplicationConfigEntity();
        config.setYenteUrl("http://127.0.0.1:1"); // Yente absent : le rechargement échoue sans bloquer
        config.setYenteUpdateToken("t");
        config.setYenteDataFolder(tmp.resolve("yente").toString());
        config.setYenteContainerDataPath("/data");
        config.setYenteManifestPath(tmp.resolve("yente/manifest.yml").toString());
        config.setArchiveFolder(tmp.resolve("archive").toString());
        config.setWorkFolder(tmp.resolve("work").toString());

        pluginOutput = List.of(
                person("t-1", "Ivan PETROV"),
                person("t-2", "Maria IVANOVA"));

        // Dépôts simulés : ils enregistrent dans les listes ci-dessus
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findById(7L)).thenReturn(Optional.of(parser));

        AtomicLong ids = new AtomicLong();
        executions = mock(ParserExecutionRepository.class);
        when(executions.save(any())).thenAnswer(call -> {
            ParserExecution e = call.getArgument(0);
            if (e.getId() == 0) {
                e.setId(ids.incrementAndGet());
                savedExecutions.add(e);
            }
            return e;
        });
        when(executions.findFirstByParserIdAndStatusOrderByStartedAtDesc(anyLong(), eq(ParserExecutionStatus.SUCCESS)))
                .thenAnswer(call -> savedExecutions.stream()
                        .filter(e -> e.getStatus() == ParserExecutionStatus.SUCCESS)
                        .reduce((first, second) -> second));

        SourceFileRepository files = mock(SourceFileRepository.class);
        when(files.save(any())).thenAnswer(call -> {
            savedFiles.add(call.getArgument(0));
            return call.getArgument(0);
        });
        DatasetChangeRepository changes = mock(DatasetChangeRepository.class);
        when(changes.saveAll(any())).thenAnswer(call -> {
            call.<Iterable<DatasetChange>>getArgument(0).forEach(savedChanges::add);
            return null;
        });

        ApplicationConfigService configService = mock(ApplicationConfigService.class);
        when(configService.current()).thenReturn(config);

        // Faux plugin : écrit pluginOutput dans le fichier demandé
        Iparser fakePlugin = ctx -> {
            Files.write(ctx.outputFile(), pluginOutput);
            return new ParserResult(pluginOutput.size(), 0, 0, java.util.Map.of(), "test", java.util.List.of());
        };
        plugins = mock(PluginManagementService.class);
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(fakePlugin));

        // Rétention : les entités gardées sont dans retainedRows
        RetainedEntityRepository retained = mock(RetainedEntityRepository.class);
        AtomicLong retainedIds = new AtomicLong();
        when(retained.findByParserIdOrderByRemovedAtDesc(anyLong())).thenAnswer(call -> new ArrayList<>(retainedRows));
        when(retained.saveAll(any())).thenAnswer(call -> {
            for (RetainedEntity e : call.<Iterable<RetainedEntity>>getArgument(0)) {
                e.setId(retainedIds.incrementAndGet());
                retainedRows.add(e);
            }
            return null;
        });
        doAnswer(call -> {
            call.<Iterable<RetainedEntity>>getArgument(0).forEach(retainedRows::remove);
            return null;
        }).when(retained).deleteAll(any());
        doAnswer(call -> {
            retainedRows.clear();
            return null;
        }).when(retained).deleteByParserId(anyLong());

        // Alertes : enregistrées dans alertRows
        ParserAlertRepository alerts = mock(ParserAlertRepository.class);
        when(alerts.save(any())).thenAnswer(call -> {
            ParserAlert a = call.getArgument(0);
            if (!alertRows.contains(a)) {
                alertRows.add(a);
            }
            return a;
        });
        when(alerts.findFirstByParserIdAndTypeAndResolvedAtIsNull(anyLong(), any())).thenAnswer(call -> alertRows
                .stream().filter(a -> a.getType() == call.getArgument(1) && a.getResolvedAt() == null).findFirst());
        when(alerts.findByParserIdAndResolvedAtIsNull(anyLong())).thenAnswer(call -> alertRows.stream()
                .filter(a -> a.getResolvedAt() == null).toList());

        service = new IngestionService(parsers, executions, configService, plugins,
                new SourceFileService(files, mock(HttpDownloader.class)),
                new QualityGate(20, 1.0),
                new DatasetDiffService(changes),
                new DatasetPublisher(new YenteManifestService()),
                new RetentionService(retained),
                new AlertService(alerts, parsers, executions));
    }

    @Test
    void personsAreCounted() {
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(2, run.getPersonCount());
        assertEquals(0, run.getRetainedCount());
    }

    @Test
    void entityRemovedFromTheSourceStaysPublishedDuringRetention() throws Exception {
        parser.setRetentionMonths(12);
        pluginOutput = persons(6);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);

        // t-6 disparaît de la source (ex. ministre qui quitte le gouvernement)
        Files.writeString(sourceFile, "<liste>v2</liste>");
        pluginOutput = persons(5);
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(ParserExecutionStatus.SUCCESS, run.getStatus(), run.getMessage());
        assertEquals(5, run.getEntityCount(), "entités produites par le plugin");
        assertEquals(1, run.getRetainedCount());
        assertEquals(0, run.getRemovedCount(), "toujours publiée : ce n'est pas un retrait");
        assertEquals(persons(6), Files.readAllLines(tmp.resolve("yente/test_list/test_list.ndjson")));
        assertEquals(1, retainedRows.size());
        assertEquals("t-6", retainedRows.get(0).getEntityId());

        // t-6 revient dans la source : elle n'est plus gardée
        Files.writeString(sourceFile, "<liste>v3</liste>");
        pluginOutput = persons(6);
        ParserExecution back = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(0, back.getRetainedCount());
        assertTrue(retainedRows.isEmpty());
    }

    @Test
    void retainedEntityIsRemovedOnceRetentionHasElapsedEvenIfSourceIsUnchanged() throws Exception {
        parser.setRetentionMonths(12);
        pluginOutput = persons(6);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        Files.writeString(sourceFile, "<liste>v2</liste>");
        pluginOutput = persons(5);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);

        // 13 mois plus tard, la source n'a toujours pas changé
        retainedRows.get(0).setRemovedAt(Instant.now().atZone(ZoneOffset.UTC).minusMonths(13).toInstant());
        ParserExecution run = service.run(7, ExecutionTriggerEnum.SCHEDULED, false);

        assertEquals(ParserExecutionStatus.SUCCESS, run.getStatus(), "pas UNCHANGED : t-6 doit être retirée");
        assertEquals(1, run.getRemovedCount());
        assertEquals(persons(5), Files.readAllLines(tmp.resolve("yente/test_list/test_list.ndjson")));
        assertTrue(retainedRows.isEmpty());
    }

    @Test
    void withoutRetentionARemovedEntityIsRemovedAtOnce() throws Exception {
        pluginOutput = persons(6);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        Files.writeString(sourceFile, "<liste>v2</liste>");
        pluginOutput = persons(5);
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(1, run.getRemovedCount());
        assertEquals(0, run.getRetainedCount());
        assertTrue(retainedRows.isEmpty());
    }

    /** Personnes t-1 à t-n. */
    private static List<String> persons(int n) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            lines.add(person("t-" + i, "Personne " + i));
        }
        return lines;
    }

    @Test
    void failureRaisesAnAlertThatTheNextPublicationResolves() throws Exception {
        Files.delete(sourceFile);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(1, alertRows.size(), "une seule alerte ouverte, même après deux échecs");
        assertEquals(AlertType.FAILED, alertRows.get(0).getType());
        assertNull(alertRows.get(0).getResolvedAt());

        Files.writeString(sourceFile, "<liste>v1</liste>");
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertNotNull(alertRows.get(0).getResolvedAt());
    }

    @Test
    void firstRunArchivesSignsAndPublishes() throws Exception {
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(ParserExecutionStatus.SUCCESS, run.getStatus(), run.getMessage());
        assertEquals(2, run.getEntityCount());
        assertEquals(2, run.getAddedCount());
        assertNotNull(run.getFinishedAt());

        // Fichier source signé et archivé sous son empreinte
        assertEquals(1, savedFiles.size());
        SourceFile archived = savedFiles.get(0);
        assertEquals(64, archived.getSha256().length());
        assertEquals(archived.getSha256(), run.getInputSha256());
        assertEquals(archived.getSha256(), SourceFileService.sha256(Path.of(archived.getArchivePath())));

        // Publié au bon endroit et déclaré dans le manifest
        Path published = tmp.resolve("yente/test_list/test_list.ndjson");
        assertEquals(pluginOutput, Files.readAllLines(published));
        String manifest = Files.readString(tmp.resolve("yente/manifest.yml"));
        assertTrue(manifest.contains("/data/test_list/test_list.ndjson"), manifest);
        assertTrue(manifest.contains("- \"test_list\"") || manifest.contains("- test_list"), manifest);

        // Première publication : pas de détail ligne par ligne, et dossier de travail
        // supprimé
        assertTrue(savedChanges.isEmpty());
        assertFalse(Files.exists(tmp.resolve("work/exec-" + run.getId())));
    }

    @Test
    void sameSourceIsUnchangedUnlessForced() {
        service.run(7, ExecutionTriggerEnum.MANUAL, false);

        ParserExecution second = service.run(7, ExecutionTriggerEnum.SCHEDULED, false);
        assertEquals(ParserExecutionStatus.UNCHANGED, second.getStatus());
        assertEquals(2, second.getEntityCount());

        ParserExecution forced = service.run(7, ExecutionTriggerEnum.MANUAL, true);
        assertEquals(ParserExecutionStatus.SUCCESS, forced.getStatus(), forced.getMessage());
        assertEquals(0, forced.getAddedCount() + forced.getRemovedCount() + forced.getChangedCount());
    }

    @Test
    void changesBetweenVersionsAreRecorded() throws Exception {
        service.run(7, ExecutionTriggerEnum.MANUAL, false);

        Files.writeString(sourceFile, "<liste>v2</liste>");
        pluginOutput = List.of(
                person("t-1", "Ivan PETROV", "Vania"), // modifiée : alias ajouté
                person("t-3", "Nouvelle PERSONNE")); // ajoutée ; t-2 retirée
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(ParserExecutionStatus.SUCCESS, run.getStatus(), run.getMessage());
        assertEquals(1, run.getAddedCount());
        assertEquals(1, run.getRemovedCount());
        assertEquals(1, run.getChangedCount());
        assertEquals(3, savedChanges.size());
        DatasetChange modified = savedChanges.stream()
                .filter(c -> c.getChangeType() == ChangeType.MODIFIED).findFirst().orElseThrow();
        assertEquals("t-1", modified.getEntityId());
        assertEquals("alias", modified.getChangedProperties());
        assertEquals("Ivan PETROV", modified.getCaption());
    }

    @Test
    void emptyOutputIsBlockedAndPreviousVersionStaysPublished() throws Exception {
        service.run(7, ExecutionTriggerEnum.MANUAL, false);
        Path published = tmp.resolve("yente/test_list/test_list.ndjson");
        List<String> before = Files.readAllLines(published);

        Files.writeString(sourceFile, "<liste>format changé</liste>");
        pluginOutput = List.of();
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(ParserExecutionStatus.BLOCKED, run.getStatus());
        assertTrue(run.getMessage().contains("Aucune entité"), run.getMessage());
        assertEquals(before, Files.readAllLines(published));
    }

    @Test
    void invalidJsonIsBlocked() throws Exception {
        pluginOutput = List.of(person("t-1", "Ivan PETROV"), "{pas du json", person("t-1", "Doublon"));
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);

        assertEquals(ParserExecutionStatus.BLOCKED, run.getStatus());
        assertTrue(run.getMessage().contains("ligne 2 : JSON invalide"), run.getMessage());
        assertTrue(run.getMessage().contains("ligne 3 : identifiant en double : t-1"), run.getMessage());
        assertFalse(Files.exists(tmp.resolve("yente/test_list/test_list.ndjson")));
    }

    @Test
    void pluginErrorOrMissingPluginFails() throws Exception {
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(ctx -> {
            throw new IllegalStateException("balise inconnue");
        }));
        ParserExecution crashed = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.FAILED, crashed.getStatus());
        assertTrue(crashed.getMessage().contains("balise inconnue"));

        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.empty());
        ParserExecution missing = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.FAILED, missing.getStatus());
        assertTrue(missing.getMessage().contains("non chargé"), missing.getMessage());
    }

    @Test
    void missingLocalFileFails() throws Exception {
        Files.delete(sourceFile);
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.FAILED, run.getStatus());
        assertTrue(run.getMessage().contains("introuvable"), run.getMessage());
    }

    @Test
    void pluginManagedSourceMustKeepRawData() throws Exception {
        parser.setDataSourceType(DataSourceType.PLUGIN_MANAGED);
        parser.setSourceLocation(null);

        // 1. le plugin ne dépose rien dans rawDir → bloqué
        ParserExecution withoutRaw = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.BLOCKED, withoutRaw.getStatus());

        // 2. le plugin dépose sa réponse brute → signée, archivée, publiée
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(ctx -> {
            Files.writeString(ctx.rawDir().resolve("page1.json"), "{\"results\": []}");
            Files.write(ctx.outputFile(), pluginOutput);
            return new ParserResult(2, 0, 0, java.util.Map.of(), null, java.util.List.of());
        }));
        ParserExecution withRaw = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.SUCCESS, withRaw.getStatus(), withRaw.getMessage());
        assertEquals("page1.json", savedFiles.get(0).getOrigin());
        assertTrue(savedFiles.get(0).getArchivePath().endsWith(".json"));
    }

    @Test
    void stopInterruptsTheRunningPluginAndNothingIsPublished() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(ctx -> {
            started.countDown();
            Thread.sleep(60_000);          // ex. Wikidata qui ne répond pas
            Files.write(ctx.outputFile(), pluginOutput);
            return new ParserResult(2, 0, 0, java.util.Map.of(), null, java.util.List.of());
        }));
        java.util.concurrent.atomic.AtomicReference<ParserExecution> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread runner = new Thread(() -> result.set(service.run(7, ExecutionTriggerEnum.MANUAL, false)));
        runner.start();
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));

        String message = service.stop(7);
        runner.join(10_000);

        assertTrue(message.contains("arrêtée"), message);
        assertEquals(ParserExecutionStatus.FAILED, result.get().getStatus());
        assertEquals(IngestionService.STOPPED_MESSAGE, result.get().getMessage());
        assertFalse(Files.exists(tmp.resolve("yente/test_list/test_list.ndjson")), "rien n'est publié");
        assertTrue(alertRows.isEmpty(), "pas d'alerte pour un arrêt voulu");
        // Le parser est libéré : il peut être relancé
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(ctx -> {
            Files.write(ctx.outputFile(), pluginOutput);
            return new ParserResult(2, 0, 0, java.util.Map.of(), null, java.util.List.of());
        }));
        assertEquals(ParserExecutionStatus.SUCCESS, service.run(7, ExecutionTriggerEnum.MANUAL, false).getStatus());
    }

    @Test
    void pluginBuiltForAnOlderApiFailsInsteadOfStayingRunning() {
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(ctx -> {
            throw new NoSuchMethodError("'void com.swiftcheck.aml.plugin.ParserResult.<init>(int, int, int, java.util.Map, java.lang.String)'");
        }));
        ParserExecution run = service.run(7, ExecutionTriggerEnum.MANUAL, false);
        assertEquals(ParserExecutionStatus.FAILED, run.getStatus());
        assertTrue(run.getMessage().contains("incompatible"), run.getMessage());
        assertNotNull(run.getFinishedAt());
    }

    @Test
    void stopReleasesAnOrphanRunningExecution() {
        assertThrows(IllegalStateException.class, () -> service.stop(7), "rien ne tourne");

        // Ligne RUNNING sans exécution active dans ce serveur
        ParserExecution orphan = new ParserExecution();
        orphan.setId(42);
        orphan.setParser(parser);
        orphan.setStatus(ParserExecutionStatus.RUNNING);
        when(executions.findByParserIdAndStatus(7L, ParserExecutionStatus.RUNNING)).thenReturn(List.of(orphan));

        service.stop(7);
        assertEquals(ParserExecutionStatus.FAILED, orphan.getStatus());
        assertNotNull(orphan.getFinishedAt());
    }

    @Test
    void unknownParserIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.run(99, ExecutionTriggerEnum.MANUAL, false));
    }

    private static String person(String id, String name, String... aliases) {
        StringBuilder alias = new StringBuilder();
        for (String a : aliases) {
            alias.append(alias.length() == 0 ? "" : ",").append('"').append(a).append('"');
        }
        return "{\"id\":\"" + id + "\",\"schema\":\"Person\",\"properties\":{\"name\":[\"" + name + "\"]"
                + (aliases.length > 0 ? ",\"alias\":[" + alias + "]" : "") + "}}";
    }
}
