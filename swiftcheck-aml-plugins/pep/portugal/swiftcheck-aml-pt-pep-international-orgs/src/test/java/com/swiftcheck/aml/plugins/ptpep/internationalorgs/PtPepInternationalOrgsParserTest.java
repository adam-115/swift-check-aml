package com.swiftcheck.aml.plugins.ptpep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-international-orgs.json");

    private final PtPepInternationalOrgsParser parser = new PtPepInternationalOrgsParser();

    @Test
    void convertsLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepInternationalOrgsParser.ID_PREFIX,
                PtPepInternationalOrgsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(6);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(6);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Zacarias da Costa"))
                .anyMatch(p -> p.getValues("name").contains("Maja Markovčić Kostelac"))
                .anyMatch(p -> p.getValues("name").contains("Alexis Goosdeel"));
    }
}
