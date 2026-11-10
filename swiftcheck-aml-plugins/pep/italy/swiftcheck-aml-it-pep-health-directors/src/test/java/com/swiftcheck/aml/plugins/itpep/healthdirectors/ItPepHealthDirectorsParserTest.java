package com.swiftcheck.aml.plugins.itpep.healthdirectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class ItPepHealthDirectorsParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/health-directors.json");

    private final ItPepHealthDirectorsParser parser = new ItPepHealthDirectorsParser();

    @Test
    void convertsHealthDirectors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                ItPepHealthDirectorsParser.ID_PREFIX,
                ItPepHealthDirectorsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(30);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(30);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("it");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
