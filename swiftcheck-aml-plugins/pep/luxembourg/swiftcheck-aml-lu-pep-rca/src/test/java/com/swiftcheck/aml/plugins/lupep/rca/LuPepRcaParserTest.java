package com.swiftcheck.aml.plugins.lupep.rca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les réponses réelles de Wikidata enregistrées le 26/10/2026
 * ({@code src/test/resources/wikidata/peps.json} et {@code relatives.json}).
 */
class LuPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/wikidata");

    private final LuPepRcaParser parser = new LuPepRcaParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepRcaParser.ID_PREFIX);
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
        LuPepRcaParser.Report report = parser.convert(RAW, new FtmDatasetBuilder(LuPepRcaParser.ID_PREFIX));

        assertThat(report.peps()).isEqualTo(393);
        assertThat(report.pepsWithFamily()).isGreaterThanOrEqualTo(25);
        assertThat(report.relatives()).isGreaterThanOrEqualTo(50);
        assertThat(families(dataset)).hasSizeGreaterThanOrEqualTo(65).hasSize(report.links());
        // Critères du contrôle qualité du core : aucune valeur rejetée, aucun conflit d'identifiant
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        // Chaque lien relie une PEP (role.pep) à un proche (role.rca) écrits dans le même jeu
        for (FtmEntity family : families(dataset)) {
            String personId = family.getValues("person").iterator().next();
            String relativeId = family.getValues("relative").iterator().next();
            FtmEntity person = dataset.entities().stream().filter(e -> e.getId().equals(personId)).findFirst().orElseThrow();
            FtmEntity relative = dataset.entities().stream().filter(e -> e.getId().equals(relativeId)).findFirst().orElseThrow();
            assertThat(person.getValues("topics")).contains("role.pep");
            assertThat(relative.getValues("topics")).contains("role.rca");
            assertThat(family.getValues("relationship")).isNotEmpty();
        }
    }

    @Test
    void spouseOfXavierBettel() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity bettel = byWikidataId(dataset, "Q561213");
        FtmEntity destenay = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Gauthier Destenay"))
                .findFirst().orElseThrow();

        assertThat(bettel.getValues("topics")).containsExactly("role.pep");
        assertThat(bettel.getValues("position")).isNotEmpty();
        assertThat(destenay.getValues("topics")).containsExactly("role.rca");
        assertThat(destenay.getValues("summary")).contains("Conjoint de Xavier Bettel");
        assertThat(families(dataset)).anySatisfy(f -> {
            assertThat(f.getValues("person")).contains(bettel.getId());
            assertThat(f.getValues("relative")).contains(destenay.getId());
            assertThat(f.getValues("relationship")).contains("conjoint");
        });
    }

    @Test
    void relativeWhoIsAlsoPepKeepsBothTopics() throws Exception {
        // Henri de Luxembourg : PEP (Grand-Duc jusqu'en 2025) et père de Guillaume, lui-même PEP
        FtmDatasetBuilder dataset = convert();
        long both = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .filter(e -> e.getValues("topics").containsAll(List.of("role.pep", "role.rca")))
                .count();
        assertThat(both).isGreaterThanOrEqualTo(2);
    }

    @Test
    void readSourceRecordsListsOnlyRelatives(@TempDir Path tmp) throws Exception {
        ParserContext ctx = new ParserContext("lu_pep_rca", null, RAW, tmp.resolve("out.ndjson"), Map.of());
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.sourceRef()).startsWith("https://www.wikidata.org/wiki/Q");
        });
    }

    @Test
    void birthDateKeepsWikidataPrecision() {
        assertThat(LuPepRcaParser.birthDate("1955-04-16T00:00:00Z", "11")).isEqualTo("1955-04-16");
        assertThat(LuPepRcaParser.birthDate("1926-01-01T00:00:00Z", "9")).isEqualTo("1926");
        assertThat(LuPepRcaParser.birthDate("1960-06-01T00:00:00Z", "10")).isEqualTo("1960-06");
        assertThat(LuPepRcaParser.birthDate("1900-01-01T00:00:00Z", "8")).isNull();   // décennie : trop vague
        assertThat(LuPepRcaParser.birthDate("", "11")).isNull();
    }

    @Test
    void queriesUseParametersAndRejectInjection() {
        WikidataRcaDownloader.Query q = new WikidataRcaDownloader.Query("Q32", LocalDate.of(2021, 10, 26),
                List.of("Q29639349"), List.of("Q13102207", "Q60853309"));
        String pepQuery = WikidataRcaDownloader.pepQuery(q);
        assertThat(pepQuery)
                .contains("wdt:P1001 wd:Q32")
                .contains("VALUES ?pos { wd:Q29639349 }")
                .contains("NOT IN (wd:Q13102207, wd:Q60853309)")
                .contains("\"2021-10-26T00:00:00Z\"^^xsd:dateTime");
        assertThat(WikidataRcaDownloader.relativesQuery(List.of("Q4547", "Q12")))
                .contains("VALUES ?pep { wd:Q4547 wd:Q12 }")
                .contains("pq:P582");

        assertThatThrownBy(() -> new WikidataRcaDownloader.Query("Q32 } DELETE {", LocalDate.now(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WikidataRcaDownloader.relativesQuery(List.of("wd:Q1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsQidsFromSparqlResult() throws Exception {
        var node = new ObjectMapper().readTree(Files.readString(RAW.resolve(WikidataRcaDownloader.PEPS_FILE)));
        assertThat(WikidataRcaDownloader.qids(node, "pep")).hasSize(393).allMatch(s -> s.matches("Q\\d+"));
        assertThat(LuPepRcaParser.list("Q1, Q2\nQ3")).containsExactly("Q1", "Q2", "Q3");
    }
}
