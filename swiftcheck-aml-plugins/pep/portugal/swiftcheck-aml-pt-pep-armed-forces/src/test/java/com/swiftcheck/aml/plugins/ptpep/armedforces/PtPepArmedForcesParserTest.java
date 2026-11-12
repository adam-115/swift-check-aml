package com.swiftcheck.aml.plugins.ptpep.armedforces;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepArmedForcesParserTest {

    private static final Path RAW = Path.of("src/test/resources/emgfa-wikipedia.json");

    private final PtPepArmedForcesParser parser = new PtPepArmedForcesParser();

    @Test
    void convertsArmedForces() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepArmedForcesParser.ID_PREFIX,
                PtPepArmedForcesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(1);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(count);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("João Cartaxo Alves"));
    }
}
