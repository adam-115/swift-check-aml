package com.swiftcheck.aml.plugins.bepep.localexecutives;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Réponses réelles de Regimand (Cour des comptes, exercice 2024) pour Charleroi et la province de Liège. */
class RegimandLocalTest {

    private static final Path REGIMAND = Path.of("src/test/resources/regimand");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate SINCE = LocalDate.of(2024, 11, 1);

    @Test
    void keepsAldermenAndCpasPresidentsOfTheCityButNotItsMayor() throws IOException {
        List<RegimandLocal.Mandate> mandates = RegimandLocal.select(read("charleroi.json"), false,
                List.of("Charleroi"), LocalDate.of(2000, 1, 1));

        assertThat(mandates).isNotEmpty();
        assertThat(mandates).allSatisfy(m -> {
            assertThat(m.institution()).startsWith("Charleroi (ville de)");
            assertThat(m.role()).matches("(?i)(Échevin|Président de CPAS).*");
            assertThat(m.fullName()).isNotBlank();
        });
        assertThat(mandates).extracting(RegimandLocal.Mandate::role).contains("Échevin", "Président de CPAS");
        assertThat(mandates).extracting(RegimandLocal.Mandate::role).noneMatch(r -> r.toLowerCase().contains("bourgmestre"));
    }

    @Test
    void keepsProvincialDeputiesButNotTheGovernor() throws IOException {
        List<RegimandLocal.Mandate> mandates = RegimandLocal.select(read("province-liege.json"), true,
                List.of("Liège", "Luik"), LocalDate.of(2000, 1, 1));

        assertThat(mandates).isNotEmpty();
        assertThat(mandates).extracting(RegimandLocal.Mandate::role).containsOnly("Député provincial");
        assertThat(mandates).allSatisfy(m -> assertThat(m.institution()).isEqualTo("Province de Liège"));
    }

    @Test
    void doesNotMistakeAnAssociationForTheCity() {
        assertThat(RegimandLocal.matches(RegimandLocal.fold("Charleroi danse - Centre chorégraphique"), false,
                List.of("Charleroi"))).isFalse();
        assertThat(RegimandLocal.matches(RegimandLocal.fold("Charleroi (ville de)"), false, List.of("Charleroi"))).isTrue();
        assertThat(RegimandLocal.matches(RegimandLocal.fold("Gent (Ville de)"), false, List.of("Gand", "Gent"))).isTrue();
        assertThat(RegimandLocal.matches(RegimandLocal.fold("Anderlecht (commune d')"), false, List.of("Anderlecht"))).isTrue();
        assertThat(RegimandLocal.matches(RegimandLocal.fold("Province de Namur"), true, List.of("province de Namur"))).isTrue();
        assertThat(RegimandLocal.matches(RegimandLocal.fold("BEP - Bureau économique de la province de Namur, sc"), true,
                List.of("province de Namur"))).isFalse();
    }

    @Test
    void dropsAMandateEndedBeforeTheRetention() throws IOException {
        assertThat(RegimandLocal.select(read("charleroi.json"), false, List.of("Charleroi"), LocalDate.of(2100, 1, 1)))
                .allSatisfy(m -> assertThat(m.end()).isNull());
    }

    @Test
    void downloadsEachAreaByItsNamesThenConvertsThem(@TempDir Path rawDir) throws Exception {
        Files.copy(Path.of("src/test/resources/local/wikidata-local.json"), rawDir.resolve(BePepLocalExecutivesParser.WIKIDATA_FILE));
        List<String> asked = new java.util.ArrayList<>();
        BePepLocalExecutivesParser parser = new BePepLocalExecutivesParser();
        parser.downloadRegimand(name -> {
            asked.add(name);
            if (name.equals("Charleroi")) {
                return Files.readString(REGIMAND.resolve("charleroi.json"), StandardCharsets.UTF_8);
            }
            if (name.equals("Liège") || name.equals("Luik")) {
                return Files.readString(REGIMAND.resolve("province-liege.json"), StandardCharsets.UTF_8);
            }
            return "[]";
        }, 50_000L, 0, rawDir);

        assertThat(asked).contains("Charleroi", "Liège", "Luik");
        assertThat(Files.exists(rawDir.resolve(BePepLocalExecutivesParser.REGIMAND_PREFIX + "Q81046.json"))).isTrue();

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepLocalExecutivesParser.ID_PREFIX, BePepLocalExecutivesParser.TOPIC);
        BePepLocalExecutivesParser.Report report = parser.convert(rawDir, 50_000L, SINCE, dataset);

        assertThat(report.regimandMandates()).isPositive();
        assertThat(report.message()).contains("échevins, présidents de CPAS et députés provinciaux");
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Position".equals(e.getSchema()))
                .flatMap(e -> e.getValues("name").stream()))
                .anyMatch(n -> n.startsWith("Échevin – Charleroi"))
                .anyMatch(n -> n.startsWith("Député provincial – Liège"));
    }

    @Test
    void reportsAnAreaWhoseSearchFailsAndStopsWhenAllFail(@TempDir Path rawDir) throws Exception {
        Files.copy(Path.of("src/test/resources/local/wikidata-local.json"), rawDir.resolve(BePepLocalExecutivesParser.WIKIDATA_FILE));
        BePepLocalExecutivesParser parser = new BePepLocalExecutivesParser();

        parser.downloadRegimand(name -> {
            if (name.equals("Charleroi")) {
                throw new IOException("Regimand HTTP 503");
            }
            return "[]";
        }, 50_000L, 0, rawDir);
        assertThat(Files.readString(rawDir.resolve(BePepLocalExecutivesParser.REGIMAND_FAILURES))).contains("Charleroi (Regimand HTTP 503)");

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> parser.downloadRegimand(name -> {
            throw new IOException("Regimand HTTP 503");
        }, 50_000L, 0, rawDir));
    }

    private static JsonNode read(String file) throws IOException {
        return JSON.readTree(Files.readString(REGIMAND.resolve(file), StandardCharsets.UTF_8));
    }
}
