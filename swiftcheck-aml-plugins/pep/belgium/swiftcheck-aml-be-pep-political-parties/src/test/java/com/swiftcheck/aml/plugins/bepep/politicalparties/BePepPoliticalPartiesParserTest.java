package com.swiftcheck.aml.plugins.bepep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import com.swiftcheck.aml.plugins.bepep.politicalparties.BePepPoliticalPartiesParser.PartyConfig;
import com.swiftcheck.aml.plugins.bepep.politicalparties.BePepPoliticalPartiesParser.Statement;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les réponses réelles de Wikidata (Q31) et de l'API Regimand (Cour des comptes) pour les
 * partis politiques belges.
 */
class BePepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");
    private static final Path PARTIES_CONFIG = Path.of("src/test/resources/parties.json");
    private static final LocalDate SINCE = LocalDate.of(2025, 1, 1);

    private final BePepPoliticalPartiesParser parser = new BePepPoliticalPartiesParser();

    private static String defaultPartiesJson() throws IOException {
        return Files.readString(PARTIES_CONFIG, StandardCharsets.UTF_8);
    }

    private static List<PartyConfig> defaultPartyConfigs() throws IOException {
        return BePepPoliticalPartiesParser.loadPartyConfigs(defaultPartiesJson());
    }

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX,
                BePepPoliticalPartiesParser.TOPIC);
        parser.convert(RAW, SINCE, dataset, defaultPartyConfigs());
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String qid) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("wikidataId").contains(qid))
                .findFirst().orElseThrow(() -> new AssertionError("Personne " + qid + " absente"));
    }

    @Test
    void convertsRealWikidataAndRegimandResponses() throws Exception {
        FtmDatasetBuilder dataset = convert();
        BePepPoliticalPartiesParser.Report report = parser.convert(RAW, SINCE,
                new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX, BePepPoliticalPartiesParser.TOPIC),
                defaultPartyConfigs());

        assertThat(report.parties()).isGreaterThanOrEqualTo(12);
        assertThat(report.persons()).isGreaterThanOrEqualTo(150);
        assertThat(report.regimandMandates()).isGreaterThanOrEqualTo(150);
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
    void extractsMajorBelgianPartyLeadersAndExecutives() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // N-VA : Valerie Van Peel (actuelle, rang préféré) et Bart De Wever (ancien, terminé en février 2025)
        FtmEntity vanPeel = person(dataset, "Q17149643");
        assertThat(vanPeel.getValues("name")).containsExactly("Valerie Van Peel");
        assertThat(vanPeel.getValues("position")).contains("Président ou présidente – Nieuw-Vlaamse Alliantie");

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

        // Organes dirigeants extraits des déclarations officielles Regimand (Cour des comptes)
        List<String> allNames = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next())
                .toList();
        assertThat(allNames).contains(
                "Valentine Delwart", "Mathieu Bihet", "André Flahaut", "Theo Francken", "Zuhal Demir",
                "Marie Lecocq", "Samuel Cogolati", "Yvan Verougstraete", "Gauthier de Sauvage Vercour",
                "Jeremie Vaneeckhout", "Nadia Naji", "Melissa Depraetere", "Robrecht Bothuyne",
                "Tom Ongena", "Vincent Van Quickenborne", "Peter Mertens", "Barbara Pas",
                "Chris Janssens", "Sophie Rohonyi", "François-Julien De Smet", "Marc Loewenstein");
    }

    @Test
    void filtersLocalAndNonGovernancePartyRoles() {
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Membre du bureau politique")).isTrue();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Secrétaire générale")).isTrue();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Lid van het partijbestuur")).isTrue();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Lid van de partijraad")).isTrue();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Ondervoorzitter")).isTrue();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Voorzitter van een lokaal bestuur")).isFalse();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Lid van het partijbestuur N-VA Deurne")).isFalse();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Membre du bureau exécutif des ainés Engagés")).isFalse();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Collaborateur politique")).isFalse();
        assertThat(BePepPoliticalPartiesParser.isPartyGovernanceRole("Attachée parlementaire")).isFalse();
    }

    @Test
    void loadsConfigStrictlyFromParameters() throws Exception {
        assertThatThrownBy(() -> BePepPoliticalPartiesParser.loadPartyConfigs(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parties");
        assertThat(defaultPartyConfigs()).hasSize(13);
    }

    @Test
    void readSourceRecordsListsLeaders(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(WikidataPartiesDownloader.LEADERS_FILE), tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE));
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_political_parties", null, tmp, null,
                        Map.of("monthsAfterEnd", "1200", "parties", defaultPartiesJson())));
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank().doesNotMatch("Q\\d+");
            assertThat(r.sourceRef()).matches("https://(www\\.wikidata\\.org/wiki/Q|public\\.regimand\\.be).*");
        });
        assertThat(records).extracting(SourceRecord::name)
                .contains("Georges-Louis Bouchez", "Paul Magnette", "Raoul Hedebouw", "Tom Van Grieken", "Sammy Mahdi");
    }

    @Test
    void emptyResponseIsAnError(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(WikidataPartiesDownloader.LEADERS_FILE), "{\"results\":{\"bindings\":[]}}");
        assertThatThrownBy(() -> parser.convert(tmp, SINCE,
                new FtmDatasetBuilder(BePepPoliticalPartiesParser.ID_PREFIX), defaultPartyConfigs()))
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
