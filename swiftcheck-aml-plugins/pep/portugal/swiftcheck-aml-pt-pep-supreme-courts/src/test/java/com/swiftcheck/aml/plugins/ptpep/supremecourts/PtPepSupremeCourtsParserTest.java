package com.swiftcheck.aml.plugins.ptpep.supremecourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepSupremeCourtsParserTest {

    private static final Path TEST_HTML = Path.of("src/test/resources/stj-estrutura.html");

    private final PtPepSupremeCourtsParser parser = new PtPepSupremeCourtsParser();

    @Test
    void convertsJudges() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepSupremeCourtsParser.ID_PREFIX,
                PtPepSupremeCourtsParser.TOPIC
        );
        int count = parser.convert(TEST_HTML, dataset);

        assertThat(count).isGreaterThan(50);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(count);

        // Verify key leadership from live STJ portal
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("João Cura Mariano"))
                .anyMatch(p -> p.getValues("name").contains("Nuno António Gonçalves"))
                .anyMatch(p -> p.getValues("name").contains("Graça Amaral"));
    }
}
