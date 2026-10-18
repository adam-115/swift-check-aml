package com.swiftcheck.aml.plugins.lupep.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Conversion d'un extrait réel du fichier 105 (adresses et téléphones retirés). */
class LuPepChamberParserTest {

    private static Path sample;
    private static FtmDatasetBuilder result;

    @BeforeAll
    static void convertSample() throws Exception {
        sample = Path.of(LuPepChamberParserTest.class.getResource("/chamber-current-deputies.csv").toURI());
        result = new FtmDatasetBuilder(LuPepChamberParser.ID_PREFIX, LuPepChamberParser.TOPIC);
        new LuPepChamberParser().convert(sample, result);
    }

    /** Renvoie l'entité portant cet ID, ou échoue avec un message clair. */
    private static FtmEntity entity(String id) {
        return result.entities().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    @Test
    void deputyIsAPepWithBirthDate() {
        FtmEntity person = entity("lu-pep-chamber-Adehm-Diane-1970-09-13");
        assertEquals(Set.of("Diane Adehm"), person.getValues("name"));
        assertEquals(Set.of("1970-09-13"), person.getValues("birthDate"));
        assertEquals(Set.of("female"), person.getValues("gender"), "« Madame » → female");
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertEquals(Set.of(), person.getValues("address"), "adresse non reprise");
    }

    @Test
    void mandateLinksTheDeputyToTheSharedPosition() {
        FtmEntity mandate = entity("lu-pep-chamber-Adehm-Diane-1970-09-13-deputy-2023-10-24");
        assertEquals(Set.of("lu-pep-chamber-Adehm-Diane-1970-09-13"), mandate.getValues("holder"));
        assertEquals(Set.of("lu-pep-chamber-deputy"), mandate.getValues("post"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2023-10-24"), mandate.getValues("startDate"), "« 2023-10-24 00:00:00 » → 2023-10-24");

        assertEquals(1, result.qualityReport().getEntities("Position"), "une seule fonction pour tous les députés");
    }

    @Test
    void parseWritesOneEntityPerLineWithoutRejectedValue(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("out.ndjson");
        ParserResult parsed = new LuPepChamberParser().parse(new ParserContext("lu_pep_chamber", sample, dir, output, Map.of()));

        assertEquals(5, parsed.entityCount(), "2 personnes, 1 fonction, 2 mandats");
        assertEquals(parsed.entityCount(), Files.readAllLines(output).size());
        assertEquals(0, parsed.rejectedCount());
    }

    @Test
    void sourceRecordsHaveTheSameIdsAsTheConversion() throws Exception {
        List<SourceRecord> records = new LuPepChamberParser()
                .readSourceRecords(new ParserContext("lu_pep_chamber", sample, null, null, Map.of()));

        assertEquals(List.of("lu-pep-chamber-Adehm-Diane-1970-09-13", "lu-pep-chamber-Arendt-Nancy-1969-05-22"),
                records.stream().map(SourceRecord::entityId).toList());
        assertEquals("Diane Adehm", records.get(0).name());
    }
}
