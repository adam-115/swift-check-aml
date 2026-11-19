package com.swiftcheck.aml.plugins.sanction.be;

import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.rejects;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.sanctionOf;
import static com.swiftcheck.aml.plugins.sanction.common.SanctionTestSupport.subject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Lignes réelles de la liste nationale (AR du 30/05/2026) recopiées dans un petit classeur de test. */
class BeNationalSanctionsParserTest {

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void findsTheXlsxLink() {
        String html = "<a href=\"https://finances.belgium.be/sites/default/files/thesaurie/SIFi/20260608%20-%20Liste%20nationale_AR%2030.05.2026.xlsx\">Liste nationale (XLSX, 49.33 Ko)</a>";
        assertTrue(BeNationalSanctionsParser.xlsxLink(html).endsWith("Liste%20nationale_AR%2030.05.2026.xlsx"));
    }

    @Test
    void convertsTheNationalList(@TempDir Path dir) throws Exception {
        List<List<String>> sheet = List.of(
                row(null, "LISTE NATIONALE CONSOLIDÉE DES PERSONNES ET ENTITES ..."),
                row(null, "Nom", "Prénom(s)", "N° Registre Nationale", "Date de naissance", "Date AR", "Entrée en vigueur",
                        "Dernière confirmation", "lien vers la publication"),
                row("1", "ABRAÏMI", "Nour-Eddine", "87.02.28-365.54", "1987-02-28", "2026-05-30", "2026-06-08", "22 mai 2026",
                        "https://www.ejustice.just.fgov.be/cgi/article.pl?language=fr&sum_date=2026-06-08&lg_txt=f&numac_search=2026004158"),
                row("2"),
                row("278", "EL FIKRI", "Billel", "85.06.29-247.31", "29 juin 1985", "21 mars 2019", "27 mars 2019", "22 mai 2026",
                        "http://www.ejustice.just.fgov.be/eli/arrete/2019/03/21/2019011361/moniteur"),
                row("304", "OUMAROVA", "Rayana Oumarovna", "02.12.29-192.63", "29 décembre 2002", "2021-10-25", "2021-10-29",
                        "22 mai 2026", "http://www.ejustice.just.fgov.be/eli/arrete/2021/10/25/2021042960/moniteur"));
        Path xlsx = SanctionTestSupport.writeXlsx(dir.resolve("be.xlsx"), List.of(sheet));

        List<SanctionRecord> records = new BeNationalSanctionsParser().read(xlsx);
        assertEquals(3, records.size(), "les lignes vides (personnes retirées) sont ignorées");
        FtmDatasetBuilder dataset = SanctionDataset.convert(records, BeNationalSanctionsParser.ID_PREFIX, "BE",
                "sanction", "crime.terror");

        FtmEntity elFikri = subject(dataset, "Billel EL FIKRI");
        assertEquals("be-national-85062924731", elFikri.getId());
        assertTrue(elFikri.getValues("birthDate").contains("1985-06-29"));
        FtmEntity sanction = sanctionOf(dataset, elFikri);
        assertTrue(sanction.getValues("listingDate").contains("2019-03-21"));
        assertTrue(subject(dataset, "Rayana Oumarovna OUMAROVA").getValues("birthDate").contains("2002-12-29"));
        assertEquals(0, dataset.qualityReport().getTotalRejected(), rejects(dataset));
    }
}
