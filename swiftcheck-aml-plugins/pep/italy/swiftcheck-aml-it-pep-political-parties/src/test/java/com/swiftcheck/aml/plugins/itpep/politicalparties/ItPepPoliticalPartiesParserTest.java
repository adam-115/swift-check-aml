package com.swiftcheck.aml.plugins.itpep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Tests sans réseau sur la réponse réelle de Wikidata pour l'Italie (Q38).
 */
class ItPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");
    private static final LocalDate SINCE = LocalDate.of(2025, 1, 1);

    private final ItPepPoliticalPartiesParser parser = new ItPepPoliticalPartiesParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ItPepPoliticalPartiesParser.ID_PREFIX,
                ItPepPoliticalPartiesParser.TOPIC);
        parser.convert(RAW, SINCE, dataset);
        return dataset;
    }

    @Test
    void convertsRealWikidataResponse() throws Exception {
        FtmDatasetBuilder dataset = convert();
        ItPepPoliticalPartiesParser.Report report = parser.convert(RAW, SINCE,
                new FtmDatasetBuilder(ItPepPoliticalPartiesParser.ID_PREFIX));

        assertThat(report.parties()).isGreaterThanOrEqualTo(5);
        assertThat(report.persons()).isGreaterThanOrEqualTo(10);
        assertThat(report.unnamed()).isZero();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .hasSize(report.persons())
                .allSatisfy(p -> {
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                    assertThat(p.getValues("country")).containsExactly("it");
                });
    }
}

