package com.swiftcheck.aml.plugins.sanction.jp;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Extrait réel de shisantouketsu20261105.csv et de la page list.html du ministère des Finances (19/11/2026). */
class JpMofSanctionsParserTest {

    private final JpMofSanctionsParser parser = new JpMofSanctionsParser();

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/" + name).toURI());
    }

    @Test
    void findsTheLatestCsvOnThePage() throws Exception {
        String html = Files.readString(resource("jp_list.html"));
        assertEquals("./shisantouketsu20261105.csv", JpMofSanctionsParser.csvLink(html));
    }

    @Test
    void convertsPersonsAndEntities() throws Exception {
        FtmDatasetBuilder dataset = SanctionDataset.convert(parser.read(resource("jp_sample.csv")),
                JpMofSanctionsParser.ID_PREFIX, "MOF", "sanction");

        FtmEntity milosevic = subject(dataset, "Milosevic Slobodan");
        assertEquals("jp-mof-001-000001", milosevic.getId());
        assertTrue(milosevic.getValues("birthDate").contains("1941-08-20"));
        assertTrue(milosevic.getValues("alias").contains("ミロシェヴィッチ・スロボダン"));

        FtmEntity akhund = subject(dataset, "MOHAMMAD HASSAN AKHUND");
        assertTrue(akhund.getValues("title").contains("Mullah"));
        assertTrue(sanctionOf(dataset, akhund).getValues("listingDate").contains("2001-09-22"));

        FtmEntity qaida = subject(dataset, "AL-QAIDA");
        assertEquals("Organization", qaida.getSchema());
        assertTrue(qaida.getValues("alias").contains("Al Qaeda"));

        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void birthDates() {
        assertEquals("1970", JpMofSanctionsParser.birthDate("1970年頃"));
        assertEquals("1955", JpMofSanctionsParser.birthDate("1955年から1958年の間"));
        assertEquals("1965-04-09", JpMofSanctionsParser.birthDate("1965/4/9"));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("jp.csv");
        assumeTrue(full != null, "SANCTION_SAMPLES/jp.csv absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, JpMofSanctionsParser.ID_PREFIX, "MOF", "sanction");
        assertTrue(records.size() > 2500);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
