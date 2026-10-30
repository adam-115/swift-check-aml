package com.swiftcheck.aml.plugins.frpep.politicalparties;

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
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.plugins.frpep.politicalparties.FrPepPoliticalPartiesParser.Statement;

/**
 * Tests sans réseau sur la réponse réelle de Wikidata du 29/10/2026 (124 partis, 198 déclarations).
 */
class FrPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");
    /** Date de la réponse moins 12 mois. */
    private static final LocalDate SINCE = LocalDate.of(2025, 10, 29);

    private final FrPepPoliticalPartiesParser parser = new FrPepPoliticalPartiesParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepPoliticalPartiesParser.ID_PREFIX,
                FrPepPoliticalPartiesParser.TOPIC);
        parser.convert(RAW, SINCE, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String qid) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("wikidataId").contains(qid))
                .findFirst().orElseThrow(() -> new AssertionError("Personne " + qid + " absente"));
    }

    private static boolean hasPerson(FtmDatasetBuilder dataset, String qid) {
        return dataset.entities().stream()
                .anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("wikidataId").contains(qid));
    }

    @Test
    void convertsRealWikidataResponse() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FrPepPoliticalPartiesParser.Report report = parser.convert(RAW, SINCE,
                new FtmDatasetBuilder(FrPepPoliticalPartiesParser.ID_PREFIX));

        assertThat(report.parties()).isGreaterThan(100);
        assertThat(report.unnamed()).as("libellés « mul » demandés").isZero();
        assertThat(report.superseded()).isPositive();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .hasSize(report.persons())
                .allSatisfy(p -> assertThat(p.getValues("topics")).containsExactly("role.pep"));
    }

    @Test
    void preferredRankGivesCurrentLeader() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Renaissance : Gabriel Attal au rang préféré ; Castaner, Guerini, Séjourné sans date de fin sont écartés
        FtmEntity attal = person(dataset, "Q30339350");
        assertThat(attal.getValues("position")).containsExactly("Président ou présidente – Renaissance");
        assertThat(attal.getValues("political")).containsExactly("Renaissance");
        assertThat(hasPerson(dataset, "Q1726930")).as("Christophe Castaner").isFalse();
        assertThat(hasPerson(dataset, "Q30343112")).as("Stanislas Guerini").isFalse();
        // Rassemblement national : Jordan Bardella, nom en « mul » seulement
        FtmEntity bardella = person(dataset, "Q40655898");
        assertThat(bardella.getValues("name")).containsExactly("Jordan Bardella");
        assertThat(bardella.getValues("gender")).containsExactly("male");
        // Les Républicains : Bruno Retailleau préféré, Christian Jacob (2019, sans fin) écarté
        assertThat(person(dataset, "Q2926942").getValues("position"))
                .contains("Président ou présidente – Les Républicains");
        assertThat(hasPerson(dataset, "Q707984")).as("Christian Jacob").isFalse();
        // Les Écologistes : Marine Tondelier préférée, David Cormand écarté
        assertThat(hasPerson(dataset, "Q23058507")).as("David Cormand").isFalse();
    }

    @Test
    void otherRolesAndParties() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // La France insoumise : Manuel Bompard, président et secrétaire général (deux fonctions, une personne)
        assertThat(person(dataset, "Q29575749").getValues("position")).contains(
                "Président ou présidente – La France insoumise",
                "Secrétaire général ou générale – La France insoumise");
        // Parti communiste français : Fabien Roussel, « directeur ou dirigeant » (P1037)
        assertThat(person(dataset, "Q30388733").getValues("position"))
                .contains("Dirigeant ou dirigeante – Parti communiste français");
        // Parti nationaliste basque (Espagne et France) et ancien Front national (suivi du RN) : hors périmètre
        assertThat(dataset.entities()).noneMatch(e -> e.getValues("wikidataId").contains("Q43093"));
        assertThat(dataset.entities()).noneMatch(e -> e.getValues("wikidataId").contains("Q107877569"));
        // Jean-Marie Le Pen, décédé, n'est pas repris
        assertThat(hasPerson(dataset, "Q7177")).isFalse();
    }

    @Test
    void positionAndOccupancy() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity bardella = person(dataset, "Q40655898");
        FtmEntity occupancy = dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(bardella.getId()))
                .findFirst().orElseThrow();
        assertThat(occupancy.getValues("startDate")).containsExactly("2022-11-05");
        assertThat(occupancy.getValues("status")).containsExactly("current");
        FtmEntity position = dataset.entities().stream()
                .filter(e -> e.getId().equals(occupancy.getValues("post").iterator().next()))
                .findFirst().orElseThrow();
        assertThat(position.getValues("name")).containsExactly("Président ou présidente – Rassemblement national");
        assertThat(position.getValues("topics")).containsExactly("gov.national");
    }

    @Test
    void selectionRules() {
        LocalDate since = LocalDate.of(2025, 10, 29);
        Statement old = statement("s1", "chair", false, "2016-01-01", null, "Q1");
        Statement newer = statement("s2", "chair", false, "2020-01-01", null, "Q2");
        Statement undated = statement("s3", "chair", false, null, null, "Q3");
        Statement recentlyEnded = statement("s4", "chair", false, "2018-01-01", "2026-03-01", "Q4");
        Statement endedLongAgo = statement("s5", "chair", false, "2010-01-01", "2015-01-01", "Q5");
        Statement secretary = statement("s6", "secretaryGeneral", false, "2010-01-01", null, "Q6");

        FrPepPoliticalPartiesParser.Selection s = FrPepPoliticalPartiesParser.select(
                List.of(old, newer, undated, recentlyEnded, endedLongAgo, secretary), since);
        assertThat(s.current()).containsExactly(newer, undated, secretary);
        assertThat(s.former()).containsExactly(recentlyEnded);
        assertThat(s.superseded()).isEqualTo(1);
        assertThat(s.endedLongAgo()).isEqualTo(1);

        // Avec un rang préféré, les autres mandats sans fin sont remplacés, même plus récents
        Statement preferred = statement("s7", "chair", true, "2014-01-01", null, "Q7");
        s = FrPepPoliticalPartiesParser.select(List.of(old, newer, undated, preferred), since);
        assertThat(s.current()).containsExactly(preferred);
        assertThat(s.superseded()).isEqualTo(3);
    }

    @Test
    void readSourceRecordsListsLeaders(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(WikidataPartiesDownloader.LEADERS_FILE), tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE));
        // Durée large : le résultat ne dépend pas de la date du jour
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("fr_pep_political_parties", null, tmp, null, Map.of("monthsAfterEnd", "1200")));
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank().doesNotMatch("Q\\d+");
            assertThat(r.sourceRef()).startsWith("https://www.wikidata.org/wiki/Q");
        });
        assertThat(records).extracting(SourceRecord::name).contains("Jordan Bardella", "Gabriel Attal");
    }

    @Test
    void emptyResponseIsAnError(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE), "{\"results\":{\"bindings\":[]}}");
        assertThatThrownBy(() -> parser.convert(tmp, SINCE, new FtmDatasetBuilder(FrPepPoliticalPartiesParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queryOnlyAcceptsQids() {
        assertThat(WikidataPartiesDownloader.leadersQuery("Q142"))
                .contains("wdt:P17 wd:Q142", "\"fr,mul,en\"", "wdt:P156|wdt:P1366", "DeprecatedRank");
        assertThatThrownBy(() -> WikidataPartiesDownloader.leadersQuery("Q142 } DELETE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Statement statement(String id, String role, boolean preferred, String start, String end, String person) {
        Set<String> births = new LinkedHashSet<>();
        return new Statement(id, "Q100", "Parti test", role, preferred, start, end, person, "Personne " + person,
                births, "");
    }
}
