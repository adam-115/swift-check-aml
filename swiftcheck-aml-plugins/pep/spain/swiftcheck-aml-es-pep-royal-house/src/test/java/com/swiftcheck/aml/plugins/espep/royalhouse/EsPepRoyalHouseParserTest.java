package com.swiftcheck.aml.plugins.espep.royalhouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepRoyalHouseParserTest {

    private static final Path RAW = Path.of("src/test/resources/royal-house-wikidata.json");

    private final EsPepRoyalHouseParser parser = new EsPepRoyalHouseParser();

    @Test
    void convertsRoyalHouse() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepRoyalHouseParser.ID_PREFIX,
                EsPepRoyalHouseParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(8);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(8);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
