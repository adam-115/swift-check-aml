package com.swiftcheck.aml.plugins.ptpep.government;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Pages réelles du portail du Gouvernement (novembre 2026) : ministre de l'Administration interne (titulaire actuel et
 * prédécesseure), secrétaire d'État des Finances, secrétaire d'État du cabinet du Premier ministre (mandat terminé),
 * biographie du Premier ministre, et un extrait du sitemap public (gouvernements gc24 et gc25).
 */
class PtPepGovernmentParserTest {

    private static final String SITEMAP = "https://portugal.gov.pt/sitemap.xml";

    @Test
    void discoversOnlyThePagesOfTheLatestGovernmentMembers(@TempDir Path rawDir) throws Exception {
        List<String> requested = new ArrayList<>();
        String government = PtPepGovernmentParser.download(fetcher(requested), SITEMAP, null, rawDir);

        assertEquals("gc25", government, "le plus grand numéro du sitemap");
        assertFalse(requested.stream().anyMatch(u -> u.contains("/gc24/")));
        assertFalse(requested.stream().anyMatch(u -> u.endsWith("/noticias") || u.endsWith("/acerca")));
        assertTrue(requested.contains("https://portugal.gov.pt/gc25/primeiro-ministro"));
        assertTrue(requested.contains("https://portugal.gov.pt/gc25/area-de-governo/administracao-interna/ministro"));
        assertTrue(requested.contains("https://portugal.gov.pt/gc25/primeiro-ministro/secretarios-de-estado/protecao-civil"));
        assertEquals(13, requested.size() - 1, "biographie du Premier ministre, 3 secrétaires d Etat du cabinet, 2 ministres, 7 secrétaires d Etat");
    }

    @Test
    void readsCurrentAndRecentlyFormerHolders(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2026, 3, 1));

        FtmEntity minister = person(dataset, "Luís Neves");
        assertTrue(minister.getValues("position").contains("Ministro da Administração Interna"));
        assertEquals(java.util.Set.of("male"), minister.getValues("gender"));
        assertEquals(java.util.Set.of("role.pep"), minister.getValues("topics"));

        FtmEntity predecessor = person(dataset, "Maria Lúcia Amaral");
        assertTrue(predecessor.getValues("position").contains("Ministra (Administração Interna)"));
        assertEquals(java.util.Set.of("female"), predecessor.getValues("gender"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2026-02-11") && e.getValues("status").contains("ended")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void dropsFormerHoldersAfterTheRetentionWindow(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2027, 6, 1));

        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Maria Lúcia Amaral")));
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Rui Rocha")));
        assertTrue(dataset.entities().stream().anyMatch(e -> e.getValues("name").contains("Luís Neves")));
    }

    @Test
    void readsThePrimeMinisterFromTheOfficialBiography(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, LocalDate.of(2026, 3, 1));

        FtmEntity pm = person(dataset, "Luis Montenegro");
        assertEquals(java.util.Set.of("1973-02-16"), pm.getValues("birthDate"));
        assertEquals(java.util.Set.of("Porto"), pm.getValues("birthPlace"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.head")));
    }

    @Test
    void titleIsSplitIntoNameRoleAreaAndGovernment() throws Exception {
        String[] title = PtPepGovernmentParser.title(resource("minister-interior.html"));
        assertArrayEquals(new String[] { "Luís Neves", "Ministro da Administração Interna", "Administração Interna",
                "XXV Governo Constitucional" }, title);
        assertThrows(IllegalStateException.class, () -> PtPepGovernmentParser.download(
                url -> "<urlset></urlset>", SITEMAP, null, Files.createTempDirectory("pt-gov")));
    }

    private static FtmDatasetBuilder run(Path rawDir, LocalDate today) throws Exception {
        PtPepGovernmentParser.download(fetcher(new ArrayList<>()), SITEMAP, "gc25", rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PtPepGovernmentParser.ID_PREFIX, PtPepGovernmentParser.TOPIC);
        new PtPepGovernmentParser().convert(rawDir, "gc25", today, 12, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    /** Sitemap et pages réelles ; les autres postes reçoivent la page du secrétaire d'État des Finances. */
    private static PtPepGovernmentParser.Fetcher fetcher(List<String> requested) {
        return url -> {
            requested.add(url);
            if (url.equals(SITEMAP)) {
                return resource("sitemap.xml");
            }
            if (url.endsWith("/gc25/primeiro-ministro")) {
                return resource("prime-minister.html");
            }
            if (url.endsWith("/administracao-interna/ministro")) {
                return resource("minister-interior.html");
            }
            if (url.endsWith("/primeiro-ministro/secretarios-de-estado/protecao-civil")) {
                return resource("state-secretary-pm-office.html");
            }
            return resource("state-secretary-finance.html");
        };
    }

    private static String resource(String name) {
        try (InputStream in = PtPepGovernmentParserTest.class.getResourceAsStream("/government/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
