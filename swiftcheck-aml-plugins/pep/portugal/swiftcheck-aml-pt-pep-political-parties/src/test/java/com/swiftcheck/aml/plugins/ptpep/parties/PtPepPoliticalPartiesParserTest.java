package com.swiftcheck.aml.plugins.ptpep.parties;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/pt-political-parties-sparql.json");

    private final PtPepPoliticalPartiesParser parser = new PtPepPoliticalPartiesParser();

    @Test
    void convertsPartyLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepPoliticalPartiesParser.ID_PREFIX,
                PtPepPoliticalPartiesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(10);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(10);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Montenegro")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Pedro Nuno Santos")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Ventura")));
    }
}
