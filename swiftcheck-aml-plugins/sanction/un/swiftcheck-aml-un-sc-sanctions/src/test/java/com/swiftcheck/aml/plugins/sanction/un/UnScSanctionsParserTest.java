package com.swiftcheck.aml.plugins.sanction.un;

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

import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extrait réel de consolidated.xml (19/11/2026) : 3 personnes (RDC, Taliban) et 2 entités (RDC). */
class UnScSanctionsParserTest {

    private final UnScSanctionsParser parser = new UnScSanctionsParser();

    private List<SanctionRecord> sample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/un_sample.xml")) {
            return parser.read(in);
        }
    }

    @Test
    void convertsIndividualsAndEntities() throws Exception {
        FtmDatasetBuilder dataset = SanctionDataset.convert(sample(), UnScSanctionsParser.ID_PREFIX, "UNSC", "sanction");

        FtmEntity akhund = subject(dataset, "MOHAMMAD HASSAN AKHUND");
        assertEquals("Person", akhund.getSchema());
        assertEquals("un-sc-110577", akhund.getId());
        assertTrue(akhund.getValues("alias").contains("محمد حسن آخوند"));
        assertTrue(akhund.getValues("birthDate").containsAll(List.of("1955", "1958", "1945", "1950")));
        assertTrue(akhund.getValues("nationality").contains("af"));
        assertTrue(akhund.getValues("title").contains("Mullah"));
        assertTrue(akhund.getValues("topics").contains("sanction"));

        FtmEntity sanction = sanctionOf(dataset, akhund);
        assertTrue(sanction.getValues("program").contains("Taliban"));
        assertTrue(sanction.getValues("unscId").contains("TAi.002"));
        assertTrue(sanction.getValues("listingDate").contains("2001-01-25"));

        FtmEntity adf = subject(dataset, "ADF");
        assertEquals("Organization", adf.getSchema());
        assertTrue(adf.getValues("alias").contains("Allied Democratic Forces"));
        assertTrue(adf.getValues("previousName").contains("ADF/NALU"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void listsOneSourceRecordPerFiche() throws Exception {
        List<SourceRecord> records = SanctionDataset.sourceRecords(sample(), UnScSanctionsParser.ID_PREFIX);
        assertEquals(5, records.size());
        assertTrue(records.stream().anyMatch(r -> r.entityId().equals("un-sc-6908024")
                && r.name().equals("BUTEMBO AIRLINES (BAL)") && r.schema().equals("Organization")));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("un.xml");
        assumeTrue(full != null, "SANCTION_SAMPLES/un.xml absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, UnScSanctionsParser.ID_PREFIX, "UNSC", "sanction");
        assertTrue(records.size() > 900, "environ 1 000 fiches");
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
