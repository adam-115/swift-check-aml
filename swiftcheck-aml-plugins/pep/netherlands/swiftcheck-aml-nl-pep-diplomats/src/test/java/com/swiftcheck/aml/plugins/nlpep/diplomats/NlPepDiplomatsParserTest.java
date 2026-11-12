package com.swiftcheck.aml.plugins.nlpep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/dutch-ambassadors-wikipedia.json");

    private final NlPepDiplomatsParser parser = new NlPepDiplomatsParser();

    @Test
    void convertsAmbassadors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepDiplomatsParser.ID_PREFIX,
                NlPepDiplomatsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(100);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(count);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Jan Versteeg"))
                .anyMatch(p -> p.getValues("name").contains("Reinout Vos"));
    }
}
