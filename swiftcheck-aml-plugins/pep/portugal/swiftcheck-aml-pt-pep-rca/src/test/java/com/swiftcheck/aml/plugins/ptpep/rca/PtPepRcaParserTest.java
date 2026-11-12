package com.swiftcheck.aml.plugins.ptpep.rca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/pt-rca-wikipedia.json");

    private final PtPepRcaParser parser = new PtPepRcaParser();

    @Test
    void convertsAssociates() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepRcaParser.ID_PREFIX,
                PtPepRcaParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(5);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(7);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Nuno Rebelo de Sousa")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Carla Montenegro")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Diogo Lacerda Machado")));
    }
}
