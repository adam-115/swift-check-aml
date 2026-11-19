package com.swiftcheck.aml.plugins.sanction.pl;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Extrait réel de la page gov.pl (19/11/2026) : trois personnes et deux entités. */
class PlMswiaSanctionsParserTest {

    private final PlMswiaSanctionsParser parser = new PlMswiaSanctionsParser();

    @Test
    void convertsPersonsAndEntities() throws Exception {
        List<SanctionRecord> records = parser.read(Path.of(getClass().getResource("/pl_sample.html").toURI()));
        assertEquals(5, records.size());
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, PlMswiaSanctionsParser.ID_PREFIX, "MSWiA", "sanction");

        FtmEntity belov = subject(dataset, "BELOV Alexey");
        assertTrue(belov.getValues("alias").contains("BELOV Alexy"));
        assertTrue(belov.getValues("birthDate").contains("1963-11-15"));

        FtmEntity pschem = subject(dataset, "PSCHEM Sp. z o.o. w likwidacji");
        assertEquals("Organization", pschem.getSchema());
        assertTrue(pschem.getValues("taxNumber").contains("5272848379"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void polishBirthDates() {
        assertEquals("1973-10-05", PlMswiaSanctionsParser.birthDate("urodzony 5 października 1973 r."));
        assertEquals("1975-10-16", PlMswiaSanctionsParser.birthDate("urodzona 16.10.1975 r."));
    }

    @Test
    void fullPageHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("pl.html");
        assumeTrue(full != null, "SANCTION_SAMPLES/pl.html absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, PlMswiaSanctionsParser.ID_PREFIX, "MSWiA", "sanction");
        assertTrue(records.size() > 400, "fiches : " + records.size());
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
