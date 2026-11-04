package com.swiftcheck.aml.plugins.depep.stateenterprises;

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

/** Pages de direction téléchargées le 4 novembre 2026 (dossier test/resources/enterprises). */
class EnterprisePagesTest {

    private static String page(String name) throws IOException {
        try (InputStream in = EnterprisePagesTest.class.getResourceAsStream("/enterprises/" + name + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(String key, String source, String file) throws IOException {
        return EnterprisePages.EXTRACTORS.get(key + "/" + source).apply(PageText.lines(page(file))).stream()
                .map(p -> p.role() + "|" + p.cleanName() + "|" + (p.start() == null ? "" : p.start())).toList();
    }

    @Test
    void deutscheBahnBoardWithTheirStartDates() throws IOException {
        assertThat(read("deutsche-bahn", "vorstand", "deutsche-bahn-vorstand")).containsExactly(
                "Vorstandsvorsitzende|Evelyn Palla|2025-10-01",
                "Vorstand Finanzen|Michael Obrowski|2026-09-01",
                "Vorstand Regionalverkehr|Harmen van Zijderveld|2025-11-01",
                "Vorstand Güterverkehr|Bernhard Osburg|2025-11-15",
                "Vorstand Personenfernverkehr|Michael Peterson|2022-07-01",
                "Vorstand Personal und Recht|Martin Seiler|2018-01-01");
    }

    @Test
    void kfwBoard() throws IOException {
        assertThat(read("kfw", "vorstand", "kfw-vorstand")).containsExactly(
                "Vorstandsvorsitzender|Stefan Wintels|",
                "Mitglied des Vorstands|Melanie Kehr|",
                "Mitglied des Vorstands|Bernd Loewen|",
                "Mitglied des Vorstands|Susanne Maurenbrecher|",
                "Mitglied des Vorstands|Stefan Peiß|",
                "Mitglied des Vorstands|Brigitte Réthier|");
    }

    @Test
    void telekomBoardAndSupervisoryBoardChair() throws IOException {
        List<String> board = read("deutsche-telekom", "vorstand", "deutsche-telekom-vorstand");
        assertThat(board).hasSize(8);
        assertThat(board.get(0)).isEqualTo("Vorstandsvorsitzender|Timotheus Höttges|");
        List<String> supervisory = read("deutsche-telekom", "aufsichtsrat", "deutsche-telekom-aufsichtsrat");
        assertThat(supervisory).first().isEqualTo("Aufsichtsratsvorsitzender|Frank Appel|2022-04-07");
        assertThat(supervisory.stream().filter(s -> s.startsWith("Aufsichtsratsvorsitzender")).count()).isEqualTo(1);
        assertThat(supervisory).contains("Stellvertretender Aufsichtsratsvorsitzender|Christoph Schmitz-Dethlefsen|2023-11-07");
        assertThat(supervisory.size()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void uniperManagementBoard() throws IOException {
        assertThat(read("uniper", "vorstand", "uniper-vorstand")).containsExactly(
                "Chief Executive Officer (CEO)|Michael D. Lewis|2023-06-01",
                "Chief Financial Officer (CFO)|Christian Barr|2025-11-01",
                "Chief Operating Officer|Holger Kreetz|2023-03-01",
                "Chief Commercial Officer (CCO)|Christian Ohlms|2026-10-01");
    }

    @Test
    void dfsSupervisoryChairAndManagement() throws IOException {
        assertThat(read("dfs", "geschaeftsfuehrung", "dfs-geschaeftsfuehrung")).containsExactly(
                "Aufsichtsratsvorsitzende(r)|Iris Reimold|",
                "Vorsitzender der Geschäftsführung|Arndt Schoenemann|",
                "Geschäftsführer|Andrea Wächter|",
                "Geschäftsführer|Dirk Mahns|",
                "Geschäftsführer|Friedrich-Wilhelm Menge|");
    }

    @Test
    void bundesdruckereiManagement() throws IOException {
        assertThat(read("bundesdruckerei", "geschaeftsfuehrung", "bundesdruckerei-geschaeftsfuehrung")).containsExactly(
                "Geschäftsführung (CEO)|Christian Helfrich|",
                "Geschäftsführung (CSO)|Claudia Thamm|",
                "Geschäftsführung (CTO)|Frederik Blachetta|");
    }

    // ------------------------------------------------------------------ plugin complet

    private static DePepStateEnterprisesParser.Fetcher fixtures() throws IOException {
        var map = java.util.Map.of(
                "deutsche-bahn/vorstand", "deutsche-bahn-vorstand", "kfw/vorstand", "kfw-vorstand",
                "deutsche-telekom/vorstand", "deutsche-telekom-vorstand", "deutsche-telekom/aufsichtsrat", "deutsche-telekom-aufsichtsrat",
                "uniper/vorstand", "uniper-vorstand", "dfs/geschaeftsfuehrung", "dfs-geschaeftsfuehrung",
                "bundesdruckerei/geschaeftsfuehrung", "bundesdruckerei-geschaeftsfuehrung");
        var config = DePepStateEnterprisesParser.loadConfig();
        return url -> {
            for (var e : config.enterprises()) {
                for (var s : e.sources()) {
                    if (s.url().equals(url) && map.containsKey(e.key() + "/" + s.id())) {
                        return page(map.get(e.key() + "/" + s.id()));
                    }
                }
            }
            throw new IOException("connexion refusée");
        };
    }

    @Test
    void readsEverySourceAndReportsDhlAsUnreadable(@TempDir Path raw) throws Exception {
        var outcomes = DePepStateEnterprisesParser.readAll(DePepStateEnterprisesParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepStateEnterprisesParser.ID_PREFIX, DePepStateEnterprisesParser.TOPIC_PEP);

        String message = DePepStateEnterprisesParser.convert(outcomes, LocalDate.of(2026, 11, 4), dataset);

        assertThat(message).contains("Pages non lues").contains("Deutsche Post AG (DHL Group)");
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next())).contains("Evelyn Palla", "Stefan Wintels", "Frank Appel")
                .doesNotContain("Richard Lutz", "Robert Habeck", "Werner Gatzer");
    }

    @Test
    void aPageThatChangedProducesNoPerson(@TempDir Path raw) throws Exception {
        var base = fixtures();
        var fetcher = (DePepStateEnterprisesParser.Fetcher) url -> url.contains("deutschebahn") ? "<html><body>Nouvelle page</body></html>" : base.get(url);

        var outcomes = DePepStateEnterprisesParser.readAll(DePepStateEnterprisesParser.loadConfig(), fetcher, raw);

        var db = outcomes.stream().filter(o -> o.enterprise().key().equals("deutsche-bahn")).findFirst().orElseThrow();
        assertThat(db.persons()).isEmpty();
        assertThat(db.problem()).contains("mise en page a changé");
    }

    @Test
    void failsWhenNothingCanBeRead(@TempDir Path raw) {
        assertThatThrownBy(() -> DePepStateEnterprisesParser.readAll(DePepStateEnterprisesParser.loadConfig(), url -> {
            throw new IOException("réseau coupé");
        }, raw)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucune page de direction lisible");
    }
}
