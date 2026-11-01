package com.swiftcheck.aml.plugins.frpep.wikidatapositions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugins.frpep.wikidatapositions.FrPepWikidataPositionsParser.Statement;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Réponse réelle de Wikidata du 30/10/2026 pour le chef d'état-major de la Marine (Q2961994). */
class FrPepWikidataPositionsParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");
    private static final LocalDate SINCE = LocalDate.of(2025, 10, 30);

    private final FrPepWikidataPositionsParser parser = new FrPepWikidataPositionsParser();

    private static List<String> positions(FtmDatasetBuilder dataset, String name) {
        FtmEntity p = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
        return p.getValues("position").stream().toList();
    }

    @Test
    void navyChiefOfStaff() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepWikidataPositionsParser.ID_PREFIX,
                FrPepWikidataPositionsParser.TOPIC);
        FrPepWikidataPositionsParser.Report report = parser.convert(RAW, SINCE, dataset);

        // Christophe Cluzel depuis le 15/07/2026 ; Nicolas Vaujour, sorti le même jour, reste PEP 12 mois
        assertThat(positions(dataset, "Christophe Cluzel")).containsExactly("Chef d'état-major de la Marine");
        assertThat(positions(dataset, "Nicolas Vaujour")).containsExactly("Ancien : Chef d'état-major de la Marine");
        // Jean-Louis Battet (2001-2005), enregistré sans dates : pas pris pour le titulaire actuel
        assertThat(dataset.entities()).noneMatch(e -> e.getValues("name").contains("Jean-Louis Battet"));
        assertThat(report.current()).hasSize(1);
        assertThat(report.superseded()).isEqualTo(1);
        assertThat(report.endedLongAgo()).isEqualTo(7);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
    }

    @Test
    void preferredRankWinsThenLatestStart() {
        Statement undated = statement("s1", false, null, null);
        Statement older = statement("s2", false, "2016-01-01", null);
        Statement newer = statement("s3", false, "2020-01-01", null);
        assertThat(FrPepWikidataPositionsParser.select(List.of(undated, older, newer), SINCE).current())
                .containsExactly(newer);
        Statement preferred = statement("s4", true, "2010-01-01", null);
        assertThat(FrPepWikidataPositionsParser.select(List.of(undated, older, newer, preferred), SINCE).current())
                .containsExactly(preferred);
    }

    @Test
    void queryOnlyAcceptsQids() {
        assertThat(FrPepWikidataPositionsParser.query(List.of("Q2961994"))).contains("VALUES ?pos { wd:Q2961994 }",
                "\"fr,mul,en\"");
        assertThatThrownBy(() -> FrPepWikidataPositionsParser.query(List.of("Q1 } DELETE")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noCurrentHolderIsAnError(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(FrPepWikidataPositionsParser.HOLDERS_FILE), "{\"results\":{\"bindings\":[]}}");
        assertThatThrownBy(() -> parser.convert(tmp, SINCE, new FtmDatasetBuilder(FrPepWikidataPositionsParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Statement statement(String id, boolean preferred, String start, String end) {
        return new Statement(id, "Q1", "fonction", preferred, start, end, "Q" + id.substring(1), "Personne " + id,
                Set.of(), "");
    }
}
