package com.swiftcheck.aml.plugins.sanction.ustrade;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
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

/** Extrait réel de consolidated.csv (19/11/2026) : une fiche SSI du Trésor et une fiche par liste BIS / State. */
class UsCslSanctionsParserTest {

    private final UsCslSanctionsParser parser = new UsCslSanctionsParser();

    private Path sample() throws Exception {
        return Path.of(getClass().getResource("/csl_sample.csv").toURI());
    }

    @Test
    void skipsTreasuryListsByDefault() throws Exception {
        List<SanctionRecord> records = parser.read(sample(), UsCslSanctionsParser.DEFAULT_EXCLUDE);
        assertEquals(6, records.size(), "la fiche SSI (Trésor) est lue par le plugin OFAC");
        assertEquals(7, parser.read(sample(), "").size());
    }

    @Test
    void convertsBisAndStateLists() throws Exception {
        FtmDatasetBuilder dataset = SanctionDataset.convert(parser.read(sample(), UsCslSanctionsParser.DEFAULT_EXCLUDE),
                UsCslSanctionsParser.ID_PREFIX, "CSL");

        FtmEntity ghoura = subject(dataset, "Abdul Satar Ghoura");
        assertEquals("LegalEntity", ghoura.getSchema());
        assertEquals("us-csl-EL-Abdul-Satar-Ghoura", ghoura.getId());
        assertTrue(ghoura.getValues("country").containsAll(List.of("af", "pk")));
        assertTrue(ghoura.getValues("topics").contains("export.control"));
        FtmEntity sanction = sanctionOf(dataset, ghoura);
        assertTrue(sanction.getValues("authority").contains("US Bureau of Industry and Security"));
        assertTrue(sanction.getValues("startDate").contains("2011-11-21"));

        FtmEntity acn = subject(dataset, "SPC Supachoke");
        assertTrue(acn.getValues("alias").contains("Super Trade"));
        assertTrue(acn.getValues("topics").contains("sanction"));
        assertTrue(sanctionOf(dataset, acn).getValues("program").contains("Chemical and Biological Weapons Act"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("csl.csv");
        assumeTrue(full != null, "SANCTION_SAMPLES/csl.csv absent");
        List<SanctionRecord> records = parser.read(full, "");
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, UsCslSanctionsParser.ID_PREFIX, "CSL");
        assertTrue(records.size() > 15000);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
