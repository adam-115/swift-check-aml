package com.swiftcheck.aml.plugins.ptpep.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepBankOfPortugalParserTest {

    private static final Path RAW = Path.of("src/test/resources/banco-de-portugal-wikipedia.json");

    private final PtPepBankOfPortugalParser parser = new PtPepBankOfPortugalParser();

    @Test
    void convertsBankMembers() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepBankOfPortugalParser.ID_PREFIX,
                PtPepBankOfPortugalParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(10);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(10);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Centeno")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Carlos da Silva Costa") || n.contains("Costa")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Santos Pereira")));
    }
}
