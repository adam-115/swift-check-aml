package com.swiftcheck.aml.plugins.espep.diplomats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/spanish-ambassadors-wikipedia.json");

    private final EsPepDiplomatsParser parser = new EsPepDiplomatsParser();

    @Test
    void convertsAmbassadors() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepDiplomatsParser.ID_PREFIX,
                EsPepDiplomatsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(100);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(100);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
