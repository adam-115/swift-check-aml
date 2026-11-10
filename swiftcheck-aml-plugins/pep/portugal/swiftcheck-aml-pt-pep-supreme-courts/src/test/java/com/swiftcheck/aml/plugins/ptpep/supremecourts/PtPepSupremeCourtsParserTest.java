package com.swiftcheck.aml.plugins.ptpep.supremecourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepSupremeCourtsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-supreme-courts.json");

    private final PtPepSupremeCourtsParser parser = new PtPepSupremeCourtsParser();

    @Test
    void convertsJudges() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepSupremeCourtsParser.ID_PREFIX,
                PtPepSupremeCourtsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(12);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(12);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("João Cura Mariano"))
                .anyMatch(p -> p.getValues("name").contains("Dulce Neto"));
    }
}
