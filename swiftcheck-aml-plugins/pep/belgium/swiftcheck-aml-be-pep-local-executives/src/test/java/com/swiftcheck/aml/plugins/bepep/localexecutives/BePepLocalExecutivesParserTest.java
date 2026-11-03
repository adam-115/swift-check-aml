package com.swiftcheck.aml.plugins.bepep.localexecutives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les réponses réelles de Wikidata et LBLOD pour les collectivités belges de 50 000 habitants
 * ou plus.
 */
class BePepLocalExecutivesParserTest {

    private static final Path RAW = Path.of("src/test/resources/local");
    private static final LocalDate SINCE = LocalDate.of(2024, 11, 1);

    private final BePepLocalExecutivesParser parser = new BePepLocalExecutivesParser();

    @Test
    void convertsWikidataAndLblodResponses() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepLocalExecutivesParser.ID_PREFIX,
                BePepLocalExecutivesParser.TOPIC);
        BePepLocalExecutivesParser.Report report = parser.convert(RAW, 50_000L, SINCE, dataset);

        assertThat(report.communes()).isGreaterThanOrEqualTo(30);
        assertThat(report.provinces()).isGreaterThanOrEqualTo(5);
        assertThat(report.mayors()).isGreaterThanOrEqualTo(25);
        assertThat(report.governors()).isPositive();
        assertThat(report.lblodAdded()).isPositive();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.toConversionResult().conflictCount()).as("ID conflicts").isZero();

        List<String> names = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next())
                .toList();
        // Wallonie, Bruxelles et Flandre (Wikidata + LBLOD)
        assertThat(names).contains(
                "Thomas Dermine",       // Charleroi
                "Maxime Prévot",        // Namur
                "Nicolas Martin",       // Mons
                "Willy Demeyer",        // Liège
                "Philippe Close",       // Bruxelles
                "Mathias De Clercq",    // Gand
                "Mohamed Ridouani",     // Louvain
                "Bart Somers",          // Malines
                "Ruth Vandenberghe",    // Courtrai (LBLOD)
                "John Crombez"          // Ostende (LBLOD)
        );
    }

    @Test
    void filtersByMinimumPopulation() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepLocalExecutivesParser.ID_PREFIX,
                BePepLocalExecutivesParser.TOPIC);
        BePepLocalExecutivesParser.Report report = parser.convert(RAW, 200_000L, SINCE, dataset);

        // À 200 000 habitants, seules les très grandes villes (Anvers, Gand, Charleroi) et les provinces restent
        assertThat(report.communes()).isLessThan(10);
        assertThat(dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())))
                .allSatisfy(pos -> assertThat(pos.getValues("topics")).contains("gov.executive"));
    }

    @Test
    void readSourceRecordsListsExecutives(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(BePepLocalExecutivesParser.WIKIDATA_FILE),
                tmp.resolve(BePepLocalExecutivesParser.WIKIDATA_FILE));
        Files.copy(RAW.resolve(BePepLocalExecutivesParser.LBLOD_FILE),
                tmp.resolve(BePepLocalExecutivesParser.LBLOD_FILE));
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_local_executives", null, tmp, null, Map.of("monthsAfterEnd", "120")));
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank();
        });
    }

    @Test
    void emptyResponseIsAnError(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(BePepLocalExecutivesParser.WIKIDATA_FILE), "{\"results\":{\"bindings\":[]}}");
        assertThatThrownBy(() -> parser.convert(tmp, 50_000L, SINCE,
                new FtmDatasetBuilder(BePepLocalExecutivesParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }
}

