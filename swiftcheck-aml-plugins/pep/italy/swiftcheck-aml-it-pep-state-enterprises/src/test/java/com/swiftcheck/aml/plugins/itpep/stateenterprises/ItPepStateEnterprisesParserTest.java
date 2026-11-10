package com.swiftcheck.aml.plugins.itpep.stateenterprises;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class ItPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/state-enterprises-leaders.json");

    private final ItPepStateEnterprisesParser parser = new ItPepStateEnterprisesParser();

    @Test
    void convertsExecutives() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                ItPepStateEnterprisesParser.ID_PREFIX,
                ItPepStateEnterprisesParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(25);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(25);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("it");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
