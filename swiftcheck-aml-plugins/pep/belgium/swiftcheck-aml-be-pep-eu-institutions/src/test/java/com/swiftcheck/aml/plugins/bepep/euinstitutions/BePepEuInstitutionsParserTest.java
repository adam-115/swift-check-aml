package com.swiftcheck.aml.plugins.bepep.euinstitutions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class BePepEuInstitutionsParserTest {

    private static final Path PAGES = Path.of("src/test/resources/pages");

    private BePepEuInstitutionsParser parser;

    @BeforeEach
    void setUp() {
        parser = new BePepEuInstitutionsParser();
    }

    @Test
    void convertsBelgianMembersFromOfficialSources() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepEuInstitutionsParser.ID_PREFIX,
                BePepEuInstitutionsParser.TOPIC);
        BePepEuInstitutionsParser.Report report = parser.convert(PAGES, dataset, Map.of());

        assertThat(report.belgian()).isNotEmpty();
        assertThat(dataset.entities()).isNotEmpty();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();

        List<String> names = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .flatMap(e -> e.getValues("name").stream())
                .toList();

        // Vérification de la présence de personnalités belges identifiées dans les instances de l'UE
        // Ex: Koen Lenaerts (Président de la Cour de justice), Annemie Turtelboom (Cour des comptes européenne)
        assertThat(names).contains("Koen Lenaerts", "Annemie TURTELBOOM");
    }

    @Test
    void readSourceRecordsReturnsBelgianMembers() throws Exception {
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_eu_institutions", null, PAGES, null, Map.of()));
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank();
        });
    }

    @Test
    void emptyRawDirectoryThrowsException(@TempDir Path emptyDir) {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepEuInstitutionsParser.ID_PREFIX,
                BePepEuInstitutionsParser.TOPIC);
        assertThatThrownBy(() -> parser.convert(emptyDir, dataset, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
