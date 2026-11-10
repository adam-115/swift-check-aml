package com.swiftcheck.aml.plugins.nlpep.centralbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/nl-central-bank.json");

    private final NlPepCentralBankParser parser = new NlPepCentralBankParser();

    @Test
    void convertsBoardMembers() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepCentralBankParser.ID_PREFIX,
                NlPepCentralBankParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(6);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(6);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Klaas Knot"))
                .anyMatch(p -> p.getValues("name").contains("Steven Maijoor"));
    }
}

