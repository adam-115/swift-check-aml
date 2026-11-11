package com.swiftcheck.aml.plugins.itpep.constitutionalcourt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class ItPepConstitutionalCourtParserTest {

    private static final Path RAW = Path.of("src/test/resources/constitutional-judges-wikidata.json");

    private final ItPepConstitutionalCourtParser parser = new ItPepConstitutionalCourtParser();

    @Test
    void convertsJudges() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                ItPepConstitutionalCourtParser.ID_PREFIX,
                ItPepConstitutionalCourtParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(15);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(15);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("it");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
