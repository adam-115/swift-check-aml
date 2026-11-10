package com.swiftcheck.aml.plugins.ptpep.mayors;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepMayorsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-mayors.json");

    private final PtPepMayorsParser parser = new PtPepMayorsParser();

    @Test
    void convertsMayors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepMayorsParser.ID_PREFIX,
                PtPepMayorsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(20);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(20);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Carlos Moedas"))
                .anyMatch(p -> p.getValues("name").contains("Rui Moreira"))
                .anyMatch(p -> p.getValues("name").contains("Basílio Horta"));
    }
}
