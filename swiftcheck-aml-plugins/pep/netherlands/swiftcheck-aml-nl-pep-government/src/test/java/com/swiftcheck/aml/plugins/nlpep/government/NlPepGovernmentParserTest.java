package com.swiftcheck.aml.plugins.nlpep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles de rijksoverheid.nl (5 novembre 2026) : la liste des bewindspersonen et deux fiches (Rob Jetten, Eric van der Burg). */
class NlPepGovernmentParserTest {

    private static final String LIST = "https://www.rijksoverheid.nl/regering/bewindspersonen";

    @Test
    void readsTheMembersOfTheListWithTheirRole() {
        List<NlPepGovernmentParser.Member> members = NlPepGovernmentParser.members(resource("bewindspersonen.html"), LIST);

        assertTrue(members.size() >= 25, "ministres et secrétaires d'État : " + members.size());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Rob Jetten") && m.role().startsWith("Minister-president")
                && m.slug().equals("rob-jetten")));
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Eric van der Burg") && m.role().startsWith("Staatssecretaris")));
        assertTrue(members.get(0).url().startsWith("https://www.rijksoverheid.nl/regering/bewindspersonen/"));
    }

    @Test
    void readsBirthPartyAndAppointmentFromAMemberPage() {
        var jetten = NlPepGovernmentParser.details(resource("rob-jetten.html"));
        assertEquals("1987-03-25", jetten.birthDate());
        assertEquals("Veghel", jetten.birthPlace());
        assertEquals("D66", jetten.party());
        assertEquals("2026-02-23", jetten.appointed());

        var burg = NlPepGovernmentParser.details(resource("eric-van-der-burg.html"));
        assertEquals("1965-10-09", burg.birthDate());
        assertEquals("Amsterdam", burg.birthPlace());
        assertEquals("VVD", burg.party());
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        List<String> requested = new ArrayList<>();
        NlPepGovernmentParser.download(url -> {
            requested.add(url);
            if (url.equals(LIST)) {
                return resource("bewindspersonen.html");
            }
            return resource(url.endsWith("/eric-van-der-burg") ? "eric-van-der-burg.html" : "rob-jetten.html");
        }, LIST, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(NlPepGovernmentParser.ID_PREFIX, NlPepGovernmentParser.TOPIC);

        int count = new NlPepGovernmentParser().convert(rawDir, dataset);

        assertEquals(requested.size() - 1, count, "une fiche par membre");
        assertEquals(count, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
        FtmEntity pm = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Rob Jetten")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), pm.getValues("topics"));
        assertEquals(Set.of("1987-03-25"), pm.getValues("birthDate"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.head")));
    }

    @Test
    void failsClearlyWhenTheListHasNoMember(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> NlPepGovernmentParser.download(url -> "<html></html>", LIST, rawDir));
    }

    private static String resource(String name) {
        try (InputStream in = NlPepGovernmentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
