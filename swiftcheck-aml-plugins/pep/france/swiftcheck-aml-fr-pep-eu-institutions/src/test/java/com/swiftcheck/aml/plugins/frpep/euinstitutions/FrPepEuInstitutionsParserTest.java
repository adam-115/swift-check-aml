package com.swiftcheck.aml.plugins.frpep.euinstitutions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les pages officielles réelles du 30/10/2026 : CJUE (Cour et Tribunal), composition de la
 * Cour des comptes européenne (PDF), directoire de la BCE et CV de ses membres, conseil de surveillance prudentielle,
 * secrétaire général du Conseil.
 */
class FrPepEuInstitutionsParserTest {

    private static final Path PAGES = Path.of("src/test/resources/pages");

    private final FrPepEuInstitutionsParser parser = new FrPepEuInstitutionsParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepEuInstitutionsParser.ID_PREFIX,
                FrPepEuInstitutionsParser.TOPIC);
        parser.convert(PAGES, dataset, Map.of());
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    private static List<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
    }

    @Test
    void frenchMembersOfEuInstitutions() throws Exception {
        FtmDatasetBuilder dataset = convert();
        assertThat(names(dataset)).containsExactlyInAnyOrder(
                "Stéphane Gervasoni", "Jean Richard de la Tour", "Stéphanie Laulhé Shaelou",       // Cour de justice
                "Laurent Truchot", "Hervé Cassagnabère", "Raphaël Meyer",                          // Tribunal
                "Pierre MOSCOVICI",                                                                // Cour des comptes
                "Christine Lagarde", "Denis Beau",                                                 // BCE
                "Thérèse Blanchet");                                                               // Conseil
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("nationality")).containsExactly("fr");
                    assertThat(p.getValues("notes")).singleElement().asString().startsWith("Nationalité française : ");
                });
    }

    @Test
    void reasonForEachNationality() throws Exception {
        FtmDatasetBuilder dataset = convert();
        assertThat(person(dataset, "Pierre MOSCOVICI").getValues("notes"))
                .containsExactly("Nationalité française : pays indiqué par la Cour : France");
        assertThat(person(dataset, "Stéphanie Laulhé Shaelou").getValues("notes"))
                .containsExactly("Nationalité française : nationalité française écrite dans la biographie");
        assertThat(person(dataset, "Laurent Truchot").getValues("notes"))
                .containsExactly("Nationalité française : né(e) en France (biographie officielle)");
        // Né à Zurich, carrière en France
        assertThat(person(dataset, "Raphaël Meyer").getValues("notes")).singleElement().asString()
                .contains("carrière en France");
        assertThat(person(dataset, "Christine Lagarde").getValues("notes"))
                .containsExactly("Nationalité française : né(e) à Paris, France (CV officiel)");
        assertThat(person(dataset, "Thérèse Blanchet").getValues("notes"))
                .containsExactly("Nationalité française : nationalité indiquée : française");
    }

    @Test
    void functions() throws Exception {
        FtmDatasetBuilder dataset = convert();
        assertThat(person(dataset, "Jean Richard de la Tour").getValues("position"))
                .containsExactly("Avocat général à la Cour de justice");
        assertThat(person(dataset, "Raphaël Meyer").getValues("position")).containsExactly("Juge au Tribunal");
        assertThat(person(dataset, "Christine Lagarde").getValues("position")).containsExactly("Présidente de la BCE");
        assertThat(person(dataset, "Denis Beau").getValues("position"))
                .containsExactly("Membre du conseil de surveillance prudentielle de la BCE");
    }

    @Test
    void nonFrenchMembersWhoStudiedInFranceAreNotKept() throws Exception {
        FrPepEuInstitutionsParser.Report report = parser.convert(PAGES,
                new FtmDatasetBuilder(FrPepEuInstitutionsParser.ID_PREFIX), Map.of());
        // Juges luxembourgeois, chypriote, néerlandais formés à Paris : 1 à 3 mentions « (France) »
        assertThat(report.french()).noneMatch(f -> f.startsWith("Dean Spielmann") || f.startsWith("Marc Jaeger")
                || f.startsWith("François Biltgen"));
        assertThat(report.byInstitution().get("Cour des comptes européenne")).containsExactly(27, 1);
        assertThat(report.byInstitution().get("Cour de justice de l'Union européenne")[1]).isEqualTo(6);
        assertThat(report.message()).contains("Non couvert : chefs des délégations de l'UE");
    }

    @Test
    void biographyRule() {
        assertThat(EuReaders.frenchFromBiography("Né en 1962 à Paris (France), M. X suit des études.")).isNotNull();
        assertThat(EuReaders.frenchFromBiography("Né en 1958 à Luxembourg (Grand-Duché de Luxembourg), M. Y obtient"
                + " une maîtrise à Paris II (France) et un diplôme de l'IEP de Paris (France).")).isNull();
        assertThat(EuReaders.frenchFromBiography("Née en 1974 à Nicosie (Chypre), Mme Z, qui possède la double"
                + " nationalité chypriote et française, …")).isNotNull();
    }

    @Test
    void readSourceRecordsListsFrenchMembers(@TempDir Path tmp) throws Exception {
        try (var files = Files.list(PAGES)) {
            for (Path f : files.toList()) {
                Files.copy(f, tmp.resolve(f.getFileName()));
            }
        }
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("fr_pep_eu_institutions", null, tmp,
                null, Map.of()));
        assertThat(records).hasSize(10).allSatisfy(r -> assertThat(r.sourceRef()).startsWith("https://"));
    }

    @Test
    void cvLinksFoundOnBoardPage() throws Exception {
        List<String[]> links = EuSources.ecbCvLinks(Files.readString(PAGES.resolve(EuSources.ECB_BOARD_FILE)),
                EuSources.DEFAULT_URLS.get("ecbBoardUrl"));
        assertThat(links).extracting(l -> l[0]).containsExactlyInAnyOrder("lagarde", "vujcic", "cipollone", "elderson",
                "lane", "schnabel");
        assertThat(links).extracting(l -> l[1]).contains("https://www.ecb.europa.eu/ecb/decisions/html/cvlagarde.fr.html");
    }

    @Test
    void noMemberIsAnError(@TempDir Path tmp) {
        assertThatThrownBy(() -> parser.convert(tmp, new FtmDatasetBuilder(FrPepEuInstitutionsParser.ID_PREFIX), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
