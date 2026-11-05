package com.swiftcheck.aml.plugins.itpep.localadministrators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.itpep.localadministrators.ItPepLocalAdministratorsParser.Report;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Lignes réelles des fichiers {@code ammcom.csv} et {@code ammreg.csv} du ministère de l'Intérieur (mis à jour le
 * 24/08/2026), réduites à quelques personnes : Acqui Terme (20 054 habitants), Alessandria, Asti, deux petites
 * communes, et les Régions Piémont et Lombardie.
 */
class ItPepLocalAdministratorsParserTest {

    private static final Path COMMUNES = Path.of("src/test/resources/localadministrators/ammcom.csv");
    private static final Path REGIONS = Path.of("src/test/resources/localadministrators/ammreg.csv");

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(ItPepLocalAdministratorsParser.ID_PREFIX, ItPepLocalAdministratorsParser.TOPIC);
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains(name)).findFirst()
                .orElseThrow(() -> new AssertionError("personne absente : " + name));
    }

    private static List<FtmEntity> mandates(FtmDatasetBuilder dataset, FtmEntity holder) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(holder.getId())).toList();
    }

    @Test
    void keepsTheMayorsOfCommunesOfAtLeast15000InhabitantsOnly() throws IOException {
        FtmDatasetBuilder dataset = dataset();

        Report report = new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 15_000, Set.of(), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(3, report.mayors(), "Acqui Terme, Alessandria, Asti");
        assertEquals(2, report.smallCommunesSkipped(), "Sala Monferrato (340) et Sale (3 916)");
        assertEquals("24/08/2026", report.communesUpdated());

        FtmEntity rapetti = person(dataset, "Danilo Rapetti");
        assertEquals(Set.of("1971-03-05"), rapetti.getValues("birthDate"));
        assertEquals(Set.of("Acqui Terme"), rapetti.getValues("birthPlace"));
        assertEquals(Set.of("male"), rapetti.getValues("gender"));
        assertEquals(Set.of("role.pep"), rapetti.getValues("topics"));
        FtmEntity mandate = mandates(dataset, rapetti).get(0);
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2022-06-26"), mandate.getValues("startDate"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Sindaco di Acqui Terme (Italie)")
                && e.getValues("topics").contains("gov.muni")));
    }

    @Test
    void communalAssessorsAndCouncillorsAreNotInTheLaw() throws IOException {
        FtmDatasetBuilder dataset = dataset();
        new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 15_000, Set.of(), dataset);

        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Rosanna Benazzo")
                || e.getValues("name").contains("Fabrizio Baldizzone")));
    }

    @Test
    void aSmallCapoluogoCanBeAddedByItsCode() throws IOException {
        FtmDatasetBuilder dataset = dataset();

        Report report = new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 15_000, Set.of("002/1510"), dataset);
        assertEquals(3, report.mayors(), "le code ne correspond à aucune ligne du fichier réduit");

        FtmDatasetBuilder withAll = dataset();
        Report all = new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 0, Set.of(), withAll);
        assertEquals(5, all.mayors(), "avec un seuil à 0, toutes les petites communes entrent");
    }

    @Test
    void regionsKeepPresidentAssessorsAndCouncillorsButNotSubstitutes() throws IOException {
        FtmDatasetBuilder dataset = dataset();

        Report report = new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 15_000, Set.of(), dataset);

        assertEquals(2, report.presidents(), "Cirio et Fontana");
        assertEquals(6, report.regionalAssessors(), "2 assesseurs élus du Piémont, 2 élus et 2 non élus de Lombardie");
        assertEquals(8, report.regionalCouncillors(), "conseillers, y compris « candidats président » ; sans suppléants");
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Federica Barbero")),
                "conseillère suppléante");

        FtmEntity cirio = person(dataset, "Alberto Cirio");
        assertEquals(Set.of("1972-12-06"), cirio.getValues("birthDate"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Président de la Région Piemonte (Italie)")
                && e.getValues("topics").contains("gov.head")));

        FtmEntity fontana = person(dataset, "Attilio Fontana");
        assertEquals(2, mandates(dataset, fontana).size(), "président et conseiller « candidat président »");
    }

    @Test
    void noPersonalDataBeyondTheLawIsKept() throws IOException {
        FtmDatasetBuilder dataset = dataset();
        new ItPepLocalAdministratorsParser().convert(COMMUNES, REGIONS, 15_000, Set.of(), dataset);

        assertTrue(dataset.entities().stream().noneMatch(e -> !e.getValues("education").isEmpty()
                || !e.getValues("profession").isEmpty() || !e.getValues("politicalGroup").isEmpty()));
    }

    @Test
    void parsesQuotedFieldsAndEmptyOnes() {
        List<String> fields = AdministratorsCsv.split("\"01\";\"A; B\";;\"x\"\"y\";\"\"");
        assertEquals(List.of("01", "A; B", "", "x\"y", ""), fields);
        assertEquals("Sant'Angelo", ItPepLocalAdministratorsParser.titleCase("SANT'ANGELO"));
        assertEquals("1971-03-05", ItPepLocalAdministratorsParser.isoDate("05/03/1971"));
        assertNull(ItPepLocalAdministratorsParser.isoDate("n/a"));
        assertEquals("president", ItPepLocalAdministratorsParser.regionRole("Presidente della regione"));
        assertNull(ItPepLocalAdministratorsParser.regionRole("Consigliere supplente"));
        assertEquals("assessor", ItPepLocalAdministratorsParser.regionRole("Assessore non di origine elettiva"));
    }

    @Test
    void refusesAFileWithoutTheExpectedColumns(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path bad = dir.resolve("bad.csv");
        java.nio.file.Files.writeString(bad, "\"titre\"\n\"Aggiornato al 01/01/2026\"\n\"a\";\"b\"\n");
        assertThrows(IllegalStateException.class, () -> AdministratorsCsv.forEach(bad, r -> { }));
        assertThrows(IllegalStateException.class, () -> new ItPepLocalAdministratorsParser()
                .convert(COMMUNES, COMMUNES, 15_000, Set.of(), dataset()), "pas de président de Région dans un fichier communal");
    }
}
