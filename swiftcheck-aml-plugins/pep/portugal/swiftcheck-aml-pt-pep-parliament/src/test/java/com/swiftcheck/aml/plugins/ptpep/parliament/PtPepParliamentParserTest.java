package com.swiftcheck.aml.plugins.ptpep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extraits réels des « Dados Abertos » du Parlement portugais : les deux pages (liste des législatures, dossier de la
 * XVIIe) et dix entrées du plénaire couvrant chaque cas (titulaire, président, vice-président, suppléant,
 * démissionnaire, ancien titulaire, mandat suspendu, titulaire temporaire, suppléant ayant siégé).
 */
class PtPepParliamentParserTest {

    private static final String ROOT = "https://www.parlamento.pt/Cidadania/Paginas/DAComposicaoOrgaos.aspx";

    @Test
    void followsTheRouteFromTheLegislatureListToTheJsonFile(@TempDir Path rawDir) throws Exception {
        String[] requested = new String[3];
        PtPepParliamentParser.Fetcher fetcher = url -> {
            int i = requested[0] == null ? 0 : requested[1] == null ? 1 : 2;
            requested[i] = url;
            return switch (i) {
                case 0 -> resource("root-page.html");
                case 1 -> resource("legislature-page.html");
                default -> resource("composicao.json");
            };
        };

        String legislature = PtPepParliamentParser.download(fetcher, ROOT, null, rawDir);

        assertEquals("XVII", legislature, "la première législature de la page est la plus récente");
        assertTrue(requested[1].startsWith("https://www.parlamento.pt/Cidadania/Paginas/DAComposicaoOrgaos.aspx?t="));
        assertTrue(requested[2].startsWith("https://app.parlamento.pt/webutils/docs/doc.txt?path="));
        assertTrue(Files.exists(rawDir.resolve(PtPepParliamentParser.COMPOSITION)));
    }

    @Test
    void canAskForAnotherLegislature() {
        String[] link = PtPepParliamentParser.folderLink(resource("root-page.html"), ROOT, "XVI");
        assertEquals("XVI", link[0]);
        assertThrows(IllegalStateException.class,
                () -> PtPepParliamentParser.folderLink(resource("root-page.html"), ROOT, "XX"));
    }

    @Test
    void keepsSittingSuspendedAndRecentlyFormerDeputies(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        PtPepParliamentParser.Report report = run(rawDir, LocalDate.of(2026, 1, 15), dataset);

        assertEquals(4, report.current(), "Aguiar-Branco, Francisco César, Rodrigo Saraiva, Paulo Cavaleiro");
        assertEquals(1, report.suspended(), "Luís Montenegro, mandat suspendu");
        assertEquals(2, report.former(), "Rui Cristina (fin 2025-11-02) et Darlene Costa (fin 2026-01-11)");
        assertEquals(3, report.skipped(), "un suppléant, un démissionnaire, un désistement : jamais siégé");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(7, persons(dataset));
    }

    @Test
    void dropsFormerDeputiesAfterTheRetentionWindow(@TempDir Path rawDir) throws Exception {
        PtPepParliamentParser.Report report = run(rawDir, LocalDate.of(2026, 11, 5), dataset());

        assertEquals(1, report.former(), "Rui Cristina (fin 2025-11-02) a plus de douze mois");
    }

    @Test
    void addsTheOfficesOfTheBureauAndTheSuspensionNote(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        PtPepParliamentParser.Report report = run(rawDir, LocalDate.of(2026, 1, 15), dataset);

        assertEquals(2, report.officers());
        FtmEntity president = person(dataset, "José Pedro Aguiar-Branco");
        assertTrue(president.getValues("position").contains("Presidente da Assembleia da República"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Mandat suspendu"))));
    }

    @Test
    void usesTheFullNameAndKeepsTheParliamentaryNameAsAlias(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        run(rawDir, LocalDate.of(2026, 1, 15), dataset);

        FtmEntity person = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("alias").contains("Francisco César")).findFirst().orElseThrow();
        assertTrue(person.getValues("name").iterator().next().contains(" "), "nom complet, plus long que le nom parlementaire");
        assertEquals(java.util.Set.of("role.pep"), person.getValues("topics"));
    }

    private static PtPepParliamentParser.Report run(Path rawDir, LocalDate today, FtmDatasetBuilder dataset)
            throws IOException {
        Files.writeString(rawDir.resolve(PtPepParliamentParser.COMPOSITION), resource("composicao.json"), StandardCharsets.UTF_8);
        return new PtPepParliamentParser().convert(rawDir, "XVII", ROOT, today, 12, dataset);
    }

    private static long persons(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String parliamentaryName) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && (e.getValues("name").contains(parliamentaryName) || e.getValues("alias").contains(parliamentaryName)))
                .findFirst().orElseThrow();
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(PtPepParliamentParser.ID_PREFIX, PtPepParliamentParser.TOPIC);
    }

    private static String resource(String name) {
        try (InputStream in = PtPepParliamentParserTest.class.getResourceAsStream("/parliament/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
