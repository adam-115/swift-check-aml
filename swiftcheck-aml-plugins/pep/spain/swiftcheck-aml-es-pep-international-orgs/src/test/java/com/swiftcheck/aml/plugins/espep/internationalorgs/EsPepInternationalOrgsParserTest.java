package com.swiftcheck.aml.plugins.espep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/international-orgs.json");

    private final EsPepInternationalOrgsParser parser = new EsPepInternationalOrgsParser();

    @Test
    void convertsInternationalOrgs() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepInternationalOrgsParser.ID_PREFIX,
                EsPepInternationalOrgsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(11);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(11);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}

