package com.swiftcheck.aml.plugins.depep.auditcourts;

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

/** Pages du Bundesrechnungshof téléchargées le 4 novembre 2026. */
class AuditPagesTest {

    private static String page(String key) throws IOException {
        try (InputStream in = AuditPagesTest.class.getResourceAsStream("/courts/" + key + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(String key) throws IOException {
        return AuditPages.EXTRACTORS.get(key).apply(PageText.lines(page(key))).stream()
                .map(m -> m.role() + "|" + m.cleanName() + "|" + (m.end() == null ? "" : m.end())).toList();
    }

    @Test
    void currentPresidentAndVicePresident() throws IOException {
        assertThat(read("brh")).containsExactly("Präsident des Bundesrechnungshofes|Ansgar Heveling|",
                "Vizepräsidentin des Bundesrechnungshofes|Klara Geywitz|");
    }

    @Test
    void formerPresidentFromTheSuccessionAnnouncement() throws IOException {
        assertThat(read("brh-succession")).containsExactly("Ancien président du Bundesrechnungshof|Kay Scheller|2026-05-31");
    }

    private static DePepAuditCourtsParser.Fetcher fixtures() throws IOException {
        var config = DePepAuditCourtsParser.loadConfig();
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
    void writesThePresidencyAndTheFormerPresidentStillWithinTwelveMonths(@TempDir Path raw) throws Exception {
        var outcomes = DePepAuditCourtsParser.readAll(DePepAuditCourtsParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepAuditCourtsParser.ID_PREFIX, DePepAuditCourtsParser.TOPIC);

        String message = DePepAuditCourtsParser.convert(outcomes, LocalDate.of(2026, 11, 4), 12, dataset);

        assertThat(message).startsWith("3 personnes lues");
        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
        assertThat(names).containsExactlyInAnyOrder("Ansgar Heveling", "Klara Geywitz", "Kay Scheller");
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }

    @Test
    void aFormerPresidentOlderThanTwelveMonthsIsDropped(@TempDir Path raw) throws Exception {
        var outcomes = DePepAuditCourtsParser.readAll(DePepAuditCourtsParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepAuditCourtsParser.ID_PREFIX, DePepAuditCourtsParser.TOPIC);

        String message = DePepAuditCourtsParser.convert(outcomes, LocalDate.of(2027, 7, 1), 12, dataset);

        assertThat(message).contains("écartés");
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Kay Scheller"))).isFalse();
    }

    @Test
    void failsWhenNothingCanBeRead(@TempDir Path raw) {
        assertThatThrownBy(() -> DePepAuditCourtsParser.readAll(DePepAuditCourtsParser.loadConfig(), url -> {
            throw new IOException("réseau coupé");
        }, raw)).isInstanceOf(IllegalStateException.class);
    }
}
