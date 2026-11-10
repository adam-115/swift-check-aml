package com.swiftcheck.aml.plugins.ptpep.prosecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepProsecutionParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-prosecution.json");

    private final PtPepProsecutionParser parser = new PtPepProsecutionParser();

    @Test
    void convertsProsecutors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepProsecutionParser.ID_PREFIX,
                PtPepProsecutionParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(10);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(10);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Amadeu Guerra"))
                .anyMatch(p -> p.getValues("name").contains("Lucília Gago"));
    }
}
