package com.swiftcheck.aml.plugins.depep.defenceattaches;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Listes diplomatiques officielles relevées le 16 novembre 2026 (Suède, Royaume-Uni en .odt ; texte PDFBox trié pour les
 * autres, pour garder les fichiers de test petits).
 */
class DiplomaticListsTest {

    static final String LISTS = """
            [{"host":"Suède","url":"se.pdf","nameAt":"same"},
             {"host":"Royaume-Uni","url":"uk.odt","nameAt":"same"},
             {"host":"Islande","url":"is.txt","nameAt":"after"},
             {"host":"Macédoine du Nord","url":"mk.txt","nameAt":"before"},
             {"host":"Estonie","url":"ee.txt","nameAt":"before"},
             {"host":"Danemark","url":"dk.txt","nameAt":"same"},
             {"host":"Thaïlande","url":"th.txt","nameAt":"before"},
             {"host":"Singapour","url":"sg.txt","nameAt":"before"}]""";

    static byte[] bytes(String file) throws IOException {
        try (InputStream in = DiplomaticListsTest.class.getResourceAsStream("/lists/" + file)) {
            if (in == null) {
                throw new IOException("HTTP 404");
            }
            return in.readAllBytes();
        }
    }

    @Test
    void readsTheGermanDefenceAttacheOfEachList() {
        DePepDefenceAttachesParser.Reading reading = DePepDefenceAttachesParser.read(DePepDefenceAttachesParser.lists(LISTS),
                DiplomaticListsTest::bytes, null);
        System.out.println("READ " + reading.message());
        reading.byPerson().values().forEach(v -> System.out.println("ATTACHE " + v.get(0).rank() + " | " + v.get(0).fullName() + " | "
                + v.stream().map(DiplomaticLists.Attache::host).toList() + " | " + v.get(0).line()));
        assertThat(reading.byPerson().values().stream().map(v -> v.get(0).fullName())).containsExactlyInAnyOrder("Ralf Heimrich",
                "Stefan Klenz", "Alexander Türk", "Guido Altendorf", "Harald Krempchen", "Mirko Wilcken", "Michael Raimund Sichler",
                "Michael Radtke", "Ivo Schneider");
    }

    @Test
    void readsNameAndRank() {
        DiplomaticLists.ListConfig same = new DiplomaticLists.ListConfig("Suède", null, "u", "same", null, null);
        assertThat(DiplomaticLists.attache("Colonel Ralf HEIMRICH, ", same, "l").fullName()).isEqualTo("Ralf Heimrich");
        assertThat(DiplomaticLists.attache("Colonel Ralf HEIMRICH, ", same, "l").rank()).isEqualTo("Colonel");
        assertThat(DiplomaticLists.attache("Colonel (GS) Andreas Bauer – ", same, "l").fullName()).isEqualTo("Andreas Bauer");
        assertThat(DiplomaticLists.attache("Commander Michael Radtke 13.08.2025", same, "l").fullName()).isEqualTo("Michael Radtke");
        assertThat(DiplomaticLists.attache("LTC i.G. Guido Altendorf", same, "l").fullName()).isEqualTo("Guido Altendorf");
        assertThat(DiplomaticLists.attache("Mr Mirko Wilcken (01.09.2026) ", same, "l").fullName()).isEqualTo("Mirko Wilcken");
        assertThat(DiplomaticLists.attache("Mrs. Jessica", same, "l")).isNull();
    }

    @Test
    void publishesOnePersonPerAttache() {
        DePepDefenceAttachesParser.Reading reading = DePepDefenceAttachesParser.read(DePepDefenceAttachesParser.lists(LISTS),
                DiplomaticListsTest::bytes, null);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepDefenceAttachesParser.ID_PREFIX, DePepDefenceAttachesParser.TOPIC);
        DePepDefenceAttachesParser.convert(reading, dataset);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count()).isEqualTo(reading.byPerson().size());
    }

}
