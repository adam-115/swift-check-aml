package com.swiftcheck.aml.plugins.espep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/spanish-party-leaders-wikidata.json");

    private final EsPepPoliticalPartiesParser parser = new EsPepPoliticalPartiesParser();

    @Test
    void convertsPartyLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepPoliticalPartiesParser.ID_PREFIX,
                EsPepPoliticalPartiesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(15);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(15);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
