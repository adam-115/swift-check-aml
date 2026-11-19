package com.swiftcheck.aml.plugins.sanction.ch;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel de la Gesamtliste SECO (13/11/2026) : une personne active (Bélarus), un navire (Corée du Nord) et une
 * personne radiée en 2016, avec les programmes et les lieux auxquels ils renvoient.
 */
class ChSecoSanctionsParserTest {

    private final ChSecoSanctionsParser parser = new ChSecoSanctionsParser();

    private List<SanctionRecord> sample() throws Exception {
        return parser.read(Path.of(getClass().getResource("/ch_sample.xml").toURI()));
    }

    @Test
    void skipsDelistedTargets() throws Exception {
        List<SanctionRecord> records = sample();
        assertEquals(2, records.size());
        assertFalse(records.stream().anyMatch(r -> r.name().contains("Lukomski")), "radié le 01/03/2016");
    }

    @Test
    void convertsPersonWithPlacesAndVessel() throws Exception {
        FtmDatasetBuilder dataset = SanctionDataset.convert(sample(), ChSecoSanctionsParser.ID_PREFIX, "SECO", "sanction");
        FtmEntity person = dataset.entities().stream().filter(e -> e.getId().equals("ch-seco-4617")).findFirst().orElseThrow();
        assertEquals("Person", person.getSchema());
        assertTrue(person.getValues("lastName").contains("Paulichenka"));
        assertTrue(person.getValues("birthDate").contains("1966"));
        assertTrue(person.getValues("birthPlace").stream().anyMatch(p -> p.contains("Vitebsk")));
        assertTrue(person.getValues("country").contains("by"));
        assertTrue(sanctionOf(dataset, person).getValues("program").contains("Belarus"));

        FtmEntity ship = subject(dataset, "Hui Chon");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("8405270"));
        assertTrue(ship.getValues("alias").contains("Hwang Gum San 2"));
        assertTrue(sanctionOf(dataset, ship).getValues("program").contains("North Korea"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("ch.xml");
        assumeTrue(full != null, "SANCTION_SAMPLES/ch.xml absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, ChSecoSanctionsParser.ID_PREFIX, "SECO", "sanction");
        assertTrue(records.size() > 4000, "fiches actives : " + records.size());
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
