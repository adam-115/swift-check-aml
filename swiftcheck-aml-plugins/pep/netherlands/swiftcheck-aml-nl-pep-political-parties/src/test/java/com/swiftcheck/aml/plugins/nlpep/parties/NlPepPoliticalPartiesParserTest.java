package com.swiftcheck.aml.plugins.nlpep.parties;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/nl-political-parties.json");

    private final NlPepPoliticalPartiesParser parser = new NlPepPoliticalPartiesParser();

    @Test
    void convertsPartyLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepPoliticalPartiesParser.ID_PREFIX,
                NlPepPoliticalPartiesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(15);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(15);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Geert Wilders"))
                .anyMatch(p -> p.getValues("name").contains("Frans Timmermans"))
                .anyMatch(p -> p.getValues("name").contains("Dilan Yeşilgöz-Zegerius"));
    }
}

