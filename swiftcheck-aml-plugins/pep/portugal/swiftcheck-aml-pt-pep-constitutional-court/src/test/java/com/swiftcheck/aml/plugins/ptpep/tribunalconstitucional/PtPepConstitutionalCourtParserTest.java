package com.swiftcheck.aml.plugins.ptpep.tribunalconstitucional;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepConstitutionalCourtParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pt-constitutional-court.json");

    private final PtPepConstitutionalCourtParser parser = new PtPepConstitutionalCourtParser();

    @Test
    void convertsJudges() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepConstitutionalCourtParser.ID_PREFIX,
                PtPepConstitutionalCourtParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(13);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(13);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("José João Abrantes"))
                .anyMatch(p -> p.getValues("name").contains("Gonçalo de Almeida Ribeiro"));
    }
}
