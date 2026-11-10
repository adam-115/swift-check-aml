package com.swiftcheck.aml.plugins.ptpep.enterprises;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-state-enterprises.json");

    private final PtPepStateEnterprisesParser parser = new PtPepStateEnterprisesParser();

    @Test
    void convertsExecutives() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepStateEnterprisesParser.ID_PREFIX,
                PtPepStateEnterprisesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(12);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(12);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Paulo Macedo"))
                .anyMatch(p -> p.getValues("name").contains("Luís Rodrigues"))
                .anyMatch(p -> p.getValues("name").contains("Pedro Moreira"));
    }
}
