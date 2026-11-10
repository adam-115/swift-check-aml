package com.swiftcheck.aml.plugins.ptpep.rca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepRcaParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-rca.json");

    private final PtPepRcaParser parser = new PtPepRcaParser();

    @Test
    void convertsAssociates() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepRcaParser.ID_PREFIX,
                PtPepRcaParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(10);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        // 10 associates + their target PEP persons
        assertThat(persons).isGreaterThanOrEqualTo(10);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Nuno Rebelo de Sousa"))
                .anyMatch(p -> p.getValues("name").contains("Carla Montenegro"))
                .anyMatch(p -> p.getValues("name").contains("Diogo Lacerda Machado"));
    }
}
