package com.swiftcheck.aml.plugins.sanction.nz;

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

/** Lignes réelles du registre (mise à jour du 09/09/2026) recopiées dans un petit classeur de test. */
class NzMfatSanctionsParserTest {

    private final NzMfatSanctionsParser parser = new NzMfatSanctionsParser();

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void convertsRegisterAndShips(@TempDir Path dir) throws Exception {
        List<String> header = row("Type", "Unique Identifier", "First name", "Middle name(s)", "Last name", "Name of Asset",
                "Name of Service", "DOB", "Title", "Alias/Alternate Spellings", "Address", "Place of Birth", "Citizenship",
                "Citizenship 2", "Citizenship 3", "Passport Number", "Sanction Status", "Date of Sanction",
                "Date of Additional Sanction", "Travel Ban", "Asset Freeze", "Aircraft Ban", "Ship Ban",
                "Service Prohibition", "Dealing with Securities", "General Rationale for Sanction", "Associates/Relatives");
        List<List<String>> register = List.of(
                row("Sanction Type", "Link to Description"),
                header,
                row("Individual", "IND-1", "Vladimir", "Vladimirovich", "Putin", null, null, "19274",
                        "President of the Russian Federation", null, null, null, null, null, null, null, "Sanctioned",
                        "44638", "44656", "Yes", "Yes", "Yes", "Yes", "Yes", "Yes", "President of the Russian Federation", null),
                row("Individual", "IND-999", "Test", null, "Delisted", null, null, null, null, null, null, null, null, null,
                        null, null, "Delisted", "44638"));
        List<List<String>> ships = List.of(
                row("Type", "Unique Identifier", "IMO Number", "Name of Ship as of Date of Sanction", "Date of Sanction",
                        "Alias/Alternate Names", "Date of Additional Sanction", "Sanction Status", "Travel Ban",
                        "Asset Freeze", "Aircraft Ban", "Ship Ban"),
                row("Ship", "SHP-1", "9288693", "Andaman Skies", "45827", "Mirador", null, "Sanctioned", null, null, null, "Yes"));
        Path xlsx = SanctionTestSupport.writeXlsx(dir.resolve("nz.xlsx"), List.of(register, ships));

        List<SanctionRecord> records = parser.read(xlsx);
        assertEquals(2, records.size(), "la fiche radiée est ignorée");
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, NzMfatSanctionsParser.ID_PREFIX, "MFAT", "sanction");

        FtmEntity putin = subject(dataset, "Vladimir Vladimirovich Putin");
        assertEquals("nz-mfat-IND-1", putin.getId());
        assertTrue(putin.getValues("birthDate").contains("1952-10-07"));
        assertTrue(sanctionOf(dataset, putin).getValues("listingDate").contains("2022-03-18"));

        FtmEntity ship = subject(dataset, "Andaman Skies");
        assertEquals("Vessel", ship.getSchema());
        assertTrue(ship.getValues("imoNumber").contains("9288693"));
        assertTrue(ship.getValues("alias").contains("Mirador"));
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }

    @Test
    void fullFileHasNoRejectedValue() throws Exception {
        Path full = SanctionTestSupport.fullSample("nz.xlsx");
        assumeTrue(full != null, "SANCTION_SAMPLES/nz.xlsx absent");
        List<SanctionRecord> records = parser.read(full);
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, NzMfatSanctionsParser.ID_PREFIX, "MFAT", "sanction");
        assertTrue(records.size() > 1500, "fiches : " + records.size());
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }
}
