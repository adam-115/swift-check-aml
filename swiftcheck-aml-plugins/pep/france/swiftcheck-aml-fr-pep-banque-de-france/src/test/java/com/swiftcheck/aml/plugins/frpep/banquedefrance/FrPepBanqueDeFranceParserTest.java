package com.swiftcheck.aml.plugins.frpep.banquedefrance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

/** Page réelle « Gouvernance et comités consultatifs » de banque-france.fr (octobre 2026). */
class FrPepBanqueDeFranceParserTest {

    @Test
    void readsTheMembersOfTheGeneralCouncil() throws Exception {
        List<FrPepBanqueDeFranceParser.Member> members = FrPepBanqueDeFranceParser.members(resource());

        assertEquals(11, members.size(), "le censeur et son suppléant ne sont pas membres");
        assertEquals(new FrPepBanqueDeFranceParser.Member("Emmanuel Moulin", "gouverneur, président du Conseil général"),
                members.get(0));
        assertEquals(new FrPepBanqueDeFranceParser.Member("Anne-Laure Delatte", null), members.get(3));
        assertEquals("Elisabeth de Longevialle", members.get(10).name());
        assertTrue(members.stream().noneMatch(m -> m.name().contains("Bories")));
    }

    @Test
    void refusesAPageWithoutTheSection() {
        assertThrows(IllegalStateException.class, () -> FrPepBanqueDeFranceParser.members("<html><h3>Autre</h3></html>"));
    }

    @Test
    void writesMembersAndSourceRecords(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.html");
        Files.writeString(input, resource());

        ParserResult result = new FrPepBanqueDeFranceParser()
                .parse(new ParserContext("fr_pep_banque_de_france", input, dir, dir.resolve("out.ndjson"), Map.of()));
        List<SourceRecord> records = new FrPepBanqueDeFranceParser()
                .readSourceRecords(new ParserContext("fr_pep_banque_de_france", input, null, null, Map.of()));

        assertEquals(23, result.entityCount(), "11 personnes, 11 mandats, 1 fonction");
        assertEquals(0, result.rejectedCount());
        FtmEntity moulin = result.entities().stream().filter(e -> e.getValues("name").contains("Emmanuel Moulin")).findFirst().orElseThrow();
        assertEquals(Set.of("Membre du Conseil général de la Banque de France",
                "Gouverneur, président du Conseil général (Banque de France)"), moulin.getValues("position"));
        assertEquals(Set.of("role.pep"), moulin.getValues("topics"));
        assertEquals(11, records.size());
        assertEquals(moulin.getId(), records.get(0).entityId());
    }

    private static String resource() throws Exception {
        try (InputStream in = FrPepBanqueDeFranceParserTest.class.getResourceAsStream("/gouvernance.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
