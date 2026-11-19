package com.swiftcheck.aml.yente;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.dto.YenteDatasetStatusDTO;
import com.swiftcheck.aml.yente.DatasetStateResolver.DatasetInputs;
import com.swiftcheck.aml.yente.YenteModels.AuditEvent;
import com.swiftcheck.aml.yente.YenteModels.CatalogEntry;
import com.swiftcheck.aml.yente.YenteModels.IndexInfo;

/** Un état par dataset, première règle qui s'applique dans l'ordre du spec (§6.2). */
class DatasetStateResolverTest {

    private static final String DS = "fr_pep_senate";
    private static final CatalogEntry CURRENT = new CatalogEntry(DS, true, "v2", "v2", true);
    private static final CatalogEntry STALE = new CatalogEntry(DS, true, "v3", "v2", false);
    private static final IndexInfo SERVED = new IndexInfo("yente-entities-" + DS + "-h-v2", DS, "v2", 2675, true);
    private static final IndexInfo BUILDING = new IndexInfo("yente-entities-" + DS + "-h-v3", DS, "v3", 6100, false);

    private static AuditEvent event(String type) {
        return new AuditEvent(DS, type, "v2", Instant.parse("2026-11-18T13:54:00Z"), "msg " + type);
    }

    /** Parser actif, publié, dans le manifest, servi à jour : point de départ de chaque test. */
    private static DatasetInputs ok(Long expected) {
        return new DatasetInputs(DS, 7L, "PEP France – Sénateurs", true, true, true, CURRENT, SERVED, null,
                event("index_alias_rollover_complete"), expected);
    }

    private static String state(DatasetInputs in) {
        return DatasetStateResolver.resolve(in).state();
    }

    @Test
    void notPublished() {
        assertEquals("NOT_PUBLISHED", state(new DatasetInputs(DS, 7L, "p", true, false, false, null, null, null, null, null)));
    }

    @Test
    void notInManifest() {
        assertEquals("NOT_IN_MANIFEST", state(new DatasetInputs(DS, 7L, "p", true, true, false, null, null, null, null, 10L)));
    }

    @Test
    void notLoaded() {
        assertEquals("NOT_LOADED", state(new DatasetInputs(DS, 7L, "p", true, true, true, null, null, null, null, 10L)));
    }

    @Test
    void failed() {
        assertEquals("FAILED", state(new DatasetInputs(DS, 7L, "p", true, true, true, STALE, SERVED, null,
                event("reindex_failed"), 2675L)));
    }

    @Test
    void notServed() {
        assertEquals("NOT_SERVED", state(new DatasetInputs(DS, 7L, "p", true, true, true, CURRENT, null, null,
                event("index_alias_rollover_complete"), 2675L)));
    }

    @Test
    void indexing() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(new DatasetInputs(DS, 7L, "p", true, true, true, STALE,
                SERVED, BUILDING, event("reindex_started"), 8513L));
        assertEquals("INDEXING", dto.state());
        assertEquals(71, dto.progressPercent());
    }

    @Test
    void indexingSansIndexEnConstructionMaisDebutAuJournal() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(new DatasetInputs(DS, 7L, "p", true, true, true, STALE,
                SERVED, null, event("reindex_started"), 8513L));
        assertEquals("INDEXING", dto.state());
        assertNull(dto.progressPercent());
    }

    @Test
    void debutSansFinNiVerrouNEstPlusUneIndexation() {
        // Yente arrêté en pleine indexation : le journal garde un reindex_started sans fin, mais plus rien ne tourne
        DatasetInputs stale = new DatasetInputs(DS, 7L, "p", true, true, true, CURRENT, SERVED, null,
                event("reindex_started"), 2675L, false);
        assertEquals("OK", state(stale));
    }

    @Test
    void indexSansNombreAttenduDonneLesDocumentsEcrits() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(new DatasetInputs(DS, 7L, "p", true, true, true, STALE,
                SERVED, BUILDING, event("reindex_started"), null));
        assertNull(dto.progressPercent());
        assertEquals(6100L, dto.buildingEntities());
    }

    @Test
    void indexingCalculeLePourcentagePlafonne() {
        IndexInfo full = new IndexInfo(BUILDING.index(), DS, "v3", 8513, false);
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(new DatasetInputs(DS, 7L, "p", true, true, true, STALE,
                SERVED, full, event("reindex_started"), 8513L));
        assertEquals(99, dto.progressPercent(), "100 % seulement après la mise en service");
    }

    @Test
    void outdated() {
        assertEquals("OUTDATED", state(new DatasetInputs(DS, 7L, "p", true, true, true, STALE, SERVED, null,
                event("index_alias_rollover_complete"), 2675L)));
    }

    @Test
    void countMismatch() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(ok(2700L));
        assertEquals("COUNT_MISMATCH", dto.state());
        assertEquals(2675L, dto.indexedEntities());
        assertEquals(2700L, dto.expectedEntities());
    }

    @Test
    void ok() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(ok(2675L));
        assertEquals("OK", dto.state());
        assertTrue(dto.countVerified());
        assertEquals(100, dto.progressPercent());
        assertEquals("v2", dto.fileVersion());
        assertEquals("v2", dto.servedVersion());
        assertEquals("index_alias_rollover_complete", dto.lastEventType());
        assertEquals(7L, dto.parserId());
    }

    @Test
    void okSansNombreAttenduEstNonVerifie() {
        YenteDatasetStatusDTO dto = DatasetStateResolver.resolve(ok(null));
        assertEquals("OK", dto.state());
        assertFalse(dto.countVerified());
    }

    @Test
    void orphan() {
        assertEquals("ORPHAN", state(new DatasetInputs(DS, null, null, false, false, false, CURRENT, SERVED, null,
                null, null)));
    }

    @Test
    void datasetDuManifestSansParserEstSuiviNormalement() {
        assertEquals("OK", state(new DatasetInputs("eu_sanctions", null, null, false, false, true,
                new CatalogEntry("eu_sanctions", true, "v1", "v1", true),
                new IndexInfo("yente-entities-eu_sanctions-h-v1", "eu_sanctions", "v1", 5, true), null, null, null)));
    }

    @Test
    void echecPrioritaireSurIndexation() {
        assertEquals("FAILED", state(new DatasetInputs(DS, 7L, "p", true, true, true, STALE, SERVED, BUILDING,
                event("reindex_failed"), 8513L)));
    }
}
