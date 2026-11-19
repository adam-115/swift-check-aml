package com.swiftcheck.aml.plugins.sanction.ofac;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extrait réel de SDN.XML (18/11/2026) : une banque, une personne, un navire, un avion, une banque centrale. */
class OfacSanctionsParserTest {

    private final OfacSanctionsParser parser = new OfacSanctionsParser();

    private FtmDatasetBuilder sample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ofac_sample.xml")) {
            return SanctionDataset.convert(parser.read(in), OfacSanctionsParser.ID_PREFIX, "OFAC", "sanction");
        }
    }

    @Test
    void convertsPersonWithDocumentsAndDates() throws Exception {
        FtmDatasetBuilder dataset = sample();
        FtmEntity person = subject(dataset, "Zayn al-Abidin Muhammad HUSAYN");
        assertEquals("us-ofac-sdn-6905", person.getId());
        assertTrue(person.getValues("passportNumber").contains("484824"));
        assertTrue(person.getValues("birthDate").contains("1971-03-12"));
        assertTrue(person.getValues("gender").contains("male"));
        assertTrue(person.getValues("alias").contains("Zain Al-Abidin Muhammad HUSAIN"));
        assertTrue(person.getValues("weakAlias").contains("ABU ZUBAIDA"), "alias « weak » de l'OFAC");
        FtmEntity sanction = sanctionOf(dataset, person);
        assertTrue(sanction.getValues("program").contains("SDGT"));
        assertTrue(sanction.getValues("provisions").stream().anyMatch(p -> p.contains("Executive Order 13224")));
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void convertsVesselsAircraftAndCryptoAddresses() throws Exception {
        FtmDatasetBuilder dataset = sample();
        FtmEntity vessel = subject(dataset, "ARTAVIL");
        assertEquals("Vessel", vessel.getSchema());
        assertTrue(vessel.getValues("imoNumber").contains("9187629"));
        assertTrue(vessel.getValues("mmsi").contains("572469210"));
        assertTrue(vessel.getValues("flag").contains("ir"));
        assertTrue(vessel.getValues("pastFlags").contains("mt"));

        FtmEntity plane = subject(dataset, "EP-GOL");
        assertEquals("Airplane", plane.getSchema());
        assertTrue(plane.getValues("model").contains("IL-76TD"));
        assertTrue(plane.getValues("serialNumber").contains("1013409297"));

        FtmEntity bank = subject(dataset, "BANK MARKAZI JOMHOURI ISLAMI IRAN");
        assertTrue(bank.getValues("notes").stream().anyMatch(n -> n.contains("TNiq9AXBp9EjUqhDhrwrfvAA8U3GUQZH81")));
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullSdnAndConsolidatedFilesHaveNoRejectedValue() throws Exception {
        for (String file : List.of("ofac_sdn.xml", "ofac_cons.xml")) {
            Path full = SanctionTestSupport.fullSample(file);
            assumeTrue(full != null, "SANCTION_SAMPLES/" + file + " absent");
            List<SanctionRecord> records = parser.read(full);
            FtmDatasetBuilder dataset = SanctionDataset.convert(records, "us-ofac-test", "OFAC", "sanction");
            assertTrue(records.size() > 400, file);
            assertEquals(0, dataset.qualityReport().getTotalRejected(), file + " " + rejects(dataset));
            assertEquals(0, dataset.qualityReport().getIdConflictCount(), file);
        }
    }
}
