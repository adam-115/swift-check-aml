package com.swiftcheck.aml.plugins.lupep.governmentformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests unitaires du parser {@link LuPepGovernmentFormerParser} sur un extrait réel de la page
 * officielle de gouvernement.lu.
 */
class LuPepGovernmentFormerParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private Path htmlFile;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        htmlFile = Path.of(getClass().getResource("/gouvernement/anciens-membres-gouvernement.html").toURI());
    }

    @Test
    void parseMembersExtractsAllMembers() throws Exception {
        String content = Files.readString(htmlFile);
        Map<String, LuPepGovernmentFormerParser.MemberData> members = LuPepGovernmentFormerParser.parseMembers(content);
        assertFalse(members.isEmpty(), "La liste des anciens membres ne doit pas être vide");
        assertEquals(66, members.size(), "La page doit contenir 66 anciens membres du gouvernement");

        assertTrue(members.containsKey("ARENDT Guy"));
        assertTrue(members.containsKey("ASSELBORN Jean"));
        assertTrue(members.containsKey("JUNCKER Jean-Claude"));
        assertTrue(members.containsKey("MISCHO Georges"));
        assertTrue(members.containsKey("HENNICOT-SCHOEPGES Erna"));
    }

    @Test
    void splitHeaderNameSplitsCorrectly() {
        String[] arendt = LuPepGovernmentFormerParser.splitHeaderName("ARENDT Guy");
        assertEquals("Guy", arendt[0]);
        assertEquals("ARENDT", arendt[1]);

        String[] hennicot = LuPepGovernmentFormerParser.splitHeaderName("HENNICOT-SCHOEPGES Erna");
        assertEquals("Erna", hennicot[0]);
        assertEquals("HENNICOT-SCHOEPGES", hennicot[1]);

        String[] asselborn = LuPepGovernmentFormerParser.splitHeaderName("ASSELBORN Jean");
        assertEquals("Jean", asselborn[0]);
        assertEquals("ASSELBORN", asselborn[1]);
    }

    @Test
    void parseDateHandlesBothNumericAndTextMonths() {
        assertEquals(LocalDate.of(2018, 12, 5), LuPepGovernmentFormerParser.parseDate("05.12.2018"));
        assertEquals(LocalDate.of(2015, 12, 18), LuPepGovernmentFormerParser.parseDate("18.12.2015"));
        assertEquals(LocalDate.of(2023, 11, 17), LuPepGovernmentFormerParser.parseDate("17 novembre 2023"));
        assertEquals(LocalDate.of(2025, 12, 11), LuPepGovernmentFormerParser.parseDate("11 décembre 2025"));
    }

    @Test
    void parseMandateLineExtractsEndedAndCurrentMandates() {
        LuPepGovernmentFormerParser.MandateData ended = LuPepGovernmentFormerParser.parseMandateLine(
                "Secrétaire d’État à la Culture, du 18.12.2015 au 05.12.2018");
        assertNotNull(ended);
        assertEquals("Secrétaire d’État à la Culture", ended.function);
        assertEquals(LocalDate.of(2015, 12, 18), ended.startDate);
        assertEquals(LocalDate.of(2018, 12, 5), ended.endDate);
        assertFalse(ended.isCurrent);

        LuPepGovernmentFormerParser.MandateData current = LuPepGovernmentFormerParser.parseMandateLine(
                "Ministre de la Défense depuis le 17.11.2023");
        assertNotNull(current);
        assertEquals("Ministre de la Défense", current.function);
        assertEquals(LocalDate.of(2023, 11, 17), current.startDate);
        assertNull(current.endDate);
        assertTrue(current.isCurrent);
    }

    @Test
    void convertBuildsCompleteFtmEntities() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-government-former", "role.pep");
        // Sans filtre de date (oldestEndDate = null)
        new LuPepGovernmentFormerParser().convert(htmlFile, null, dataset);

        java.util.Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        // Recherche d'un membre emblématique : Jean Asselborn
        FtmEntity asselborn = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jean ASSELBORN"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Jean ASSELBORN non trouvé"));

        assertEquals(Set.of("Jean"), asselborn.getValues("firstName"));
        assertEquals(Set.of("ASSELBORN"), asselborn.getValues("lastName"));
        assertEquals(Set.of("lu"), asselborn.getValues("country"));
        assertEquals(Set.of("role.pep"), asselborn.getValues("topics"));

        // Vérifier qu'il a ses mandats
        List<FtmEntity> occupancies = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(asselborn.getId()))
                .toList();
        assertTrue(occupancies.size() >= 10, "Jean Asselborn doit avoir plusieurs mandats ministériels");

        FtmEntity sampleOccupancy = occupancies.get(0);
        assertEquals(Set.of("ended"), sampleOccupancy.getValues("status"));
        assertNotNull(sampleOccupancy.getValues("post"));
    }

    @Test
    void convertWithYearsFilterKeepsRecentFormerMembers() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("lu-pep-government-former", "role.pep");
        // Filtre 5 ans par rapport à TODAY (2026-10-19) -> mandats terminés après 2021-10-19
        LocalDate limit = TODAY.minusYears(5);
        new LuPepGovernmentFormerParser().convert(htmlFile, limit, dataset);

        java.util.Collection<FtmEntity> entities = dataset.entities();
        assertFalse(entities.isEmpty());

        // Asselborn (fin de mandat 16.11.2023) doit être présent
        boolean hasAsselborn = entities.stream()
                .anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jean ASSELBORN"));
        assertTrue(hasAsselborn, "Jean Asselborn a quitté le gouvernement en novembre 2023 : il doit être conservé");
    }

    @Test
    void readSourceRecordsProducesValidRecords() throws Exception {
        ParserContext ctx = new ParserContext("lu_pep_government_former", htmlFile, null, null, Map.of("yearsAfterEnd", "0"));
        List<SourceRecord> records = new LuPepGovernmentFormerParser().readSourceRecords(ctx);
        assertNotNull(records);
        assertEquals(66, records.size(), "66 personnes sources doivent être retournées pour le test");

        SourceRecord sample = records.stream()
                .filter(r -> r.name().contains("Jean ASSELBORN"))
                .findFirst()
                .orElseThrow();
        assertEquals("Person", sample.schema());
        assertTrue(sample.entityId().startsWith("lu-pep-government-former-"));
    }

    @Test
    void parseWritesNdjsonFileAndReturnsResult() throws Exception {
        Path outputFile = tempDir.resolve("lu_pep_government_former.ndjson");
        ParserContext ctx = new ParserContext("lu_pep_government_former", htmlFile, null, outputFile, Map.of("yearsAfterEnd", "0"));

        ParserResult result = new LuPepGovernmentFormerParser().parse(ctx);
        assertNotNull(result);
        assertTrue(result.entityCount() > 0);
        assertEquals(0, result.rejectedCount());
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);
    }

    @Test
    void parseUsesSourceUrlFromContextParameters() throws Exception {
        String customUrl = "https://custom.gouvernement.lu/anciens.html";
        Path outputFile = tempDir.resolve("custom_output.ndjson");
        ParserContext ctx = new ParserContext("lu_pep_government_former", htmlFile, null, outputFile,
                Map.of("yearsAfterEnd", "0", "sourceUrl", customUrl));

        new LuPepGovernmentFormerParser().parse(ctx);
        List<SourceRecord> records = new LuPepGovernmentFormerParser().readSourceRecords(ctx);
        assertNotNull(records);
        assertFalse(records.isEmpty());
        assertEquals(customUrl, records.get(0).sourceRef(), "L'URL source doit provenir du ParserContext");
    }
}
