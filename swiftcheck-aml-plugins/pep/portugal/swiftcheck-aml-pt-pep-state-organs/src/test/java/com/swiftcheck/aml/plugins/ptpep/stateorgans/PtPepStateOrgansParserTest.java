package com.swiftcheck.aml.plugins.ptpep.stateorgans;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepStateOrgansParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-state-organs.json");

    private final PtPepStateOrgansParser parser = new PtPepStateOrgansParser();

    @Test
    void convertsStateOrgans() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepStateOrgansParser.ID_PREFIX,
                PtPepStateOrgansParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(11);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(11);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Maria Lúcia Amaral"))
                .anyMatch(p -> p.getValues("name").contains("Luís Pais Antunes"));
    }
}
