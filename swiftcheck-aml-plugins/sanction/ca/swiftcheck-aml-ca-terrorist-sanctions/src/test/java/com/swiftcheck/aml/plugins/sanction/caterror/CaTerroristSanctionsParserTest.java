package com.swiftcheck.aml.plugins.sanction.caterror;

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

/** Page réelle complète « Currently listed entities » (19/11/2026, 89 entités). */
class CaTerroristSanctionsParserTest {

    @Test
    void convertsAllListedEntities() throws Exception {
        List<SanctionRecord> records = new CaTerroristSanctionsParser()
                .read(Path.of(getClass().getResource("/ca_terror.html").toURI()));
        assertEquals(89, records.size());
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, CaTerroristSanctionsParser.ID_PREFIX, "PS",
                "sanction", "crime.terror");

        FtmEntity asg = subject(dataset, "Abu Sayyaf Group (ASG)");
        assertEquals("ca-terror-3", asg.getId());
        assertTrue(asg.getValues("alias").contains("Al Harakat Al Islamiyya (AHAI)"));
        assertTrue(asg.getValues("alias").contains("Mujahideen Commando Freedom Fighters (MCFF)"));
        assertTrue(asg.getValues("topics").contains("crime.terror"));
        assertTrue(sanctionOf(dataset, asg).has("listingDate"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
