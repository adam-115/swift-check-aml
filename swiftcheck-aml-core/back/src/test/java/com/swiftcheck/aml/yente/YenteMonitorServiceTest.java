package com.swiftcheck.aml.yente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.dto.YenteDatasetStatusDTO;
import com.swiftcheck.aml.dto.YenteStatusDTO;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.YenteManifestService;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.ClusterInfo;
import com.swiftcheck.aml.yente.YenteModels.Health;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/** Instantané de l'écran Yente : union des sources, pourcentage, dégradation quand une source manque. */
class YenteMonitorServiceTest {

    @TempDir
    Path dir;

    private final YenteMonitorClient client = mock(YenteMonitorClient.class);
    private final ParserRepository parsers = mock(ParserRepository.class);
    private final ParserExecutionRepository executions = mock(ParserExecutionRepository.class);
    private final ApplicationConfigService configService = mock(ApplicationConfigService.class);
    private final YenteManifestService manifest = mock(YenteManifestService.class);
    private YenteMonitorService service;

    private final List<Parser> parserList = new ArrayList<>();
    private final List<CatalogEntry> catalog = new ArrayList<>();
    private final List<IndexInfo> indices = new ArrayList<>();
    private final Set<Long> published = new java.util.HashSet<>();
    private final Set<String> manifestNames = new java.util.HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        ApplicationConfigEntity config = new ApplicationConfigEntity();
        config.setYenteDataFolder(dir.toString());
        when(configService.current()).thenReturn(config);
        when(parsers.findAll()).thenReturn(parserList);
        when(executions.findParserIdsWithStatusIn(any())).thenReturn(published);
        when(manifest.datasetNames(any())).thenReturn(manifestNames);
        when(client.health(any())).thenReturn(new Health(true, true, 12, null));
        when(client.catalog(any())).thenReturn(catalog);
        when(client.indices(any())).thenReturn(indices);
        when(client.cluster(any())).thenReturn(new ClusterInfo("green", 1_000_000L, 7));
        when(client.lastEvents(any())).thenReturn(Map.of());
        when(client.activeLockSince(any())).thenReturn(Optional.empty());
        when(client.lastCompletedAt(any())).thenReturn(Optional.empty());
        service = new YenteMonitorService(client, new ExpectedEntityCounter(), parsers, executions, configService, manifest);
    }

    /** Parser actif + publié + dans le manifest + catalog + index servi ; {@code current=false} → nouvelle version en attente. */
    private void servedDataset(long id, String name, boolean current) throws Exception {
        parser(id, name, true);
        published.add(id);
        manifestNames.add(name);
        catalog.add(new CatalogEntry(name, true, current ? "v2" : "v3", "v2", current));
        indices.add(new IndexInfo("yente-entities-" + name + "-h-v2", name, "v2", 2, true));
        Path file = dir.resolve(name).resolve(name + ".ndjson");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}\n{}\n");
    }

    private void parser(long id, String name, boolean enabled) {
        Parser p = mock(Parser.class);
        when(p.getId()).thenReturn(id);
        when(p.getDatasetName()).thenReturn(name);
        when(p.getName()).thenReturn("Parser " + name);
        when(p.isEnabled()).thenReturn(enabled);
        parserList.add(p);
    }

    private static YenteDatasetStatusDTO row(YenteStatusDTO status, String dataset) {
        return status.datasets().stream().filter(d -> d.dataset().equals(dataset)).findFirst().orElseThrow();
    }

    @Test
    void pourcentageExclutNonPubliesEtOrphelins() throws Exception {
        servedDataset(1, "a_ok", true);
        servedDataset(2, "b_ok", true);
        servedDataset(3, "c_ok", true);
        servedDataset(4, "d_outdated", false);
        parser(5, "e_never", true);                                       // jamais publié
        catalog.add(new CatalogEntry("f_orphan", true, "v1", "v1", true)); // ni parser ni manifest
        indices.add(new IndexInfo("yente-entities-f_orphan-h-v1", "f_orphan", "v1", 3, true));

        YenteStatusDTO status = service.snapshot();

        assertEquals("OUTDATED", row(status, "d_outdated").state());
        assertEquals("NOT_PUBLISHED", row(status, "e_never").state());
        assertEquals("ORPHAN", row(status, "f_orphan").state());
        assertEquals(4, status.expectedCount());
        assertEquals(3, status.okCount());
        assertEquals(75, status.percent());
        assertEquals("ORPHAN", status.datasets().get(status.datasets().size() - 1).state(), "orphelins en dernier");
    }

    @Test
    void snapshotQuandElasticsearchEstInjoignable() throws Exception {
        servedDataset(1, "a_ok", true);
        when(client.indices(any())).thenThrow(new IllegalStateException("Elasticsearch injoignable (http://x) : refusé"));

        YenteStatusDTO status = service.snapshot();

        assertNotNull(status.indexError());
        assertTrue(status.indexError().startsWith("Elasticsearch injoignable"), status.indexError());
        assertTrue(status.yenteReady());
        assertEquals(1, status.datasets().size());
    }

    @Test
    void snapshotQuandYenteEstArrete() throws Exception {
        servedDataset(1, "a_ok", true);
        when(client.health(any())).thenReturn(new Health(false, false, 3, "Yente injoignable (http://y) : refusé"));
        when(client.catalog(any())).thenThrow(new IllegalStateException("Yente injoignable (http://y) : refusé"));

        YenteStatusDTO status = service.snapshot();

        assertNotNull(status.yenteError());
        assertEquals("NOT_LOADED", row(status, "a_ok").state());
    }

    @Test
    void snapshotEnCacheTroisSecondes() throws Exception {
        servedDataset(1, "a_ok", true);

        service.snapshot();
        service.snapshot();

        verify(client, times(1)).catalog(any());
    }

    @Test
    void cacheCompteDepuisLaFinDuCalcul() throws Exception {
        servedDataset(1, "a_ok", true);
        when(client.health(any())).thenAnswer(inv -> {
            Thread.sleep(3200);                                   // source lente : le calcul dépasse la durée du cache
            return new Health(true, true, 3200, null);
        });

        service.snapshot();
        service.snapshot();

        verify(client, times(1)).catalog(any());
    }

    @Test
    void manifestIntrouvableSignale() throws Exception {
        servedDataset(1, "a_ok", true);
        when(manifest.datasetNames(any())).thenThrow(new java.io.IOException("Manifest introuvable : /x/manifest.yml"));

        YenteStatusDTO status = service.snapshot();

        assertNotNull(status.manifestError());
        assertTrue(status.manifestError().contains("Manifest introuvable"), status.manifestError());
    }

    @Test
    void indexEnConstructionDetecte() throws Exception {
        servedDataset(1, "a_ok", false);
        indices.add(new IndexInfo("yente-entities-a_ok-h-v3", "a_ok", "v3", 1, false));

        YenteStatusDTO status = service.snapshot();

        assertEquals("INDEXING", row(status, "a_ok").state());
        assertEquals(50, row(status, "a_ok").progressPercent());
        assertTrue(status.indexing());
    }

    @Test
    void evenementsRefusentUnNomInvalide() {
        var error = assertThrows(IllegalStateException.class, () -> service.events("../x", 50));
        assertTrue(error.getMessage().contains("Nom de dataset invalide"), error.getMessage());
        verify(client, never()).events(any(), anyString(), anyInt());
    }
}
