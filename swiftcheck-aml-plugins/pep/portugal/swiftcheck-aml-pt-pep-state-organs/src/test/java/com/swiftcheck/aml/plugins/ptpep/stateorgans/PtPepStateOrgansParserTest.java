package com.swiftcheck.aml.plugins.ptpep.stateorgans;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepStateOrgansParserTest {

    private static final Path TEST_HTML = Path.of("src/test/resources/conselho-estado.html");

    private final PtPepStateOrgansParser parser = new PtPepStateOrgansParser();

    @Test
    void convertsStateOrgans() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepStateOrgansParser.ID_PREFIX,
                PtPepStateOrgansParser.TOPIC
        );
        int count = parser.convert(TEST_HTML, dataset);

        assertThat(count).isEqualTo(19);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(19);

        // Verify key officials from live official source
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Luísa Neto"))
                .anyMatch(p -> p.getValues("name").contains("José Pedro Aguiar-Branco"))
                .anyMatch(p -> p.getValues("name").contains("Luís Montenegro"))
                .anyMatch(p -> p.getValues("name").contains("Marcelo Rebelo de Sousa"))
                .anyMatch(p -> p.getValues("name").contains("Leonor Beleza"))
                .anyMatch(p -> p.getValues("name").contains("André Ventura"));
    }
}
