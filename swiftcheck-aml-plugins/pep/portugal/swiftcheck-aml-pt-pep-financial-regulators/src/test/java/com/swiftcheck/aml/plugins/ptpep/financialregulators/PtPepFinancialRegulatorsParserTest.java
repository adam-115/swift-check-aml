package com.swiftcheck.aml.plugins.ptpep.financialregulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepFinancialRegulatorsParserTest {

    private static final Path RAW = Path.of("src/test/resources/pt-financial-regulators-wikipedia.json");

    private final PtPepFinancialRegulatorsParser parser = new PtPepFinancialRegulatorsParser();

    @Test
    void convertsRegulators() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepFinancialRegulatorsParser.ID_PREFIX,
                PtPepFinancialRegulatorsParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isGreaterThanOrEqualTo(4);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isGreaterThanOrEqualTo(4);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Luís Laginha de Sousa")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Margarida Corrêa de Aguiar")))
                .anyMatch(p -> p.getValues("name").stream().anyMatch(n -> n.contains("Paula Meira Lourenço")));
    }
}
