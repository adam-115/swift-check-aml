package com.swiftcheck.aml.plugins.espep.unions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepUnionsParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/spanish-unions-leaders.json");

    private final EsPepUnionsParser parser = new EsPepUnionsParser();

    @Test
    void convertsUnionLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepUnionsParser.ID_PREFIX,
                EsPepUnionsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(9);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(9);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}

