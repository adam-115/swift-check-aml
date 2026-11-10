package com.swiftcheck.aml.plugins.ptpep.tribunalconstitucional;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepConstitutionalCourtParserTest {

    private static final Path TEST_HTML = Path.of("src/test/resources/tc-plenario.html");

    private final PtPepConstitutionalCourtParser parser = new PtPepConstitutionalCourtParser();

    @Test
    void convertsJudges() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepConstitutionalCourtParser.ID_PREFIX,
                PtPepConstitutionalCourtParser.TOPIC
        );
        int count = parser.convert(TEST_HTML, dataset);

        assertThat(count).isEqualTo(13);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(13);

        // Verify current President and Vice-President from live source
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("João Carlos Simões Gonçalves Loureiro"))
                .anyMatch(p -> p.getValues("name").contains("Rui Rodrigo Firmino Guerra da Fonseca"))
                .anyMatch(p -> p.getValues("name").contains("Afonso Nunes de Figueiredo Patrão"));
    }
}
