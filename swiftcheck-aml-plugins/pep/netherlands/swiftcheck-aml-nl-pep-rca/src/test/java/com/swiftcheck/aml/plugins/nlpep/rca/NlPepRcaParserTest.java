package com.swiftcheck.aml.plugins.nlpep.rca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/royal-family-wikidata.json");

    private final NlPepRcaParser parser = new NlPepRcaParser();

    @Test
    void convertsAssociates() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepRcaParser.ID_PREFIX,
                NlPepRcaParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(6);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(count);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Máxima")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Beatrix")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Amalia")));
    }
}
