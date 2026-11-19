package com.swiftcheck.aml.plugins.sanction.ca;

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

/** Extrait réel de sema-lmes.xml (19/11/2026) : deux personnes (Bélarus), une entité (Iran), un navire (Russie). */
class CaSemaSanctionsParserTest {

    private final CaSemaSanctionsParser parser = new CaSemaSanctionsParser();

    @Test
    void convertsPersonsEntitiesAndShips() throws Exception {
        FtmDatasetBuilder dataset;
        try (InputStream in = getClass().getResourceAsStream("/ca_sample.xml")) {
            dataset = SanctionDataset.convert(parser.read(in), CaSemaSanctionsParser.ID_PREFIX, "GAC", "sanction");
        }
        FtmEntity barsukov = subject(dataset, "Aleksandr Petrovich Barsukov");
        assertEquals("ca-sema-Belarus-1-Part-1-3", barsukov.getId());
        assertTrue(barsukov.getValues("birthDate").contains("1965-04-29"));
        assertTrue(sanctionOf(dataset, barsukov).getValues("program").contains("Belarus / Bélarus"));

        FtmEntity mashadziyeu = subject(dataset, "Ruslan Khikmetavich MASHADZIYEU");
        assertTrue(mashadziyeu.getValues("alias").contains("Руслан Хикметович МАШАДИЕВ"));
        assertTrue(mashadziyeu.getValues("alias").contains("Ruslan Khikmetovich MASHADIYEV"));

        FtmEntity ship = subject(dataset, "Balitiyskiy III");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("7612448"));
        assertTrue(ship.getValues("buildDate").contains("1980"));

        assertEquals("Organization",
                subject(dataset, "Aerospace Industries Organization (AIO)/Organisation iranienne des industries aérospatiales").getSchema());
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("ca.xml");
        assumeTrue(full != null, "SANCTION_SAMPLES/ca.xml absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, CaSemaSanctionsParser.ID_PREFIX, "GAC", "sanction");
        assertTrue(records.size() > 5000);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
