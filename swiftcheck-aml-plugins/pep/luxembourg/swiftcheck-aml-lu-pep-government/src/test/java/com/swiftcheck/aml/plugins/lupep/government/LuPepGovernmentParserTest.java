package com.swiftcheck.aml.plugins.lupep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Lecture d'un extrait réel de la page gouvernement.lu/fr/gouvernement.html. */
class LuPepGovernmentParserTest {

    private static Path sample() throws Exception {
        return Path.of(LuPepGovernmentParserTest.class.getResource("/government.html").toURI());
    }

    @Test
    void eachMemberBecomesAPepWithACurrentMandate() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepGovernmentParser.ID_PREFIX, LuPepGovernmentParser.TOPIC);
        new LuPepGovernmentParser().convert(sample(), dataset);

        FtmEntity person = dataset.entities().stream()
                .filter(e -> e.getId().equals("lu-pep-government-luc-frieden"))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of("Luc FRIEDEN"), person.getValues("name"));
        assertEquals(Set.of("FRIEDEN"), person.getValues("lastName"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));

        assertEquals(7, dataset.qualityReport().getTotalEntities(), "3 personnes, 1 fonction, 3 mandats");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void eachMemberOnceWithoutTheFormerMembersLink() throws Exception {
        List<SourceRecord> records = new LuPepGovernmentParser()
                .readSourceRecords(new ParserContext("lu_pep_government", sample(), null, null, Map.of()));

        assertEquals(List.of("Luc FRIEDEN", "Martine HANSEN", "Serge WILMES"),
                records.stream().map(SourceRecord::name).toList(), "Luc Frieden est deux fois dans la page");
        assertEquals("lu-pep-government-luc-frieden", records.get(0).entityId());
    }

    @Test
    void nameIsSplitOnUpperCaseWords() {
        String[] name = LuPepGovernmentParser.splitName("Elisabeth MARGUE");
        assertEquals("Elisabeth", name[0]);
        assertEquals("MARGUE", name[1]);
    }

    @Test
    void pageWithoutMemberStopsTheConversion(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("page.html");
        Files.writeString(page, "<html><body>Nouvelle mise en page</body></html>");

        assertThrows(IllegalStateException.class,
                () -> new LuPepGovernmentParser().convert(page, new FtmDatasetBuilder("test")));
    }

    /** Parser configuré en « Géré par le plugin » : le core ne télécharge rien, le message dit quoi corriger. */
    @Test
    void parserConfiguredAsPluginManagedGivesAClearMessage(@TempDir Path dir) {
        ParserContext ctx = new ParserContext("lu_pep_government", null, dir, dir.resolve("out.ndjson"), Map.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LuPepGovernmentParser().parse(ctx));
        assertTrue(error.getMessage().contains("HTTP_FILE"), error.getMessage());
        assertThrows(IllegalStateException.class, () -> new LuPepGovernmentParser().readSourceRecords(ctx));
    }
}
