package com.swiftcheck.aml.plugins.espep.regulators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extrait réel de la page « Miembros del Pleno » de csn.es (3 novembre 2026). */
class EsPepRegulatorsParserTest {

    private static String resource(String file) throws IOException {
        try (InputStream in = EsPepRegulatorsParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheMembersOfThePleno() throws Exception {
        List<EsPepRegulatorsParser.Member> members = EsPepRegulatorsParser.members(resource("csn.html"),
                EsPepRegulatorsParser.institutions(null).get(0));

        assertEquals(5, members.size());
        assertTrue(members.contains(new EsPepRegulatorsParser.Member("Juan Carlos Lentijo Lentijo", "Presidente")));
        assertTrue(members.contains(new EsPepRegulatorsParser.Member("María del Pilar Lucio Carrasco", "Vicepresidenta")));
        assertTrue(members.contains(new EsPepRegulatorsParser.Member("Silvia Calzón Fernández", "Consejera")));
    }

    @Test
    void writesPersonsPositionsAndMandates(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("csn.html"), resource("csn.html"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepRegulatorsParser.ID_PREFIX, EsPepRegulatorsParser.TOPIC);

        int members = new EsPepRegulatorsParser().convert(EsPepRegulatorsParser.institutions(null), dir, dataset);

        assertEquals(5, members);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity president = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Juan Carlos Lentijo Lentijo")).findFirst().orElseThrow();
        assertTrue(president.getValues("position").contains("Presidente – Consejo de Seguridad Nuclear"));
        assertEquals(Set.of("role.pep"), president.getValues("topics"));
        assertEquals(Set.of("es"), president.getValues("nationality"));
    }

    @Test
    void mergesTheAbbreviatedNameOfTheSamePerson() {
        EsPepRegulatorsParser.Institution i = new EsPepRegulatorsParser.Institution("x", "X", "http://x",
                java.util.regex.Pattern.compile("<a>(?<role>[^<]+)</a><b>(?<name>[^<]+)</b>"), List.of("gov.state"));
        List<EsPepRegulatorsParser.Member> members = EsPepRegulatorsParser.members(
                "<a>Presidente</a><b>Juan Carlos Lentijo Lentijo</b><a>Presidente</a><b>Juan Carlos Lentijo</b>", i);
        assertEquals(1, members.size());
        assertEquals("Juan Carlos Lentijo Lentijo", members.get(0).name());
    }

    @Test
    void failsWhenThePageChanged(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("csn.html"), "<html><body>Nouvelle page</body></html>");
        assertThrows(IllegalStateException.class, () -> new EsPepRegulatorsParser()
                .convert(EsPepRegulatorsParser.institutions(null), dir,
                        new FtmDatasetBuilder(EsPepRegulatorsParser.ID_PREFIX, EsPepRegulatorsParser.TOPIC)));
    }

    @Test
    void refusesAnUnknownInstitution() {
        assertThrows(IllegalStateException.class, () -> EsPepRegulatorsParser.institutions(Map.of("institutions", "inexistant")));
    }
}
