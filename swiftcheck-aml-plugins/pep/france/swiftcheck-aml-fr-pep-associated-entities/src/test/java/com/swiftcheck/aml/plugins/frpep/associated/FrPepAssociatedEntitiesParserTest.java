package com.swiftcheck.aml.plugins.frpep.associated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel de declarations.xml (HATVP, 27/10/2026) : deux déclarations du député européen Gregory Allione
 * (2024 et 2025), celle du ministre David Amiel, et celle d'un élu départemental (à ignorer).
 */
class FrPepAssociatedEntitiesParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @TempDir
    static Path dir;
    private static Path xml;

    @BeforeAll
    static void copy() throws Exception {
        xml = dir.resolve("declarations.xml");
        try (InputStream in = FrPepAssociatedEntitiesParserTest.class.getResourceAsStream("/declarations-extrait.xml")) {
            Files.copy(in, xml);
        }
    }

    @Test
    void readsTheLatestDeclarationOfEachPep() throws Exception {
        List<HatvpDeclarations.Declaration> declarations = HatvpDeclarations.read(xml,
                Set.of("depute", "senateur", "gouvernement", "europe"));

        assertEquals(2, declarations.size(), "l'élu départemental est ignoré, Gregory Allione compté une fois");
        HatvpDeclarations.Declaration allione = declarations.get(0);
        assertEquals("2025-05-27 09:12:28", allione.depositDate(), "la déclaration la plus récente");
        assertEquals("1971-07-25", allione.birthDate());
        assertEquals(4, allione.directorships().size());
        assertEquals(new HatvpDeclarations.Directorship("OFPRA", "membre du conseil d'administration en tant que député européen",
                "2024-10", null), allione.directorships().get(3));
        assertTrue(allione.holdings().isEmpty(), "« SCI [Données non publiées] » : nom masqué, écarté");
    }

    @Test
    void writesCurrentDirectorshipsOnly() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepAssociatedEntitiesParser.ID_PREFIX);

        FrPepAssociatedEntitiesParser.Report report = new FrPepAssociatedEntitiesParser()
                .convert(xml, FrPepAssociatedEntitiesParser.Rules.of(Map.of(), TODAY), dataset);

        assertEquals(2, report.declarations());
        assertEquals(2, report.pepsWithLinks());
        assertEquals(3, report.directorships(), "HURRICANE, OFPRA, Floréal ; pas les présidences terminées en 12/2022");
        assertEquals(3, report.organizations());
        assertEquals(0, dataset.qualityReport().getTotalRejected());

        FtmEntity amiel = dataset.entities().stream().filter(e -> e.getValues("name").contains("David Amiel")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), amiel.getValues("topics"));
        assertEquals(Set.of("1992-11-28"), amiel.getValues("birthDate"));
        FtmEntity floreal = dataset.entities().stream().filter(e -> e.getValues("name").contains("Floréal")).findFirst().orElseThrow();
        assertEquals(Set.of("role.rca"), floreal.getValues("topics"));
        FtmEntity link = dataset.entities().stream().filter(e -> "Directorship".equals(e.getSchema())
                && e.getValues("organization").contains(floreal.getId())).findFirst().orElseThrow();
        assertEquals(Set.of(amiel.getId()), link.getValues("director"));
        assertEquals(Set.of("Président"), link.getValues("role"));
        assertEquals(Set.of("2024-11"), link.getValues("startDate"));
    }

    @Test
    void significantHoldingsFollowTheThresholds() {
        FrPepAssociatedEntitiesParser.Rules rules = FrPepAssociatedEntitiesParser.Rules.of(Map.of(), TODAY);
        assertTrue(rules.keep(new HatvpDeclarations.Holding("SARL X", 50.0, 500.0, "50")), "50 % du capital");
        assertTrue(rules.keep(new HatvpDeclarations.Holding("AIRBUS", null, 150_000.0, "900")), "150 000 €");
        assertEquals(false, rules.keep(new HatvpDeclarations.Holding("REXEL", null, 426.0, "20")), "20 actions cotées");
        assertEquals(false, rules.keep(new HatvpDeclarations.Holding("SAS Y", 5.0, 2000.0, "10")));
        FrPepAssociatedEntitiesParser.Rules strict = FrPepAssociatedEntitiesParser.Rules.of(Map.of("minCapitalPercent", "60"), TODAY);
        assertEquals(false, strict.keep(new HatvpDeclarations.Holding("SARL X", 50.0, 500.0, "50")));
        assertThrows(IllegalArgumentException.class,
                () -> FrPepAssociatedEntitiesParser.Rules.of(Map.of("minValue", "100k"), TODAY));
    }

    @Test
    void cleansNamesAndNumbers() {
        assertNull(HatvpDeclarations.published("SCI \n        [Données non publiées]\n     "));
        assertNull(HatvpDeclarations.published("néant"));
        assertEquals("Floréal", HatvpDeclarations.published("  Floréal "));
        assertEquals(1985.5, HatvpDeclarations.number("1 985,50"));
        assertEquals(50.0, HatvpDeclarations.number("50 %"));
        assertNull(HatvpDeclarations.number("environ"));
    }

    @Test
    void sourceRecordsListTheOrganizations(@TempDir Path out) throws Exception {
        List<SourceRecord> records = new FrPepAssociatedEntitiesParser()
                .readSourceRecords(new ParserContext("fr_pep_associated_entities", xml, out, null, Map.of()));
        assertTrue(records.stream().allMatch(r -> r.schema().equals("Organization")));
        assertTrue(records.stream().anyMatch(r -> r.name().equals("OFPRA")));
    }
}
