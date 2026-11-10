package com.swiftcheck.aml.plugins.nlpep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/nl-international-orgs.json");

    private final NlPepInternationalOrgsParser parser = new NlPepInternationalOrgsParser();

    @Test
    void convertsLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepInternationalOrgsParser.ID_PREFIX,
                NlPepInternationalOrgsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(8);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(8);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Nawaf Salam"))
                .anyMatch(p -> p.getValues("name").contains("Catherine De Bolle"))
                .anyMatch(p -> p.getValues("name").contains("Karim Khan"));
    }
}

