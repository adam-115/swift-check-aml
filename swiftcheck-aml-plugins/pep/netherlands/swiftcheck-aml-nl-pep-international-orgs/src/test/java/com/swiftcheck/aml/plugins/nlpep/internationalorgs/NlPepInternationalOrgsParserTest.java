package com.swiftcheck.aml.plugins.nlpep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class NlPepInternationalOrgsParserTest {

    private static final Path TEST_HTML = Path.of("src/test/resources/icj-members.html");

    private final NlPepInternationalOrgsParser parser = new NlPepInternationalOrgsParser();

    @Test
    void convertsLeaders() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                NlPepInternationalOrgsParser.ID_PREFIX,
                NlPepInternationalOrgsParser.TOPIC
        );
        int count = parser.convert(TEST_HTML, dataset);

        assertThat(count).isEqualTo(15);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(15);

        // Verify current President and Vice-President from live ICJ portal
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .anyMatch(p -> p.getValues("name").contains("IWASAWA Yuji"))
                .anyMatch(p -> p.getValues("name").contains("Julia SEBUTINDE"))
                .anyMatch(p -> p.getValues("name").contains("Peter TOMKA"))
                .anyMatch(p -> p.getValues("name").contains("Ronny ABRAHAM"));
    }
}
