package com.swiftcheck.aml.plugins.espep.army;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepArmyParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/spanish-military-leaders.json");

    private final EsPepArmyParser parser = new EsPepArmyParser();

    @Test
    void convertsCommanders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepArmyParser.ID_PREFIX,
                EsPepArmyParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(12);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(12);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}

