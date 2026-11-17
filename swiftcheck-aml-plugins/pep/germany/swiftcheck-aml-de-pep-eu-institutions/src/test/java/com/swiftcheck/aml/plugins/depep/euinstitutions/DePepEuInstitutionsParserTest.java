package com.swiftcheck.aml.plugins.depep.euinstitutions;

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

class DePepEuInstitutionsParserTest {

    private static final Path PAGES = Path.of("src/test/resources/pages");

    private DePepEuInstitutionsParser parser;

    @BeforeEach
    void setUp() {
        parser = new DePepEuInstitutionsParser();
    }

    @Test
    void convertsGermanMembersFromOfficialSources() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepEuInstitutionsParser.ID_PREFIX,
                DePepEuInstitutionsParser.TOPIC);
        DePepEuInstitutionsParser.Report report = parser.convert(PAGES, dataset, Map.of("knownNationals",
                "[{\"name\":\"Isabel Schnabel\",\"evidence\":\"candidate du gouvernement allemand\"}]"));

        assertThat(report.german()).isNotEmpty();
        assertThat(dataset.entities()).isNotEmpty();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();

        List<String> names = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .flatMap(e -> e.getValues("name").stream())
                .toList();

        // pages officielles du 16/11/2026 : CJUE (naissance en Allemagne), Cour des comptes (pays indiqué), BCE
        assertThat(names).containsExactlyInAnyOrder("Thomas von Danwitz", "Juliane Kokott", "Johannes Christoph Laitenberger",
                "Gabriele Steinfatt", "Daniel CASPARY", "Isabel Schnabel", "Mark Branson", "Michael Theurer");
    }

    @Test
    void readSourceRecordsReturnsGermanMembers() throws Exception {
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("de_pep_eu_institutions", null, PAGES, null, Map.of()));
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank();
        });
    }

    @Test
    void emptyRawDirectoryThrowsException(@TempDir Path emptyDir) {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepEuInstitutionsParser.ID_PREFIX,
                DePepEuInstitutionsParser.TOPIC);
        assertThatThrownBy(() -> parser.convert(emptyDir, dataset, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
