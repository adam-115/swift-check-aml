package com.swiftcheck.aml.plugins.ptpep.enterprises;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/pt-state-enterprises-wikipedia.json");

    private final PtPepStateEnterprisesParser parser = new PtPepStateEnterprisesParser();

    @Test
    void convertsExecutives() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepStateEnterprisesParser.ID_PREFIX,
                PtPepStateEnterprisesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(10);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(10);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Paulo Macedo"))
                .anyMatch(p -> p.getValues("name").contains("Luís Rodrigues"))
                .anyMatch(p -> p.getValues("name").contains("Pedro Moreira"))
                .anyMatch(p -> p.getValues("name").contains("Nicolau Santos"));
    }
}
