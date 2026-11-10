package com.swiftcheck.aml.plugins.ptpep.armedforces;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepArmedForcesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-armed-forces.json");

    private final PtPepArmedForcesParser parser = new PtPepArmedForcesParser();

    @Test
    void convertsArmedForces() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepArmedForcesParser.ID_PREFIX,
                PtPepArmedForcesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(8);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(8);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("José Nunes da Fonseca"))
                .anyMatch(p -> p.getValues("name").contains("Henrique Gouveia e Melo"))
                .anyMatch(p -> p.getValues("name").contains("Luís Carrilho"));
    }
}
