package com.swiftcheck.aml.plugins.ptpep.autonomousregions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepAutonomousRegionsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-autonomous-regions.json");

    private final PtPepAutonomousRegionsParser parser = new PtPepAutonomousRegionsParser();

    @Test
    void convertsRegions() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepAutonomousRegionsParser.ID_PREFIX,
                PtPepAutonomousRegionsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(14);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(14);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("José Manuel Bolieiro"))
                .anyMatch(p -> p.getValues("name").contains("Miguel Albuquerque"))
                .anyMatch(p -> p.getValues("name").contains("Pedro Catarino"));
    }
}
