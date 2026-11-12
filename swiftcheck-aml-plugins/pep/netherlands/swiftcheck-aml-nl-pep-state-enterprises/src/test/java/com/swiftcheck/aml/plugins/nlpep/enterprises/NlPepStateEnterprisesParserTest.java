package com.swiftcheck.aml.plugins.nlpep.enterprises;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/dutch-state-enterprises-wikidata.json");

    private final NlPepStateEnterprisesParser parser = new NlPepStateEnterprisesParser();

    @Test
    void convertsExecutives() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepStateEnterprisesParser.ID_PREFIX,
                NlPepStateEnterprisesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(5);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(count);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Wouter Koolmees"))
                .anyMatch(p -> p.getValues("name").contains("Pieter van Oord"))
                .anyMatch(p -> p.getValues("name").contains("Manon van Beek"))
                .anyMatch(p -> p.getValues("name").contains("Marjan Rintel"));
    }
}
