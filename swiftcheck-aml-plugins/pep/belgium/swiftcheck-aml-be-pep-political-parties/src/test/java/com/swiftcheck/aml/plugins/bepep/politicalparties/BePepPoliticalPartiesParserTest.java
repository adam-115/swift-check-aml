package com.swiftcheck.aml.plugins.bepep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.bepep.politicalparties.BePepPoliticalPartiesParser.Statement;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur la réponse réelle de Wikidata pour la Belgique (Q31).
 */
class BePepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");
    private static final LocalDate SINCE = LocalDate.of(2025, 1, 1);

    private final BePepPoliticalPartiesParser parser = new BePepPoliticalPartiesParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX,
                BePepPoliticalPartiesParser.TOPIC);
        parser.convert(RAW, SINCE, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String qid) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("wikidataId").contains(qid))
                .findFirst().orElseThrow(() -> new AssertionError("Personne " + qid + " absente"));
    }

    @Test
    void convertsRealWikidataResponse() throws Exception {
        FtmDatasetBuilder dataset = convert();
        BePepPoliticalPartiesParser.Report report = parser.convert(RAW, SINCE,
                new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX));

        assertThat(report.parties()).isGreaterThanOrEqualTo(12);
        assertThat(report.unnamed()).isZero();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .hasSize(report.persons())
                .allSatisfy(p -> {
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                    assertThat(p.getValues("country")).containsExactly("be");
                });
    }

    @Test
    void extractsMajorBelgianPartyLeaders() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // N-VA : Valerie Van Peel (actuelle, rang préféré) et Bart De Wever (ancien, terminé en février 2025)
        FtmEntity vanPeel = person(dataset, "Q17149643");
        assertThat(vanPeel.getValues("name")).containsExactly("Valerie Van Peel");
        assertThat(vanPeel.getValues("position")).containsExactly("Président ou présidente – Nieuw-Vlaamse Alliantie");

        FtmEntity deWever = person(dataset, "Q336599");
        assertThat(deWever.getValues("name")).containsExactly("Bart De Wever");
        assertThat(deWever.getValues("position"))
                .contains("Ancien : Président ou présidente – Nieuw-Vlaamse Alliantie");

        // MR : Georges-Louis Bouchez
        assertThat(person(dataset, "Q18433444").getValues("position"))
                .contains("Président ou présidente – Mouvement réformateur");
        // PS : Paul Magnette
        assertThat(person(dataset, "Q1341556").getValues("position"))
                .contains("Président ou présidente – Parti socialiste");
        // PTB-PVDA : Raoul Hedebouw
        assertThat(person(dataset, "Q16672153").getValues("position"))
                .contains("Président ou présidente – Parti du travail de Belgique");
        // Vlaams Belang : Tom Van Grieken
        assertThat(person(dataset, "Q17300271").getValues("position"))
                .contains("Président ou présidente – Vlaams Belang");
        // Vooruit : Conner Rousseau
        assertThat(person(dataset, "Q65249245").getValues("position"))
                .contains("Président ou présidente – Vooruit");
        // CD&V : Sammy Mahdi
        assertThat(person(dataset, "Q75807853").getValues("position"))
                .contains("Président ou présidente – Christen-Democratisch en Vlaams");
    }

    @Test
    void readSourceRecordsListsLeaders(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(WikidataPartiesDownloader.LEADERS_FILE), tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE));
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_political_parties", null, tmp, null, Map.of("monthsAfterEnd", "1200")));
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank().doesNotMatch("Q\\d+");
            assertThat(r.sourceRef()).startsWith("https://www.wikidata.org/wiki/Q");
        });
        assertThat(records).extracting(SourceRecord::name)
                .contains("Georges-Louis Bouchez", "Paul Magnette", "Raoul Hedebouw", "Tom Van Grieken", "Sammy Mahdi");
    }

    @Test
    void emptyResponseIsAnError(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE), "{\"results\":{\"bindings\":[]}}");
        assertThatThrownBy(() -> parser.convert(tmp, SINCE, new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queryOnlyAcceptsQids() {
        assertThat(WikidataPartiesDownloader.leadersQuery("Q31"))
                .contains("wdt:P17 wd:Q31", "\"fr,nl,de,mul,en\"", "wdt:P156|wdt:P1366", "DeprecatedRank");
        assertThatThrownBy(() -> WikidataPartiesDownloader.leadersQuery("Q31 } DELETE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectionRules() {
        LocalDate since = LocalDate.of(2025, 10, 29);
        Statement old = statement("s1", "chair", false, "2016-01-01", null, "Q1");
        Statement newer = statement("s2", "chair", false, "2020-01-01", null, "Q2");
        Statement recentlyEnded = statement("s4", "chair", false, "2018-01-01", "2026-03-01", "Q4");
        BePepPoliticalPartiesParser.Selection s = BePepPoliticalPartiesParser.select(
                List.of(old, newer, recentlyEnded), since);
        assertThat(s.current()).containsExactly(newer);
        assertThat(s.former()).containsExactly(recentlyEnded);
        assertThat(s.superseded()).isEqualTo(1);
    }

    private static Statement statement(String id, String role, boolean preferred, String start, String end, String person) {
        Set<String> births = new LinkedHashSet<>();
        return new Statement(id, "Q100", "Parti test", role, preferred, start, end, person, "Personne " + person,
                births, "");
    }
}

