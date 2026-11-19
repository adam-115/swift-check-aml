package com.swiftcheck.aml.plugins.sanction.fr;

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

/** Extrait réel de la publication du 18/11/2026 : une personne (Ukraine), une société russe, un navire nord-coréen. */
class FrDgTresorSanctionsParserTest {

    private final FrDgTresorSanctionsParser parser = new FrDgTresorSanctionsParser();

    @Test
    void convertsPersonsCompaniesAndVessels() throws Exception {
        FtmDatasetBuilder dataset;
        try (InputStream in = getClass().getResourceAsStream("/fr_sample.json")) {
            dataset = SanctionDataset.convert(parser.read(in), FrDgTresorSanctionsParser.ID_PREFIX, "DGT", "sanction");
        }
        FtmEntity person = subject(dataset, "Aleksey Alekseevich NAYDENKO");
        assertEquals("fr-dgt-1001", person.getId());
        assertTrue(person.getValues("birthDate").contains("1980-06-02"));
        assertTrue(person.getValues("alias").contains("NAYDENKO Oleksii Oleksiyovych"));
        FtmEntity sanction = sanctionOf(dataset, person);
        assertTrue(sanction.getValues("program").contains("UE Ukraine intégrité territoriale - R (UE) 269/2014"));

        FtmEntity company = subject(dataset, "JSC Gruppa Kremniy El");
        assertTrue(company.getValues("registrationNumber").contains("1023202741781"));
        assertTrue(company.getValues("taxNumber").contains("3234043140"));

        FtmEntity ship = subject(dataset, "HUI CHON");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("8405270"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("fr.json");
        assumeTrue(full != null, "SANCTION_SAMPLES/fr.json absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, FrDgTresorSanctionsParser.ID_PREFIX, "DGT", "sanction");
        assertTrue(records.size() > 8000);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
