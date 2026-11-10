package com.swiftcheck.aml.plugins.ptpep.parties;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-political-parties.json");

    private final PtPepPoliticalPartiesParser parser = new PtPepPoliticalPartiesParser();

    @Test
    void convertsPartyLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepPoliticalPartiesParser.ID_PREFIX,
                PtPepPoliticalPartiesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(13);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(13);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Luís Montenegro"))
                .anyMatch(p -> p.getValues("name").contains("Pedro Nuno Santos"))
                .anyMatch(p -> p.getValues("name").contains("André Ventura"));
    }
}
