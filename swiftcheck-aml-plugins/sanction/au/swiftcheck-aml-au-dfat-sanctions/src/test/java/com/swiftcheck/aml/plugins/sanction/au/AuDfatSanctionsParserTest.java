package com.swiftcheck.aml.plugins.sanction.au;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugins.sanction.common.SanctionDataset;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Classeur de test construit sur les colonnes décrites par le « Guide to Australia's Consolidated List » (DFAT) :
 * le site du DFAT n'est pas joignable depuis le poste de développement. À compléter par un extrait du vrai fichier
 * dès qu'il est récupéré depuis le serveur (variable SANCTION_SAMPLES, fichier au.xlsx).
 */
class AuDfatSanctionsParserTest {

    private final AuDfatSanctionsParser parser = new AuDfatSanctionsParser();

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void groupsAliasRowsUnderTheirReference(@TempDir Path dir) throws Exception {
        List<String> header = row("Reference", "Name of Individual or Entity", "Type", "Name Type", "Alias Strength",
                "Date of Birth", "Place of Birth", "Citizenship", "Address", "Additional Information", "Listing Information",
                "IMO Number", "Committees", "Control Date", "Instrument of Designation", "Targeted Financial Sanction",
                "Travel Ban", "Arms Embargo", "Maritime Restriction");
        List<List<String>> sheet = List.of(
                row("Australian Sanctions Consolidated List"),
                header,
                row("1", "Ri Won Ho", "Individual", "Primary Name", null, "17/07/1964", null, "Democratic People's Republic of Korea",
                        null, "Position: Director of the Ministry of State Security", "UNSCR 2270", null, "1718/DPRK",
                        "45000", "Charter of the United Nations (Sanctions-DPRK) Regulations 2008", "TRUE", "TRUE", "FALSE", "FALSE"),
                row("1a", "Ri Won-ho", "Individual", "Alias", "Strong"),
                row("1b", "리원호", "Individual", "Original Script"),
                row("2", "Korea Mining Development Trading Corporation", "Entity", "Primary Name", null, null, null, null,
                        "Central District, Pyongyang", null, null, null, "1718/DPRK", "44000", null, "TRUE"),
                row("3", "CHON MA SAN", "Vessel", "Primary Name", null, null, null, null, null, null, null, "8660313",
                        "1718/DPRK", "44000", null, "FALSE", "FALSE", "FALSE", "TRUE"));
        Path xlsx = SanctionTestSupport.writeXlsx(dir.resolve("au.xlsx"), List.of(sheet));

        List<SanctionRecord> records = parser.read(xlsx);
        assertEquals(3, records.size());
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, AuDfatSanctionsParser.ID_PREFIX, "DFAT", "sanction");

        FtmEntity ri = subject(dataset, "Ri Won Ho");
        assertEquals("au-dfat-1", ri.getId());
        assertTrue(ri.getValues("alias").containsAll(List.of("Ri Won-ho", "리원호")));
        assertTrue(ri.getValues("birthDate").contains("1964-07-17"));
        assertTrue(ri.getValues("nationality").contains("kp"));
        assertTrue(sanctionOf(dataset, ri).getValues("summary").contains("Targeted Financial Sanction, Travel Ban"));

        FtmEntity ship = subject(dataset, "CHON MA SAN");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("8660313"));
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("au.xlsx");
        assumeTrue(full != null, "SANCTION_SAMPLES/au.xlsx absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, AuDfatSanctionsParser.ID_PREFIX, "DFAT", "sanction");
        assertTrue(records.size() > 1000);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
