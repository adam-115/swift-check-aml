package com.swiftcheck.aml.plugins.ptpep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-diplomats.json");

    private final PtPepDiplomatsParser parser = new PtPepDiplomatsParser();

    @Test
    void convertsDiplomats() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepDiplomatsParser.ID_PREFIX,
                PtPepDiplomatsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(14);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(14);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Pedro Lourtie"))
                .anyMatch(p -> p.getValues("name").contains("Ana Paula Zacarias"))
                .anyMatch(p -> p.getValues("name").contains("José Augusto Duarte"));
    }
}
