package com.swiftcheck.aml.plugins.depep.dip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Extrait réel de l'API DIP relevé le 19 novembre 2026 (législature 21) : 20 fiches personnes recopiées sans
 * modification (une par fonction, plus des membres du gouvernement sortant) et, pour chacune, l'activité la plus
 * récente de sa réponse {@code /aktivitaet}.
 */
class DePepDipParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static Path extract() throws URISyntaxException {
        return Paths.get(DePepDipParserTest.class.getResource("/dip/person-wp21-0.json").toURI()).getParent();
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String dipId) {
        return dataset.entities().stream().filter(e -> e.getId().equals("de-pep-dip-" + dipId)).findFirst().orElse(null);
    }

    private static FtmEntity occupancy(FtmDatasetBuilder dataset, String dipId) {
        return dataset.entities().stream().filter(e -> e.getId().equals("de-pep-dip-" + dipId + "-wp21")).findFirst()
                .orElse(null);
    }

    private static FtmEntity post(FtmDatasetBuilder dataset, FtmEntity occupancy) {
        String id = occupancy.getValues("post").iterator().next();
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    private static DePepDipParser.Report convert(FtmDatasetBuilder dataset) throws Exception {
        Path dir = extract();
        return DePepDipParser.convert(DipApi.readSaved(dir, 21), DipApi.savedActivities(dir), 21, Set.of("MdB"), TODAY,
                6, 12, DipApi.DEFAULT_ENDPOINT, dataset);
    }

    @Test
    void keepsGovernmentAndBundesratFunctionsAndLeavesMembersToTheBundestagParser() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepDipParser.ID_PREFIX, DePepDipParser.TOPIC);
        DePepDipParser.Report report = convert(dataset);

        assertThat(report.listed()).isEqualTo(23);
        assertThat(report.numFound()).isEqualTo(23);
        assertThat(report.excluded()).isEqualTo(2);       // Stella Merendino et la fiche de député de Patrick Schnieder
        assertThat(report.superseded()).isEqualTo(2);     // anciennes fiches de ministre de Schnieder et de Warken
        assertThat(report.nowExcluded()).isEqualTo(1);    // Michael Kellner : fiche « Parl. Staatssekr. », activité « MdB »
        assertThat(report.tooOld()).isEqualTo(2);         // Julia Willi Hamburg, Hartmut Höppner (gouvernement sortant)
        assertThat(report.current()).isEqualTo(12);
        assertThat(report.unconfirmed()).isEqualTo(4);    // Rehlinger, Otte, Giffey, Woidke : activité entre 6 et 12 mois

        assertThat(person(dataset, "8070")).isNull();
        assertThat(person(dataset, "7528")).isNull();
        assertThat(person(dataset, "7857")).isNull();

        FtmEntity dobrindt = person(dataset, "8121");
        assertThat(dobrindt.getValues("name")).containsExactly("Alexander Dobrindt");
        assertThat(dobrindt.getValues("position")).containsExactly("Bundesminister, Bundesministerium des Innern");
        assertThat(dobrindt.getValues("topics")).contains("role.pep");
        assertThat(dobrindt.getValues("notes").iterator().next()).contains("source_type: official_api").contains("SHA-256");
        assertThat(occupancy(dataset, "8121").getValues("status")).containsExactly("current");

        FtmEntity steffen = person(dataset, "8188");
        assertThat(steffen.getValues("title")).containsExactly("Dr.");
        assertThat(steffen.getValues("name")).containsExactly("Thomas Steffen");

        FtmEntity herrmann = person(dataset, "8272");
        assertThat(herrmann.getValues("position")).containsExactly("Mitglied des Bundesrates (Leiter der Staatskanzlei und "
                + "Staatsminister für Bundes- und Europaangelegenheiten, Internationales und Medien), Bayern");
        assertThat(post(dataset, occupancy(dataset, "8272")).getValues("subnationalArea")).containsExactly("Bayern");

        // Fiche restée sur « Regierende Bürgermeisterin » (jusqu'en 2023) ; l'activité d'avril 2026 donne la fonction exercée.
        assertThat(person(dataset, "7580").getValues("position")).containsExactly(
                "Mitglied des Bundesrates (Bürgermeisterin, Senatorin für Wirtschaft, Energie und Betriebe), Berlin");
        assertThat(occupancy(dataset, "7580").getValues("status")).containsExactly("unknown");

        // Le vice-président du Bundestag agit en séance comme député : sa fonction est gardée.
        assertThat(person(dataset, "8173").getValues("position")).containsExactly("Vizepräsident des Deutschen Bundestages");

        // Le DIP ouvre une fiche par fonction. Schnieder : fiche « Bundesmin. » (activité 07/2026) plus ancienne que sa
        // fiche de député (09/2026) : fonction quittée, gardée en « ended ».
        assertThat(occupancy(dataset, "8129").getValues("status")).containsExactly("ended");
        assertThat(person(dataset, "8129").getValues("position")).containsExactly("Bundesminister, Bundesministerium für Verkehr");
        // Warken : l'ancienne fonction (Santé) est rattachée à la fiche actuelle (chef de la Chancellerie).
        assertThat(person(dataset, "8131")).isNull();
        assertThat(person(dataset, "8249").getValues("position")).containsExactlyInAnyOrder(
                "Bundesminister für besondere Aufgaben und Chef des Bundeskanzleramtes, Bundeskanzleramt",
                "Bundesminister, Bundesministerium für Gesundheit");
        assertThat(occupancy(dataset, "8131").getValues("holder")).containsExactly("de-pep-dip-8249");
        assertThat(occupancy(dataset, "8131").getValues("status")).containsExactly("ended");
        assertThat(occupancy(dataset, "8249").getValues("status")).containsExactly("current");

        assertThat(person(dataset, "8167").getValues("position")).containsExactly("Bundeskanzler");
        assertThat(person(dataset, "8226").getValues("position")).containsExactly("Wehrbeauftragter des Deutschen Bundestages");

        ParserResult result = dataset.toConversionResult();
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.conflictCount()).isZero();
    }

    @Test
    void readsTheFunctionFromAnActivityTitle() {
        assertThat(DePepDipParser.roleOf("Michael Kellner, MdB, BÜNDNIS 90/DIE GRÜNEN", "Kellner"))
                .containsExactly("MdB", null, "BÜNDNIS 90/DIE GRÜNEN");
        assertThat(DePepDipParser.roleOf("Conrad Clemens, Stellv. MdBR (Staatsminister für Kultur), Sachsen", "Clemens"))
                .containsExactly("Stellv. MdBR", "Staatsminister für Kultur", "Sachsen");
        assertThat(DePepDipParser.roleOf("Friedrich Merz, Bundeskanzl.", "Merz")).containsExactly("Bundeskanzl.", null, null);
        assertThat(DePepDipParser.roleOf("Dr. Thomas Steffen, Staatssekr., Bundesministerium für Wirtschaft und Energie",
                "Steffen")).containsExactly("Staatssekr.", null, "Bundesministerium für Wirtschaft und Energie");
    }

    @Test
    void parseWritesTheDatasetFromSavedPages(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out.ndjson");
        ParserResult result = new DePepDipParser().parse(new ParserContext("de_pep_dip", extract(), tmp.resolve("raw"), out,
                Map.of()));
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.conflictCount()).isZero();
        assertThat(Files.readString(out)).contains("Alexander Dobrindt");
        assertThat(result.message()).contains("DIP législature 21").contains("SHA-256");
    }

    @Test
    void anEmptyAnswerStopsTheRunInsteadOfPublishingNothing(@TempDir Path tmp) {
        DipApi.Fetcher empty = url -> "{\"numFound\":0,\"documents\":[],\"cursor\":\"x\"}";
        assertThatThrownBy(() -> DipApi.download(empty, DipApi.DEFAULT_ENDPOINT, 21, tmp, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("aucune personne");
    }

    @Test
    void anIncompleteReadStopsTheRun(@TempDir Path tmp) {
        DipApi.Fetcher truncated = url -> url.contains("cursor=")
                ? "{\"numFound\":150,\"documents\":[],\"cursor\":\"b\"}"
                : "{\"numFound\":150,\"documents\":[{\"id\":\"1\",\"nachname\":\"A\"}],\"cursor\":\"b\"}";
        assertThatThrownBy(() -> DipApi.download(truncated, DipApi.DEFAULT_ENDPOINT, 21, tmp, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("incomplète");
    }

    /**
     * Contrôle sur la législature complète, lancé à la main :
     * {@code mvn test -Ddip.full=<dossier des pages person-wp21-*.json et aktivitaet-*.json>}.
     */
    @Test
    @EnabledIfSystemProperty(named = "dip.full", matches = ".+")
    void fullLegislatureHasNoRejectedValueAndNoConflict() throws Exception {
        Path dir = Paths.get(System.getProperty("dip.full"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepDipParser.ID_PREFIX, DePepDipParser.TOPIC);
        DePepDipParser.Report report = DePepDipParser.convert(DipApi.readSaved(dir, 21), DipApi.savedActivities(dir), 21,
                Set.of("MdB"), LocalDate.now(), 6, 12, DipApi.DEFAULT_ENDPOINT, dataset);
        ParserResult result = dataset.toConversionResult();
        System.out.println(report.message(21, 6, 12));
        System.out.println(result.message());
        List<String> lines = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next() + " | " + String.join(" ; ", e.getValues("position")))
                .sorted().collect(Collectors.toList());
        Files.write(dir.resolve("persons.txt"), lines);
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.conflictCount()).isZero();
    }
}
