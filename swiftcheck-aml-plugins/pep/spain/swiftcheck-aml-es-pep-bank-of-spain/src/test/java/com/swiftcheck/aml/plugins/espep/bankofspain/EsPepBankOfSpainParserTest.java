package com.swiftcheck.aml.plugins.espep.bankofspain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extraits réels des pages « Consejo de Gobierno » et « Comisión Ejecutiva » de bde.es (3 novembre 2026). */
class EsPepBankOfSpainParserTest {

    private static String resource(String file) throws IOException {
        try (InputStream in = EsPepBankOfSpainParserTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path rawDir(Path dir) throws IOException {
        Files.writeString(dir.resolve(EsPepBankOfSpainParser.COUNCIL_FILE), resource("consejo-gobierno.html"));
        Files.writeString(dir.resolve(EsPepBankOfSpainParser.EXECUTIVE_FILE), resource("comision-ejecutiva.html"));
        return dir;
    }

    @Test
    void readsTheMembersOfTheCouncil() throws Exception {
        List<EsPepBankOfSpainParser.Member> members = EsPepBankOfSpainParser.members(resource("consejo-gobierno.html"));

        assertEquals(10, members.size());
        assertEquals("José Luis Escrivá", members.get(0).name());
        assertEquals("Gobernador", members.get(0).role());
        assertEquals("Soledad Núñez", members.get(1).name());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Judith Arnal") && m.role().equals("Consejera")));
        assertTrue(members.stream().anyMatch(m -> m.role().startsWith("Secretaria General del Tesoro")));
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Paloma Marín") && m.role().equals("Vicepresidenta de la CNMV")));
    }

    @Test
    void doesNotTakeTheDirectorsWhoOnlyAttend() throws Exception {
        List<EsPepBankOfSpainParser.Member> members = EsPepBankOfSpainParser.members(resource("consejo-gobierno.html"));
        assertTrue(members.stream().noneMatch(m -> m.name().equals("Juan Ayuso")));
        assertTrue(members.stream().noneMatch(m -> m.name().equals("Francisco Javier Priego")));
    }

    @Test
    void writesPersonsAndTheirPostsWithBothBodies(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepBankOfSpainParser.ID_PREFIX, EsPepBankOfSpainParser.TOPIC);
        int members = new EsPepBankOfSpainParser().convert(rawDir(dir), dataset);

        assertEquals(10, members);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        FtmEntity governor = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("José Luis Escrivá")).findFirst().orElseThrow();
        assertTrue(governor.getValues("position").contains("Gobernador del Banco de España"));
        assertTrue(governor.getValues("position").contains("Miembro del Consejo de Gobierno del Banco de España"));
        assertTrue(governor.getValues("position").contains("Miembro de la Comisión Ejecutiva del Banco de España"));
        assertEquals(Set.of("es"), governor.getValues("nationality"));
        assertEquals(Set.of("role.pep"), governor.getValues("topics"));

        FtmEntity fernando = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Fernando Fernández")).findFirst().orElseThrow();
        assertEquals(2, fernando.getValues("position").size(), "consejero au Conseil et à la Commission exécutive");
        FtmEntity judith = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Judith Arnal")).findFirst().orElseThrow();
        assertEquals(1, judith.getValues("position").size());
    }

    @Test
    void failsWhenTheGovernorIsMissing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(EsPepBankOfSpainParser.COUNCIL_FILE), "<html><h2>Composici&oacute;n</h2><p>x</p></html>");
        Files.writeString(dir.resolve(EsPepBankOfSpainParser.EXECUTIVE_FILE), resource("comision-ejecutiva.html"));
        assertThrows(IllegalStateException.class, () -> new EsPepBankOfSpainParser().convert(dir,
                new FtmDatasetBuilder(EsPepBankOfSpainParser.ID_PREFIX, EsPepBankOfSpainParser.TOPIC)));
    }
}
