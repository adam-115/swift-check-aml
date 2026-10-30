package com.swiftcheck.aml.plugins.frpep.diplomats;

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
import com.swiftcheck.aml.plugins.frpep.diplomats.DiplomatListReader.Diplomat;
import com.swiftcheck.aml.plugins.frpep.diplomats.DiplomatListReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les deux listes réelles du MEAE : chefs de poste au 22/05/2026 (176 en poste, 7 postes sans
 * titulaire) et ambassadeurs thématiques au 16/02/2026 (18), avec les pages qui les citent.
 */
class FrPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/diplomats");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final FrPepDiplomatsParser parser = new FrPepDiplomatsParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepDiplomatsParser.ID_PREFIX, FrPepDiplomatsParser.TOPIC);
        parser.convert(RAW, TODAY, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    @Test
    void convertsRealLists() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FrPepDiplomatsParser.Report report = parser.convert(RAW, TODAY, new FtmDatasetBuilder(FrPepDiplomatsParser.ID_PREFIX));

        assertThat(report.headsOfPost()).isEqualTo(176);
        assertThat(report.chargesDAffaires()).isEqualTo(4);
        assertThat(report.vacant()).isEqualTo(7);
        assertThat(report.thematic()).isEqualTo(18);
        assertThat(report.headsUpdated()).isEqualTo(LocalDate.of(2026, 5, 22));
        assertThat(report.thematicUpdated()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(report.unread()).isEmpty();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> assertThat(p.getValues("topics")).contains("role.pep", "role.diplo"));
        // 22 mai 2026 → 30 octobre 2026 : 161 jours, sous le seuil d'alerte de 200 jours
        assertThat(report.message(200)).doesNotContain("Attention");
        assertThat(report.message(100)).contains("vieille de 161 jours");
    }

    @Test
    void ambassadorsAndPermanentRepresentatives() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity delattre = person(dataset, "François DELATTRE");
        assertThat(delattre.getValues("position")).containsExactly("Ambassadeur de France – Allemagne (BERLIN)");
        assertThat(delattre.getValues("lastName")).containsExactly("DELATTRE");
        assertThat(delattre.getValues("gender")).containsExactly("male");
        assertThat(person(dataset, "Jérôme BONNAFONT").getValues("position"))
                .containsExactly("Représentant permanent de la France – ONU NEW YORK");
        // Particules avant le nom en majuscules
        FtmEntity poncins = person(dataset, "Etienne de MONTAIGNE de PONCINS");
        assertThat(poncins.getValues("lastName")).containsExactly("de MONTAIGNE de PONCINS");
        assertThat(poncins.getValues("firstName")).containsExactly("Etienne");
        // Chargé d'affaires ; poste « sis à » un autre pays
        assertThat(person(dataset, "Jean-Baptiste FAIVRE").getValues("position"))
                .containsExactly("Chargé d'affaires – Syrie (DAMAS sis à BEYROUTH)");
        // Poste sans pays
        assertThat(person(dataset, "Nicolas KASSIANIDES").getValues("position"))
                .containsExactly("Ambassadeur de France – JERUSALEM (FSLT)");
        FtmEntity occupancy = dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(delattre.getId()))
                .findFirst().orElseThrow();
        assertThat(occupancy.getValues("startDate")).containsExactly("2022-09-20");
        assertThat(occupancy.getValues("status")).containsExactly("current");
        assertThat(occupancy.getValues("sourceUrl")).allMatch(u -> u.startsWith("https://www.diplomatie.gouv.fr/"));
    }

    @Test
    void thematicAmbassadors() throws Exception {
        FtmDatasetBuilder dataset = convert();
        assertThat(person(dataset, "Anne-Claire AMPROU").getValues("position"))
                .containsExactly("Ambassadrice pour la santé mondiale");
        // Fonction sur deux lignes
        assertThat(person(dataset, "Benoît FARACO").getValues("position")).containsExactly("Ambassadeur chargé des"
                + " négociations sur le changement climatique pour les énergies renouvelables et la prévention des"
                + " risques climatiques");
        assertThat(person(dataset, "Olivier POIVRE D'ARVOR").getValues("gender")).containsExactly("male");
        assertThat(person(dataset, "Barbara POMPILI").getValues("gender")).containsExactly("female");
    }

    @Test
    void namesAndPlaces() {
        assertThat(DiplomatListReader.splitName("DE RIVIERE Nicolas")).containsExactly("DE RIVIERE", "Nicolas");
        assertThat(DiplomatListReader.splitName("D'HAUSSONVILLE Jean")).containsExactly("D'HAUSSONVILLE", "Jean");
        assertThat(DiplomatListReader.splitName("de CARNE DE TRECESSON Marine"))
                .containsExactly("de CARNE DE TRECESSON", "Marine");
        assertThat(DiplomatListReader.splitPlace("Belgique RP UE BRUXELLES")).containsExactly("Belgique", "RP UE BRUXELLES");
        assertThat(DiplomatListReader.splitPlace("Congo (Rép. Dém.) KINSHASA")).containsExactly("Congo (Rép. Dém.)", "KINSHASA");
        assertThat(DiplomatListReader.splitPlace("Afrique du sud PRETORIA")).containsExactly("Afrique du sud", "PRETORIA");

        List<Diplomat> heads = DiplomatListReader.readHeadsOfPost(List.of(
                "Mali BAMAKO M. FOURRIERE Thibaut (chargé d'affaires) 15 septembre 2025 DAOI",
                "Afghanistan KABOUL AS", "PYONGYANG (BFC) - fermeture 9/03/2020 AS")).diplomats();
        assertThat(heads).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Kind.CHARGE_D_AFFAIRES);
            assertThat(d.start()).isEqualTo(LocalDate.of(2025, 9, 15));
        });
    }

    @Test
    void findsPdfLinksInPages() throws Exception {
        String embassies = Files.readString(RAW.resolve(DiplomatsDownloader.EMBASSIES_PAGE));
        assertThat(DiplomatsDownloader.pdfLink(embassies, DiplomatsDownloader.HEADS_OF_POST_LINK,
                FrPepDiplomatsParser.DEFAULT_EMBASSIES_URL)).isEqualTo("https://www.diplomatie.gouv.fr/files/files/"
                        + "le-ministere/administration/ambassadeurs-et-ambassadrices/260522_liste_chefs_de_poste.pdf");
        String thematic = Files.readString(RAW.resolve(DiplomatsDownloader.THEMATIC_PAGE));
        assertThat(DiplomatsDownloader.pdfLink(thematic, DiplomatsDownloader.THEMATIC_LINK,
                FrPepDiplomatsParser.DEFAULT_THEMATIC_URL)).endsWith("260216-liste-des-amb.thematiques-diffusion-diplonet.pdf");
    }

    @Test
    void readSourceRecordsListsPersons(@TempDir Path tmp) throws Exception {
        for (String f : List.of(DiplomatsDownloader.HEADS_OF_POST_PDF, DiplomatsDownloader.THEMATIC_PDF)) {
            Files.copy(RAW.resolve(f), tmp.resolve(f));
        }
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("fr_pep_diplomats", null, tmp, null,
                Map.of()));
        // 176 chefs de poste et 18 thématiques ; une personne des deux listes n'est comptée qu'une fois
        assertThat(records).hasSizeGreaterThan(190).allSatisfy(r -> assertThat(r.schema()).isEqualTo("Person"));
        assertThat(records).extracting(SourceRecord::name).contains("François DELATTRE", "Anne-Claire AMPROU");
    }

    @Test
    void emptyListIsAnError(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(DiplomatsDownloader.THEMATIC_PDF), tmp.resolve(DiplomatsDownloader.HEADS_OF_POST_PDF));
        assertThatThrownBy(() -> parser.convert(tmp, TODAY, new FtmDatasetBuilder(FrPepDiplomatsParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mise en page");
    }
}
