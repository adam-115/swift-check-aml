package com.swiftcheck.aml.plugins.ptpep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/pt-diplomats-wikipedia.json");

    private final PtPepDiplomatsParser parser = new PtPepDiplomatsParser();

    @Test
    void convertsDiplomats() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepDiplomatsParser.ID_PREFIX,
                PtPepDiplomatsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(20);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(20);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Ana Paula Zacarias")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Isabel Brilhante Pedrosa") || n.contains("Vítor Sereno") || n.contains("António Gamito")));
    }
}
