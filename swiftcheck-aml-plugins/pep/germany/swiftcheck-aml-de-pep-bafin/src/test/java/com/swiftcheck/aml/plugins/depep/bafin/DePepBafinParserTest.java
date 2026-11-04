package com.swiftcheck.aml.plugins.depep.bafin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Page « Direktorium » de bafin.de relevée le 4 novembre 2026. */
class DePepBafinParserTest {

    private static String page() throws IOException {
        try (InputStream in = DePepBafinParserTest.class.getResourceAsStream("/direktorium.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsThePresidentAndTheSixExecutiveDirectors() throws Exception {
        List<DePepBafinParser.Member> members = DePepBafinParser.members(page());

        assertThat(members).extracting(DePepBafinParser.Member::cleanName).containsExactly("Mark Branson", "Birgit Rodolphe",
                "Nikolas Speer", "Silke Deppmeyer", "Rupert Schaefer", "Julia Wiens", "Thorsten Pötzsch");
        assertThat(members.get(0).role()).isEqualTo("Präsident der BaFin");
        assertThat(members.get(1).role()).isEqualTo("Exekutivdirektorin der BaFin");
        assertThat(members.get(1).area()).isEqualTo("Anti-Financial Crime");
        assertThat(members.get(6).title()).isEqualTo("Dr.");
    }

    @Test
    void writesPersonsPositionAndOccupancies(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("in.html");
        Files.writeString(input, page());
        Path out = dir.resolve("out.ndjson");

        ParserResult result = new DePepBafinParser().parse(new ParserContext("de_pep_bafin", input, dir, out, Map.of()));

        assertThat(result.message()).contains("7 membres du Direktorium");
        String all = Files.readString(out);
        assertThat(all).contains("\"name\":[\"Mark Branson\"]").contains("role.pep").contains("gov.financial");
        assertThat(all.lines().filter(l -> l.contains("\"schema\":\"Occupancy\"")).count()).isEqualTo(7);
    }

    @Test
    void failsWhenThePageChanged() {
        assertThatThrownBy(() -> DePepBafinParser.members("<html><body>Nouvelle page</body></html>"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("illisible");
    }

    @Test
    void ignoresTeasersWithoutAPerson() {
        String html = "<h3 class=\"c-teaser__headline h4\">Aufgaben</h3><div class=\"c-teaser__text\"><p>Die BaFin beaufsichtigt Banken.</p>"
                + "<h3 class=\"c-teaser__headline h4\">Präsident</h3><div class=\"c-teaser__text\"><p>Max Muster</p>"
                + "<h3 class=\"c-teaser__headline\">A</h3><div class=\"c-teaser__text\"><p>Anna Beispiel, Exekutivdirektorin</p>"
                + "<h3 class=\"c-teaser__headline\">B</h3><div class=\"c-teaser__text\"><p>Bernd Probe, Exekutivdirektor</p>";

        assertThat(DePepBafinParser.members(html)).extracting(DePepBafinParser.Member::cleanName)
                .containsExactly("Max Muster", "Anna Beispiel", "Bernd Probe");
    }

    @Test
    void usesAnEntityIdPerPerson() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepBafinParser.ID_PREFIX, DePepBafinParser.TOPIC);
        DePepBafinParser.convert(DePepBafinParser.members(page()), dataset);

        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count()).isEqualTo(7);
    }
}
