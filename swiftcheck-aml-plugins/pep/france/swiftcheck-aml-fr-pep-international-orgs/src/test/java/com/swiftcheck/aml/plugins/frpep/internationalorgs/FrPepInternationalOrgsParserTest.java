package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.internationalorgs.OrgConfig.Organization;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les pages officielles réelles du 30/10/2026 (une par organisation lue), avec la configuration
 * livrée dans le plugin.
 */
class FrPepInternationalOrgsParserTest {

    private static final Path PAGES = Path.of("src/test/resources/pages");

    private final FrPepInternationalOrgsParser parser = new FrPepInternationalOrgsParser();

    private static List<Organization> config() throws Exception {
        return OrgConfig.load(null);
    }

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX,
                FrPepInternationalOrgsParser.TOPIC);
        parser.convert(config(), PAGES, dataset);
        return dataset;
    }

    private static List<String> positions(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name))
                .getValues("position").stream().toList();
    }

    @Test
    void configMatchesOfficialList() throws Exception {
        List<Organization> orgs = config();
        // 21 organisations de la liste française (JO C/2023/724), dont 3 sans fonction PEP
        assertThat(orgs).hasSize(21);
        assertThat(orgs.stream().filter(Organization::none)).hasSize(3);
        assertThat(orgs.stream().filter(o -> !o.pages().isEmpty())).hasSize(9);
    }

    @Test
    void readsRealPages() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FrPepInternationalOrgsParser.Report report = parser.convert(config(), PAGES,
                new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX));

        assertThat(report.organizationsRead()).isEqualTo(9);
        assertThat(report.functionsNotFound()).isEmpty();
        // 28 lors de l'exécution réelle (8 organisations) + 12 de la page d'IPGRI (sa présidente est déjà au CGIAR)
        assertThat(report.leaders()).isEqualTo(40);
        // 9 organisations non lues et 6 lues en partie (adjoints, conseils) : citées dans le message
        assertThat(report.pending()).hasSize(15);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
    }

    @Test
    void leadersByOrganization() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Nom puis fonction sur la ligne suivante (deux secrétaires généraux, un français et un allemand)
        assertThat(positions(dataset, "Tobias Bütow"))
                .containsExactly("Secrétaire général – Office franco-allemand pour la jeunesse (OFAJ)");
        assertThat(positions(dataset, "Pascale Trimbach")).hasSize(1);
        // Fonction puis nom
        assertThat(positions(dataset, "Nico HORN")).singleElement().asString().startsWith("Directeur général – ");
        // Phrase : « La docteure Emmanuelle Soubeyran a été élue Directrice générale »
        assertThat(positions(dataset, "Emmanuelle Soubeyran")).singleElement().asString()
                .startsWith("Directeur général – Organisation mondiale de la santé animale");
        // Titre de la page : « Khaled El-Enany, Directeur général | UNESCO »
        assertThat(positions(dataset, "Khaled El-Enany")).singleElement().asString().contains("Unesco");
        // Présidente du conseil d'IPGRI et membre du conseil du CGIAR : une personne, deux fonctions
        assertThat(positions(dataset, "Julia Marton-Lefèvre")).containsExactlyInAnyOrder(
                "Président du Conseil d'administration – Institut international des ressources phytogénétiques"
                        + " (IPGRI, Alliance Bioversity International – CIAT)",
                "Membre du conseil d'administration – Organisation du Système CGIAR");
        // « H.E. » retiré
        assertThat(positions(dataset, "Hailemariam Dessalegn Boshe")).hasSize(1);
        assertThat(positions(dataset, "Ismahane Elouafi")).containsExactly("Directeur – Organisation du Système CGIAR");
        // CIHEAM : « Ms. Frida Krifca was elected President … Mr. Teodoro Miano was elected Secretary General »
        assertThat(positions(dataset, "Frida Krifca")).singleElement().asString().startsWith("Président du Conseil");
        assertThat(positions(dataset, "Teodoro Miano")).singleElement().asString().startsWith("Secrétaire général");
        assertThat(positions(dataset, "Annette Koo")).singleElement().asString().startsWith("Directeur du Bureau"
                + " international des poids et mesures");
        // OIML : prénom, NOM, fonction sur trois lignes
        assertThat(positions(dataset, "Anthony DONNELLAN")).singleElement().asString().startsWith("Directeur du"
                + " bureau international de métrologie légale");
        // IPGRI : directeur général, ex officio au conseil : pas de doublon « (ex-officio) »
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .noneMatch(p -> p.getValues("name").iterator().next().contains("ex-officio"));
    }

    @Test
    void pageMissingIsReportedNotFatal(@TempDir Path tmp) throws Exception {
        Files.copy(PAGES.resolve("ofaj-1.html"), tmp.resolve("ofaj-1.html"));
        Files.writeString(tmp.resolve(FrPepInternationalOrgsParser.FAILED_FILE), "unesco : HTTP 503");
        FrPepInternationalOrgsParser.Report report = parser.convert(config(), tmp,
                new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX));
        assertThat(report.leaders()).isEqualTo(2);
        assertThat(report.message()).contains("Pages en échec : unesco : HTTP 503");
    }

    @Test
    void changedPageIsReported(@TempDir Path tmp) throws Exception {
        Files.copy(PAGES.resolve("ofaj-1.html"), tmp.resolve("ofaj-1.html"));
        Files.writeString(tmp.resolve("unesco-1.html"), "<html><title>UNESCO</title><p>Page en travaux</p></html>");
        FrPepInternationalOrgsParser.Report report = parser.convert(config(), tmp,
                new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX));
        assertThat(report.functionsNotFound()).containsExactly("unesco – Directeur général");
    }

    @Test
    void noLeaderIsAnError(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() -> parser.convert(config(), tmp, new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configFileCanReplaceBundledConfig(@TempDir Path tmp) throws Exception {
        String json = "{\"organizations\":[{\"key\":\"x\",\"name\":\"Organisation X\",\"pages\":[{\"url\":"
                + "\"https://x.int\",\"functions\":[{\"label\":\"Directeur\",\"pattern\":\"^Directeur : {NAME}$\"}]}]}]}";
        List<Organization> orgs = OrgConfig.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(tmp.resolve("x-1.html"), "<p>Directeur : Jean DUPONT</p>");
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepInternationalOrgsParser.ID_PREFIX);
        parser.convert(orgs, tmp, dataset);
        assertThat(positions(dataset, "Jean DUPONT")).containsExactly("Directeur – Organisation X");
    }

    @Test
    void readSourceRecordsListsLeaders(@TempDir Path tmp) throws Exception {
        try (var files = Files.list(PAGES)) {
            for (Path f : files.toList()) {
                Files.copy(f, tmp.resolve(f.getFileName()));
            }
        }
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("fr_pep_international_orgs", null,
                tmp, null, Map.of()));
        assertThat(records).extracting(SourceRecord::name).contains("Khaled El-Enany", "Tobias Bütow");
        assertThat(records).allSatisfy(r -> assertThat(r.sourceRef()).startsWith("https://"));
    }
}
