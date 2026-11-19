package com.swiftcheck.aml.plugins.sanction.nl;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Fichier réel complet ned-terrorismelijst.ods (état au 11/09/2026). */
class NlTerrorSanctionsParserTest {

    @Test
    void convertsTheWholeList() throws Exception {
        List<SanctionRecord> records = new NlTerrorSanctionsParser()
                .read(Path.of(getClass().getResource("/nl_sample.ods").toURI()));
        assertTrue(records.size() > 50, "fiches : " + records.size());
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, NlTerrorSanctionsParser.ID_PREFIX, "NL",
                "sanction", "crime.terror");

        FtmEntity boutahar = subject(dataset, "Mohammed Amine Boutahar");
        assertEquals("Person", boutahar.getSchema());
        assertTrue(boutahar.getValues("alias").contains("Abu Ubaida al Maghribi"));
        assertTrue(boutahar.getValues("birthDate").contains("1983-04-04"));
        assertTrue(boutahar.getValues("topics").contains("crime.terror"));
        assertTrue(sanctionOf(dataset, boutahar).getValues("listingDate").contains("2015-08-19"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
