package com.swiftcheck.aml.plugins.iepep.stateenterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Pages réelles des conseils d'ESB, d'Uisce Éireann et de Bord na Móna (5 novembre 2026). */
class IePepStateEnterprisesParserTest {

    private static final IePepStateEnterprisesParser.Entity ESB =
            new IePepStateEnterprisesParser.Entity("esb", "ESB", "https://esb.ie/board");
    private static final IePepStateEnterprisesParser.Entity UISCE =
            new IePepStateEnterprisesParser.Entity("uisce_eireann", "Uisce Éireann", "https://www.water.ie/board");
    private static final IePepStateEnterprisesParser.Entity BNM =
            new IePepStateEnterprisesParser.Entity("bord_na_mona", "Bord na Móna", "https://www.bnm.ie/board");

    @Test
    void readsTheTwelveEsbDirectorsWithTheirRole() throws Exception {
        List<IePepStateEnterprisesParser.Director> list = IePepStateEnterprisesParser.extract(ESB, resource("esb.html"));

        assertEquals(12, list.size());
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Terence O'Rourke") && "Chairman".equals(d.role())));
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Gráinne O'Shea") && d.role() == null), "fada décodé, sans titre");
    }

    @Test
    void readsTheTenUisceEireannDirectors() throws Exception {
        List<IePepStateEnterprisesParser.Director> list = IePepStateEnterprisesParser.extract(UISCE, resource("uisce-eireann.html"));

        assertEquals(10, list.size());
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Jerry Grant") && "Chairperson".equals(d.role())));
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Niall Gleeson") && "CEO".equals(d.role())));
    }

    @Test
    void readsTheBordNaMonaBoardOnlyAndSplitsNamesFromRoles() throws Exception {
        List<IePepStateEnterprisesParser.Director> list = IePepStateEnterprisesParser.extract(BNM, resource("bord-na-mona.html"));

        assertEquals(12, list.size(), "les trois liens « Risk management… » après le conseil sont écartés");
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Brendan Lenihan") && "Chairperson".equals(d.role())));
        assertTrue(list.stream().anyMatch(d -> d.name().equals("Tom Donnellan") && "CEO".equals(d.role())));
        assertTrue(list.stream().noneMatch(d -> d.name().contains("Risk")));
    }

    @Test
    void writesPersonsPositionsAndOccupancies(@TempDir Path rawDir) throws Exception {
        List<IePepStateEnterprisesParser.Entity> entities = List.of(ESB, UISCE, BNM);
        IePepStateEnterprisesParser.download(url -> resource(url.contains("esb") ? "esb.html"
                : url.contains("water") ? "uisce-eireann.html" : "bord-na-mona.html"), entities, rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(IePepStateEnterprisesParser.ID_PREFIX, IePepStateEnterprisesParser.TOPIC);

        String message = new IePepStateEnterprisesParser().convert(rawDir, entities, dataset);

        assertTrue(message.startsWith("34 administrateurs"), message);
        assertEquals(34, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
        FtmEntity chair = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Terence O'Rourke")).findFirst().orElseThrow();
        assertEquals(Set.of("role.pep"), chair.getValues("topics"));
        assertTrue(chair.getValues("position").contains("Chairman, ESB"));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema()) && e.getValues("topics").contains("gov.soe")));
    }

    @Test
    void entitiesParameterIsValidated() {
        assertEquals(2, IePepStateEnterprisesParser.entities("esb|ESB|https://a; uisce_eireann|Uisce|https://b").size());
        assertThrows(IllegalStateException.class, () -> IePepStateEnterprisesParser.entities(" "));
        assertThrows(IllegalStateException.class, () -> IePepStateEnterprisesParser.entities("esb|ESB"));
        assertThrows(IllegalStateException.class, () -> IePepStateEnterprisesParser.entities("inconnu|X|https://x"));
    }

    @Test
    void failsClearlyWhenAPageHasNoName(@TempDir Path rawDir) throws Exception {
        java.nio.file.Files.writeString(rawDir.resolve("esb.html"), "<html>Verifying your browser</html>");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new IePepStateEnterprisesParser()
                .convert(rawDir, List.of(ESB), new FtmDatasetBuilder(IePepStateEnterprisesParser.ID_PREFIX)));
        assertTrue(e.getMessage().contains("ESB"));
    }

    private static String resource(String name) {
        try (InputStream in = IePepStateEnterprisesParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
