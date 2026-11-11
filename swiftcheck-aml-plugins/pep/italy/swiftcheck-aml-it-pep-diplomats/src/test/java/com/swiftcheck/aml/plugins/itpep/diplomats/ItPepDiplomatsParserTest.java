package com.swiftcheck.aml.plugins.itpep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class ItPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/italian-ambassadors-wikipedia.json");

    private final ItPepDiplomatsParser parser = new ItPepDiplomatsParser();

    @Test
    void convertsAmbassadors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                ItPepDiplomatsParser.ID_PREFIX,
                ItPepDiplomatsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(100);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(100);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("it");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
