package com.swiftcheck.aml.plugins.frpep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Conversion de la biographie officielle publiée sur elysee.fr (page réelle, octobre 2026). */
class FrPepPresidentParserTest {

    private static final String PAGE = "/elysee-emmanuel-macron.html";

    @Test
    void readsTheOfficialBiography() throws Exception {
        FrPepPresidentParser.President p = FrPepPresidentParser.read(resource());

        assertEquals("Emmanuel Macron", p.name());
        assertEquals("Emmanuel", p.firstName());
        assertEquals("Macron", p.lastName());
        assertEquals("1977-12", p.birthDate(), "la page ne donne que le mois et l'année");
        assertEquals("Amiens", p.birthPlace());
        assertEquals("2017", p.startYear());
        assertEquals("https://www.elysee.fr/emmanuel-macron", p.pageUrl());
    }

    @Test
    void readsAFullBirthDate() {
        FrPepPresidentParser.President p = FrPepPresidentParser.read("<meta property=\"og:title\" content=\"Jeanne Martin\">"
                + "<p>Jeanne Martin, présidente de la République depuis 2032.</p>"
                + "<p>Née le 1er mars 1980 à Saint-Étienne. Elle a étudié…</p>");

        assertEquals("1980-03-01", p.birthDate());
        assertEquals("Saint-Étienne", p.birthPlace());
        assertEquals("2032", p.startYear());
    }

    @Test
    void refusesAPageThatIsNotThePresidentsBiography() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FrPepPresidentParser.read("<html><title>Page introuvable</title></html>"));
        assertEquals(true, e.getMessage().contains("président de la République"));
    }

    @Test
    void writesThePersonTheFunctionAndTheMandate(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource());
        Path output = dir.resolve("out.ndjson");

        ParserResult result = new FrPepPresidentParser()
                .parse(new ParserContext("fr_pep_president", input, dir, output, Map.of()));
        List<SourceRecord> records = new FrPepPresidentParser()
                .readSourceRecords(new ParserContext("fr_pep_president", input, null, null, Map.of()));

        assertEquals(3, result.entityCount(), "personne, fonction, mandat");
        assertEquals(0, result.rejectedCount());
        assertEquals(3, Files.readAllLines(output).size());
        FtmEntity person = result.entities().stream().filter(e -> e.getSchema().equals("Person")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), person.getValues("topics"));
        assertEquals(Set.of("1977-12"), person.getValues("birthDate"));
        FtmEntity mandate = result.entities().stream().filter(e -> e.getSchema().equals("Occupancy")).findFirst().orElseThrow();
        assertEquals(Set.of(person.getId()), mandate.getValues("holder"));
        assertEquals(Set.of("2017"), mandate.getValues("startDate"));
        assertEquals(List.of(person.getId()), records.stream().map(SourceRecord::entityId).toList());
        assertEquals("Emmanuel Macron", records.get(0).name());
    }

    private static String resource() throws Exception {
        try (InputStream in = FrPepPresidentParserTest.class.getResourceAsStream(PAGE)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
