package com.swiftcheck.aml.plugins.ptpep.prosecution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PtPepProsecutionParserTest {

    private static final Path TEST_HTML = Path.of("src/test/resources/csmp-composicao.html");

    private final PtPepProsecutionParser parser = new PtPepProsecutionParser();

    @Test
    void convertsProsecution() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PtPepProsecutionParser.ID_PREFIX,
                PtPepProsecutionParser.TOPIC
        );
        int count = parser.convert(TEST_HTML, dataset);

        assertThat(count).isEqualTo(19);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(19);

        // Verify current Procurador-Geral da República from live source
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("Amadeu Francisco Ribeiro Guerra"))
                .anyMatch(p -> p.getValues("name").contains("Helena de Jesus Fernandes Gonçalves"))
                .anyMatch(p -> p.getValues("name").contains("José Norberto Ferreira Martins"))
                .anyMatch(p -> p.getValues("name").contains("Fernando Jorge de Loureiro de Roboredo Seara"));
    }
}
