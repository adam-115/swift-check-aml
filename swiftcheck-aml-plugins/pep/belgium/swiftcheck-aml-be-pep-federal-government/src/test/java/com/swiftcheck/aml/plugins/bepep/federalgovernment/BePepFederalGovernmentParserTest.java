package com.swiftcheck.aml.plugins.bepep.federalgovernment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Page réelle de belgium.be (02/11/2026) : composition du gouvernement De Wever, 15 membres. */
class BePepFederalGovernmentParserTest {

    @Test
    void readsEveryMemberWithHisFunctionAndPortrait(@TempDir Path rawDir) throws Exception {
        copyFixture(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepFederalGovernmentParser.ID_PREFIX,
                BePepFederalGovernmentParser.TOPIC);

        List<BePepFederalGovernmentParser.Member> members = new BePepFederalGovernmentParser().convert(rawDir, dataset);

        assertEquals(15, members.size());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        BePepFederalGovernmentParser.Member pm = members.get(0);
        assertEquals("Bart De Wever", pm.name());
        assertEquals("Premier ministre", pm.function());
        assertEquals("https://www.belgium.be/fr/bart_de_wever", pm.pageUrl());
        assertEquals("https://www.belgium.be/sites/default/files/De%20Wever.jpg", pm.photoUrl());

        FtmEntity verlinden = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Annelies Verlinden"))
                .findFirst().orElseThrow();
        assertEquals(Set.of("female"), verlinden.getValues("gender"));
        assertEquals(Set.of("Ministre de la Justice, chargée de la Mer du Nord"), verlinden.getValues("position"));
        assertEquals(15, dataset.media().size());
    }

    @Test
    void stopsWhenThePageNoLongerListsTheMembers(@TempDir Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve(BePepFederalGovernmentParser.FILE), "<html><body>Maintenance</body></html>");
        assertThrows(IllegalStateException.class, () -> new BePepFederalGovernmentParser().convert(rawDir,
                new FtmDatasetBuilder(BePepFederalGovernmentParser.ID_PREFIX)));
    }

    private static void copyFixture(Path rawDir) throws IOException {
        try (InputStream in = BePepFederalGovernmentParserTest.class.getResourceAsStream("/" + BePepFederalGovernmentParser.FILE)) {
            Files.write(rawDir.resolve(BePepFederalGovernmentParser.FILE), in.readAllBytes());
        }
    }
}
