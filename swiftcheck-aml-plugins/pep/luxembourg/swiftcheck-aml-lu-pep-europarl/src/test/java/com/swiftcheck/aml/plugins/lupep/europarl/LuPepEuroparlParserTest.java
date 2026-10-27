package com.swiftcheck.aml.plugins.lupep.europarl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
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

/** Conversion d'un extrait réel de la liste XML (deux députés luxembourgeois et un député allemand). */
class LuPepEuroparlParserTest {

    private static FtmDatasetBuilder result;

    @BeforeAll
    static void convertSample() throws Exception {
        result = new FtmDatasetBuilder(LuPepEuroparlParser.ID_PREFIX, LuPepEuroparlParser.TOPIC);
        try (InputStream in = LuPepEuroparlParserTest.class.getResourceAsStream("/europarl-meps.xml")) {
            new LuPepEuroparlParser().convert(in, result);
        }
    }

    /** Renvoie l'entité portant cet ID, ou échoue avec un message clair. */
    private static FtmEntity entity(String id) {
        return result.entities().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    @Test
    void memberElectedInLuxembourgIsAPep() {
        FtmEntity person = entity("lu-pep-europarl-202073");                 // ID officiel du Parlement européen
        assertEquals(Set.of("Marc ANGEL"), person.getValues("name"));
        assertEquals(Set.of("Marc"), person.getValues("firstName"));
        assertEquals(Set.of("ANGEL"), person.getValues("lastName"));
        assertEquals(Set.of("Parti ouvrier socialiste luxembourgeois"), person.getValues("political"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));

        FtmEntity mandate = entity("lu-pep-europarl-202073-member");
        assertEquals(Set.of("lu-pep-europarl-member"), mandate.getValues("post"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
    }

    @Test
    void membersFromOtherCountriesAreIgnored() {
        assertEquals(2, result.qualityReport().getEntities("Person"), "député allemand ignoré");
        assertEquals(Set.of("WISELER-LIMA"), entity("lu-pep-europarl-197421").getValues("lastName"));
        assertEquals(0, result.qualityReport().getTotalRejected());
    }

    @Test
    void parseAndSourceRecordsUseTheFileGivenByTheCore(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("source.xml");
        try (InputStream in = LuPepEuroparlParserTest.class.getResourceAsStream("/europarl-meps.xml")) {
            Files.copy(in, input);
        }
        Path output = dir.resolve("out.ndjson");

        ParserResult parsed = new LuPepEuroparlParser()
                .parse(new ParserContext("lu_pep_europarl", input, dir, output, Map.of()));
        List<SourceRecord> records = new LuPepEuroparlParser()
                .readSourceRecords(new ParserContext("lu_pep_europarl", input, null, null, Map.of()));

        assertEquals(5, parsed.entityCount(), "2 personnes, 1 fonction, 2 mandats");
        assertEquals(parsed.entityCount(), Files.readAllLines(output).size());
        // Les entités trouvées sont rendues dans le bilan, en plus du fichier NDJSON
        assertEquals(parsed.entityCount(), parsed.entities().size());
        assertEquals(Set.of("Marc ANGEL"), parsed.entities().stream()
                .filter(e -> e.getId().equals("lu-pep-europarl-202073")).findFirst().orElseThrow().getValues("name"));
        assertEquals(List.of("lu-pep-europarl-202073", "lu-pep-europarl-197421"),
                records.stream().map(SourceRecord::entityId).toList());
    }

    /** Réponse réelle de l'API open data (octobre 2026) : mêmes ID que la liste XML. */
    @Test
    void readsTheOpenDataApi() throws Exception {
        FtmDatasetBuilder api = new FtmDatasetBuilder(LuPepEuroparlParser.ID_PREFIX, LuPepEuroparlParser.TOPIC);
        try (InputStream in = LuPepEuroparlParserTest.class.getResourceAsStream("/europarl-meps-lu.jsonld")) {
            new LuPepEuroparlParser().convert(in, api);
        }

        assertEquals(6, api.qualityReport().getEntities("Person"));
        assertEquals(0, api.qualityReport().getTotalRejected());
        FtmEntity metz = api.entities().stream().filter(e -> e.getId().equals("lu-pep-europarl-193292"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("Tilly METZ"), metz.getValues("name"));
        assertEquals(Set.of("Tilly"), metz.getValues("firstName"));
        assertEquals(Set.of("METZ"), metz.getValues("lastName"));
        // Marc Angel : même ID FtM qu'avec la liste XML, donc pas de nouvelle entité dans Yente
        assertEquals(true, api.entities().stream().anyMatch(e -> e.getId().equals("lu-pep-europarl-202073")));
    }
}
