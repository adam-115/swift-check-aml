package com.swiftcheck.aml.plugins.depep.federalsecretaries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Datenhandbuch du Bundestag, chapitre 6.3, version du 12 août 2026 (PDF réel). */
class DePepFederalSecretariesParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static List<DhbTable.Seg> segs;
    private static List<DhbTable.Holder> holders;

    @BeforeAll
    static void read() throws Exception {
        try (InputStream in = DePepFederalSecretariesParserTest.class.getResourceAsStream("/kapitel-06-03.pdf")) {
            segs = DhbTable.segments(in);
        }
        holders = DhbTable.holders(segs, 21);
    }

    private static DhbTable.Holder find(String name, DhbTable.Kind kind) {
        return holders.stream().filter(h -> h.name().equals(name) && h.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError(name + " " + kind + " absent de " + holders.stream().map(DhbTable.Holder::name).toList()));
    }

    @Test
    void readsTheDateOfTheHandbook() {
        assertThat(DhbTable.standDate(segs)).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    void readsMinistersWithTheirReplacements() {
        DhbTable.Holder warken = find("Nina Warken", DhbTable.Kind.MINISTER);
        // deux lignes pour Nina Warken : ministre de la Santé jusqu'au 29/07/2026, puis chef de la Chancellerie
        assertThat(holders.stream().filter(h -> h.name().equals("Nina Warken")).map(DhbTable.Holder::end).toList())
                .containsExactlyInAnyOrder(LocalDate.of(2026, 7, 29), null);
        assertThat(find("Carsten Linnemann", DhbTable.Kind.MINISTER).start()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(find("Thorsten Frei", DhbTable.Kind.MINISTER).end()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(warken.party()).isEqualTo("CDU");
    }

    @Test
    void readsParliamentaryAndCivilStateSecretariesInTheirOwnColumns() {
        assertThat(find("Tino Sorge", DhbTable.Kind.PARLIAMENTARY).end()).isEqualTo(LocalDate.of(2026, 7, 29));
        // Schenderlein : Staatsministerin à la Chancellerie, puis secrétaire d'État parlementaire à la Santé depuis le 29/07/2026
        assertThat(holders).anyMatch(h -> h.name().equals("Christiane Schenderlein") && h.ministry().contains("Gesundheit")
                && LocalDate.of(2026, 7, 29).equals(h.start()));
        assertThat(find("Hans-Georg Engelke", DhbTable.Kind.CIVIL).ministry()).isEqualTo("BM des Innern");
        assertThat(find("Sebastian Wüste", DhbTable.Kind.CIVIL).start()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(find("Jens Plötner", DhbTable.Kind.CIVIL).start()).isEqualTo(LocalDate.of(2025, 8, 27));
        assertThat(find("Benedikt Zimmer", DhbTable.Kind.CIVIL).end()).isEqualTo(LocalDate.of(2025, 8, 27));
    }

    @Test
    void joinsNamesSplitOverTwoLinesAndKeepsTheRole() {
        assertThat(find("Martina Englhardt-Kopf", DhbTable.Kind.PARLIAMENTARY).party()).isEqualTo("CSU");
        DhbTable.Holder kaiser = find("Elisabeth Kaiser", DhbTable.Kind.PARLIAMENTARY);
        assertThat(kaiser.role()).contains("Ostdeutschland");
        assertThat(find("Wolfram Weimer", DhbTable.Kind.PARLIAMENTARY).role()).contains("Kultur und Medien");
    }

    @Test
    void doesNotInventAPersonForAnUnnamedSuccessor() {
        assertThat(holders).noneMatch(h -> h.name().startsWith("N."));
        // « Christiane Schenderlein (bis 29.7.2026) N. N. (ab 29.7.2026) » : la date d'arrivée ne revient pas à Schenderlein
        DhbTable.Holder s = holders.stream().filter(h -> h.name().equals("Christiane Schenderlein")
                && h.ministry().contains("Bundeskanzleramt")).findFirst().orElseThrow();
        assertThat(s.end()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(s.start()).isNull();
    }

    @Test
    void flagsTheDeceasedAndDropsThem(@TempDir Path dir) throws Exception {
        assertThat(find("Carsten Träger", DhbTable.Kind.PARLIAMENTARY).deceased()).isTrue();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepFederalSecretariesParser.ID_PREFIX, DePepFederalSecretariesParser.TOPIC);

        DePepFederalSecretariesParser.convert(holders, null, "x", TODAY, 12, dataset);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet())).doesNotContain("Carsten Träger");
    }

    @Test
    void keepsRecentDeparturesAndLeavesCurrentMinistersToTheCabinetPlugin() {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepFederalSecretariesParser.ID_PREFIX, DePepFederalSecretariesParser.TOPIC);

        DePepFederalSecretariesParser.Report r = DePepFederalSecretariesParser.convert(holders, LocalDate.of(2026, 8, 12),
                "x", TODAY, 12, dataset);

        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
        assertThat(names).contains("Tino Sorge", "Thorsten Frei", "Michael Meister", "Gitta Connemann");
        assertThat(names).doesNotContain("Boris Pistorius", "Lars Klingbeil", "Carsten Linnemann"); // ministres en fonction
        assertThat(r.currentMinisters()).isGreaterThanOrEqualTo(14);
        assertThat(r.droppedOld()).isGreaterThanOrEqualTo(1); // Thomas Steffen, parti en mai 2025
        FtmEntity sorge = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2026-07-29") && e.getValues("sourceUrl").contains("x")).findFirst().orElseThrow();
        assertThat(sorge.getValues("status")).containsExactly("ended");
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }

    @Test
    void writesTheDatasetFromALocalPdf(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("kapitel-06-03.pdf");
        try (InputStream is = DePepFederalSecretariesParserTest.class.getResourceAsStream("/kapitel-06-03.pdf")) {
            Files.write(in, is.readAllBytes());
        }
        Path out = dir.resolve("out.ndjson");

        ParserResult result = new DePepFederalSecretariesParser().parse(new ParserContext("de_pep_federal_secretaries", in,
                dir, out, Map.of()));

        assertThat(result.message()).contains("titulaires retenus").contains("Datenhandbuch arrêté au 2026-08-12");
        assertThat(Files.readString(out)).contains("\"name\":[\"Tino Sorge\"]").contains("role.pep").contains("gov.executive");
    }

    @Test
    void discoversThePdfAddressOnTheChapterPage() throws IOException {
        try (InputStream in = DePepFederalSecretariesParserTest.class.getResourceAsStream("/kapitel-06-page.html")) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(DePepFederalSecretariesParser.discoverPdfUrl(html))
                    .startsWith("https://www.bundestag.de/resource/blob/").contains("Kapitel_06_03_").endsWith(".pdf");
        }
        assertThat(DePepFederalSecretariesParser.discoverPdfUrl("<html></html>")).isNull();
    }

    @Test
    void failsWhenTheLayoutChanged(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("autre.pdf");
        // PDF valide mais sans le tableau : celui des secrétaires parlementaires (chapitre 6.4 non fourni) est remplacé par un texte brut
        Files.writeString(in, "pas un pdf");

        assertThatThrownBy(() -> new DePepFederalSecretariesParser().parse(new ParserContext("x", in, dir,
                dir.resolve("o.ndjson"), Map.of()))).isInstanceOf(Exception.class);
    }

    @Test
    void cleansMinistryLabelsAndPositionNames() {
        assertThat(DePepFederalSecretariesParser.organization("BM für Digitales und Staats- modernisierung"))
                .isEqualTo("Bundesministerium für Digitales und Staatsmodernisierung");
        assertThat(DePepFederalSecretariesParser.organization("BM des Innern")).isEqualTo("Bundesministerium des Innern");
        assertThat(DePepFederalSecretariesParser.organization("Bundeskanzleramt/ Bundespresseamt")).isEqualTo("Bundeskanzleramt");
        assertThat(DePepFederalSecretariesParser.organization("")).isEqualTo("Gouvernement fédéral");
    }
}
