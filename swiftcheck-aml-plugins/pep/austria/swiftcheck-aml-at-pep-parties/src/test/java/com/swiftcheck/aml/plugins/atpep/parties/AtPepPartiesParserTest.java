package com.swiftcheck.aml.plugins.atpep.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.atpep.parties.PartyRegister.Entry;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Registre réel des partis du ministère de l'Intérieur (PDF du 5 novembre 2026). */
class AtPepPartiesParserTest {

    private static final Path PDF = Path.of("src/test/resources/parteienregister.pdf");

    private static Entry entry(List<Entry> entries, String number) {
        return entries.stream().filter(e -> e.party().number().equals(number)).findFirst().orElseThrow();
    }

    @Test
    void readsTheRepresentativesOfTheParliamentaryParties() throws IOException {
        List<Entry> entries = PartyRegister.read(AtPepPartiesParser.text(PDF), AtPepPartiesParser.parties(
                AtPepPartiesParser.DEFAULT_PARTIES));

        List<PartyRegister.Person> ovp = entry(entries, "500004").people();
        assertEquals(3, ovp.size(), ovp.toString());
        assertEquals("Christian Stocker", ovp.get(0).name());
        assertTrue(ovp.get(0).role().startsWith("Bundesparteiobmann"));
        assertTrue(ovp.stream().anyMatch(p -> p.name().equals("Andreas Ottenschläger")
                && p.role().equals("Bundesfinanzreferent")), "mot coupé par un trait d'union en fin de ligne");

        List<PartyRegister.Person> spoe = entry(entries, "500002").people();
        assertTrue(spoe.stream().anyMatch(p -> p.name().equals("Andreas Babler")));
        assertEquals(3, spoe.size(), spoe.toString());

        List<PartyRegister.Person> fpoe = entry(entries, "500008").people();
        assertTrue(fpoe.stream().anyMatch(p -> p.name().equals("Herbert Kickl")));
        assertTrue(fpoe.stream().anyMatch(p -> p.name().equals("Udo Landbauer")), "« MA » est un titre");
        assertTrue(fpoe.size() >= 8, fpoe.toString());

        assertTrue(entry(entries, "500106").people().stream().anyMatch(p -> p.name().equals("Leonore Gewessler")));
    }

    @Test
    void aPartyThatDeclaredNobodyIsReportedNotInvented() throws IOException {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(AtPepPartiesParser.ID_PREFIX, AtPepPartiesParser.TOPIC);

        String message = AtPepPartiesParser.convert(PDF, AtPepPartiesParser.parties(AtPepPartiesParser.DEFAULT_PARTIES), dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(message.contains("Représentants non déclarés au registre : NEOS"), message);
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").stream().anyMatch(n -> n.contains("NEOS")
                && "Person".equals(e.getSchema()))));
    }

    @Test
    void anUnknownRegisterNumberIsReportedAndAllUnknownStops() {
        List<Entry> entries = PartyRegister.read("rien", List.of(new PartyRegister.Party("500004", "ÖVP")));
        assertEquals(false, entries.get(0).found());
        assertThrows(IllegalArgumentException.class, () -> AtPepPartiesParser.parties("abc"));
    }
}
