package com.swiftcheck.aml.plugins.ptpep.courts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle « Composição » du Tribunal de Contas (tcontas.pt, 5 novembre 2026). */
class CourtPagesTest {

    private static final Path PAGE = Path.of("src/test/resources/courts/tcontas.html");

    private static List<String> lines() throws IOException {
        return PageText.lines(Files.readString(PAGE, StandardCharsets.UTF_8));
    }

    @Test
    void readsThePresidentTheVicePresidentAndTheJudges() throws IOException {
        List<CourtPages.Member> members = CourtPages.tribunalDeContas(lines());

        assertTrue(members.size() >= 15, "président, vice-président et au moins 13 juges conseillers : " + members.size());
        assertEquals("Filipa Urbano Calvão", members.get(0).name());
        assertEquals("Presidente do Tribunal de Contas", members.get(0).role());
        assertEquals("António Francisco Martins", members.get(1).name());
        assertEquals("Vice-Presidente", members.get(1).role());
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Paulo Joaquim da Mota Osório Dá Mesquita")),
                "un nom coupé par une balise en ligne est recollé");
        assertTrue(members.stream().anyMatch(m -> m.name().equals("Helena Maria Mateus de Vasconcelos Abreu Lopes")
                && m.role().equals("Juíza Conselheira")));
    }

    @Test
    void neverTakesAnotherLineForAName() throws IOException {
        for (CourtPages.Member m : CourtPages.tribunalDeContas(lines())) {
            assertTrue(m.name().matches("\\p{Lu}[^0-9|_]+"), m.name());
            assertTrue(!m.name().contains("Secção") && !m.name().contains("Ver mais"), m.name());
        }
    }

    @Test
    void writesPersonsPositionsAndCurrentMandates() throws IOException {
        PtPepCourtsParser.CourtConfig court = PtPepCourtsParser.loadConfig().courts().get(0);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PtPepCourtsParser.ID_PREFIX, PtPepCourtsParser.TOPIC);

        String message = PtPepCourtsParser.convert(
                List.of(new PtPepCourtsParser.Outcome(court, CourtPages.tribunalDeContas(lines()), null)), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(message.contains("personnes lues sur 1 juridiction"), message);
        FtmEntity calvao = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Filipa Urbano Calvão")).findFirst().orElseThrow();
        assertTrue(calvao.getValues("position").contains("Presidente do Tribunal de Contas – Tribunal de Contas"));
        assertTrue(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()))
                .allMatch(e -> e.getValues("status").contains("current")));
    }

    @Test
    void reportsACourtWhosePageFailsAndStopsWhenNoneIsReadable(@TempDir Path dir) throws IOException {
        PtPepCourtsParser.Config config = PtPepCourtsParser.loadConfig();
        String html = Files.readString(PAGE, StandardCharsets.UTF_8);

        List<PtPepCourtsParser.Outcome> ok = PtPepCourtsParser.readAll(config, url -> html, dir);
        assertEquals(null, ok.get(0).problem());
        assertTrue(Files.exists(dir.resolve("tcontas.html")), "page archivée dans rawDir");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PtPepCourtsParser.readAll(config, url -> {
            throw new IOException("HTTP 503");
        }, dir));
        assertTrue(e.getMessage().contains("tcontas"));
        assertThrows(IllegalStateException.class, () -> PtPepCourtsParser.readAll(config, url -> "<html>autre page</html>", dir));
    }
}
