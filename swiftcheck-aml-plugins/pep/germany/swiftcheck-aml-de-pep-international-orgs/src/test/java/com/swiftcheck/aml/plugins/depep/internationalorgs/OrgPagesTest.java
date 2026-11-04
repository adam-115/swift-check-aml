package com.swiftcheck.aml.plugins.depep.internationalorgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Pages de l'ESO, de l'EMBL et de l'ITLOS téléchargées le 4 novembre 2026. */
class OrgPagesTest {

    private static String page(String key) throws IOException {
        try (InputStream in = OrgPagesTest.class.getResourceAsStream("/orgs/" + key + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(String key) throws IOException {
        return OrgPages.EXTRACTORS.get(key).apply(PageText.lines(page(key))).stream()
                .map(m -> m.role() + "|" + m.cleanName() + "|" + (m.start() == null ? "" : m.start()) + "|" + (m.end() == null ? "" : m.end())).toList();
    }

    @Test
    void esoNewDirectorGeneralAndTheOneWhoLeftThatSummer() throws IOException {
        List<String> eso = read("eso");
        assertThat(eso.get(0)).isEqualTo("Generaldirektor der ESO|Andreas Kaufer|2026-09-01|");
        assertThat(eso).contains("Ehemaliger Generaldirektor der ESO|Xavier Barcons|2017-01-01|2026-08-31");
        assertThat(eso.size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void emblDirectorGeneralStartedOn31March2026() throws IOException {
        assertThat(read("embl")).containsExactly("Generaldirektor des EMBL|Tony Hyman|2026-03-31|");
    }

    @Test
    void itlosHasThePresidentTheVicePresidentAndNineteenJudges() throws IOException {
        List<String> itlos = read("itlos");
        assertThat(itlos).hasSize(21);
        assertThat(itlos.get(0)).startsWith("Präsident des Internationalen Seegerichtshofs|Maurice K. Kamga");
        assertThat(itlos.get(1)).startsWith("Vizepräsident des Internationalen Seegerichtshofs|Frida María Armas Pfirter");
        assertThat(itlos.get(20)).contains("George Rodrigo Bandeira Galindo");
        assertThat(itlos.stream().filter(s -> s.startsWith("Richter am")).count()).isEqualTo(19);
    }

    private static DePepInternationalOrgsParser.Fetcher fixtures() throws IOException {
        var config = DePepInternationalOrgsParser.loadConfig();
        return url -> {
            for (var c : config.courts()) {
                if (c.url().equals(url)) {
                    return page(c.key());
                }
            }
            throw new IOException("URL inattendue");
        };
    }

    @Test
    void writesTheDatasetAndDropsDirectorsWhoLeftLongAgo(@TempDir Path raw) throws Exception {
        var outcomes = DePepInternationalOrgsParser.readAll(DePepInternationalOrgsParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepInternationalOrgsParser.ID_PREFIX, DePepInternationalOrgsParser.TOPIC);

        String message = DePepInternationalOrgsParser.convert(outcomes, LocalDate.of(2026, 11, 4), 12, dataset);

        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
        assertThat(names).contains("Andreas Kaufer", "Xavier Barcons", "Tony Hyman", "Maurice K. Kamga")
                .doesNotContain("Edith Heard", "Tomas Zeeuw");
        assertThat(message).contains("écartés");
        assertThat(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("status").contains("ended")).count())
                .isEqualTo(1);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }

    @Test
    void failsWhenNothingCanBeRead(@TempDir Path raw) {
        assertThatThrownBy(() -> DePepInternationalOrgsParser.readAll(DePepInternationalOrgsParser.loadConfig(), url -> {
            throw new IOException("réseau coupé");
        }, raw)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucune organisation internationale lisible");
    }
}
