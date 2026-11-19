package com.swiftcheck.aml.plugins.sanction.uk;

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

/** Extrait réel de UK-Sanctions-List.xml (17/11/2026) : une entité, une personne et un navire. */
class UkFcdoSanctionsParserTest {

    private final UkFcdoSanctionsParser parser = new UkFcdoSanctionsParser();

    private FtmDatasetBuilder sample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/uk_sample.xml")) {
            return SanctionDataset.convert(parser.read(in), UkFcdoSanctionsParser.ID_PREFIX, "UK", "sanction");
        }
    }

    @Test
    void convertsIndividualEntityAndShip() throws Exception {
        FtmDatasetBuilder dataset = sample();

        FtmEntity person = subject(dataset, "NAJIBULLAH HAQQANI HIDAYATULLAH");
        assertEquals("uk-fcdo-AFG0055", person.getId());
        assertTrue(person.getValues("birthDate").containsAll(List.of("1972-10-24", "1971")));
        assertTrue(person.getValues("passportNumber").contains("D0009871"));
        assertTrue(person.getValues("idNumber").contains("545167"));
        assertTrue(person.getValues("alias").contains("Najibullah HAQANI"));
        FtmEntity sanction = sanctionOf(dataset, person);
        assertTrue(sanction.getValues("unscId").contains("TAi.071"));
        assertTrue(sanction.getValues("listingDate").contains("2001-02-23"));

        FtmEntity exchange = subject(dataset, "HAJI KHAIRULLAH HAJI SATTAR MONEY EXCHANGE");
        assertEquals("Organization", exchange.getSchema());
        assertTrue(exchange.getValues("country").containsAll(List.of("af", "ae", "ir")));

        FtmEntity ship = subject(dataset, "Petrel 8");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("9562233"));
        assertTrue(ship.getValues("flag").contains("km"));
        assertTrue(ship.getValues("alias").contains("Tian Tai 36"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void partialDates() {
        assertEquals("1971", UkFcdoSanctionsParser.partialDate("dd/mm/1971"));
        assertEquals("12/1960", UkFcdoSanctionsParser.partialDate("dd/12/1960"));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("uk.xml");
        assumeTrue(full != null, "SANCTION_SAMPLES/uk.xml absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, UkFcdoSanctionsParser.ID_PREFIX, "UK", "sanction");
        assertTrue(records.size() > 5000);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
