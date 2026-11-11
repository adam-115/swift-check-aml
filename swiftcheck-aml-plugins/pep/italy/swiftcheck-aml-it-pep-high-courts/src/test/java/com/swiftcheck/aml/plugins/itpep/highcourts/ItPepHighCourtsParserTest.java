package com.swiftcheck.aml.plugins.itpep.highcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class ItPepHighCourtsParserTest {

    private static final Path RAW = Path.of("src/test/resources/high-courts-magistrates-wikidata.json");

    private final ItPepHighCourtsParser parser = new ItPepHighCourtsParser();

    @Test
    void convertsMagistrates() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                ItPepHighCourtsParser.ID_PREFIX,
                ItPepHighCourtsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(6);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(6);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("it");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}
