package com.swiftcheck.aml.plugins.frpep.rca;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tests sans réseau sur un extrait des réponses réelles de Wikidata (28/10/2026), avec la liste autorisée française :
 * six PEP (sénateurs, députée, conseiller d'État) et leurs proches, dont un partenaire et le conjoint d'un enfant.
 */
class FrPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");

    private final FrPepRcaParser parser = new FrPepRcaParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepRcaParser.ID_PREFIX);
        parser.convert(RAW, dataset);
        return dataset;
    }

    private static FtmEntity byWikidataId(FtmDatasetBuilder dataset, String qid) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("wikidataId").contains(qid))
                .findFirst().orElseThrow(() -> new AssertionError("Personne " + qid + " absente"));
    }

    private static List<FtmEntity> families(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Family".equals(e.getSchema())).toList();
    }

    @Test
    void convertsRealWikidataResponse() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FrPepRcaParser.Report report = parser.convert(RAW, new FtmDatasetBuilder(FrPepRcaParser.ID_PREFIX));

        assertThat(report.peps()).isEqualTo(6);
        assertThat(report.pepsWithFamily()).isEqualTo(5).as("François-Noël Buffet n'a pas de proche connu");
        assertThat(families(dataset)).hasSize(report.links()).hasSize(10);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities()).noneMatch(e -> e.getValues("wikidataId").contains("Q12944"));
    }

    @Test
    void frenchFamilyLinks() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity portelli = byWikidataId(dataset, "Q3142662");
        FtmEntity lambron = byWikidataId(dataset, "Q1237205");

        // Conjoint d'un enfant (R. 561-18, II) : le conjoint de Florence Portelli
        FtmEntity mosalini = byWikidataId(dataset, "Q52832803");
        assertThat(mosalini.getValues("topics")).containsExactly("role.rca");
        assertThat(mosalini.getValues("summary")).contains("Conjoint d'un enfant de Hugues Portelli");
        assertThat(mosalini.getValues("birthDate")).containsExactly("1972-04-29");
        // Partenaire (concubin, PACS)
        assertThat(families(dataset)).anySatisfy(f -> {
            assertThat(f.getValues("person")).contains(lambron.getId());
            assertThat(f.getValues("relationship")).contains("concubin ou partenaire");
        });
        assertThat(portelli.getValues("topics")).containsExactly("role.pep");
        assertThat(portelli.getValues("position")).contains("sénateur ou sénatrice de la Cinquième République");
        // Année seule (précision 9) : « 1958 », pas « 1958-01-01 »
        assertThat(byWikidataId(dataset, "Q116766773").getValues("birthDate")).containsExactly("1958");
    }

    @Test
    void readSourceRecordsListsOnlyRelatives(@TempDir Path tmp) throws Exception {
        ParserContext ctx = new ParserContext("fr_pep_rca", null, RAW, tmp.resolve("out.ndjson"), Map.of());
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(10).allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.sourceRef()).startsWith("https://www.wikidata.org/wiki/Q");
        });
    }

    @Test
    void allowListQueryAndNoSiblings() {
        WikidataRcaDownloader.Query q = new WikidataRcaDownloader.Query("Q142", LocalDate.of(2025, 10, 28),
                List.of(), List.of(), List.of("Q191954", "Q3044918"), List.of("Q83307"));
        String pepQuery = WikidataRcaDownloader.pepQuery(q);
        assertThat(pepQuery)
                .contains("VALUES ?pos { wd:Q191954 wd:Q3044918 }")
                .contains("?pos wdt:P1001 wd:Q142 . ?pos wdt:P279+ wd:Q83307")
                .contains("\"2025-10-28T00:00:00Z\"^^xsd:dateTime")
                .doesNotContain("{ ?pos wdt:P1001 wd:Q142 } UNION");
        // Les classes sont résolues à part (la requête combinée dépasse le délai de Wikidata)
        assertThat(WikidataRcaDownloader.classPositionsQuery("Q142", "Q83307"))
                .contains("{ SELECT ?pos WHERE { ?pos wdt:P279+ wd:Q83307 } }")
                .contains("?pos wdt:P1001 wd:Q142 .");
        assertThat(WikidataRcaDownloader.relativesQuery(List.of("Q12")))
                .contains("P451").contains("childSpouse")
                .doesNotContain("P3373");
    }

    @Test
    void withoutAllowListTheLuxembourgModeIsKept() {
        WikidataRcaDownloader.Query q = new WikidataRcaDownloader.Query("Q32", LocalDate.of(2021, 10, 26),
                List.of("Q29639349"), List.of("Q13102207"), List.of(), List.of());
        assertThat(WikidataRcaDownloader.pepQuery(q))
                .contains("{ ?pos wdt:P1001 wd:Q32 } UNION { VALUES ?pos { wd:Q29639349 } }")
                .contains("NOT IN (wd:Q13102207)");
    }
}
