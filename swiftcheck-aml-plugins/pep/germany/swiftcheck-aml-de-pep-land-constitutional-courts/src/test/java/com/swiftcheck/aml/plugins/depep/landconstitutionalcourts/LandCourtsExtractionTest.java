package com.swiftcheck.aml.plugins.depep.landconstitutionalcourts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/**
 * Les 16 pages officielles téléchargées le 4 novembre 2026 (dossier test/resources/courts) et la liste attendue de leurs
 * membres titulaires, relue à la main sur chaque page. Si une cour change sa mise en page, le test de sa page échoue.
 */
class LandCourtsExtractionTest {

    private static String page(String key) throws IOException {
        try (InputStream in = LandCourtsExtractionTest.class.getResourceAsStream("/courts/" + key + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(String key) throws IOException {
        return LandCourts.EXTRACTORS.get(key).apply(PageText.lines(page(key))).stream()
                .map(m -> m.role() + "|" + m.cleanName() + "|" + (m.endDate() == null ? "" : m.endDate())).toList();
    }

    @Test
    void vgh_bw() throws IOException {
        assertThat(read("vgh-bw")).containsExactly(
                "Präsident|Malte Graßhof|",
                "Vizepräsident|Daniel O'Sullivan|",
                "Mitglied|Jürgen Gneiting|",
                "Mitglied|Sintje Leßner|",
                "Mitglied|Christian Seiler|",
                "Mitglied|Silja Vöneky|",
                "Mitglied|Rami Suliman|",
                "Mitglied|Gabriele Abels|",
                "Mitglied|Gunter Czisch|");
    }

    @Test
    void vgh_by() throws IOException {
        assertThat(read("vgh-by")).containsExactly(
                "Präsident|Hans-Joachim Heßler|",
                "Mitglied|Andrea Breit|",
                "Mitglied|Andreas Dengler|",
                "Mitglied|Christoph Hagspiel|",
                "Mitglied|Paul Heinrichsmeier|",
                "Mitglied|Peter Hilzinger|",
                "Mitglied|Tobias Igloffstein|",
                "Mitglied|Hans-Uwe Kahl|",
                "Mitglied|Mechtild Klein|",
                "Mitglied|Theresia Koch|",
                "Mitglied|Kornelia Kornprobst|",
                "Mitglied|Tatjana Lilienfeld|",
                "Mitglied|Claudia Löffler|",
                "Mitglied|Bettina Meermagen|",
                "Mitglied|Andrea Muthig|",
                "Mitglied|Hans Rötzer|",
                "Mitglied|Andreas Schmitz|",
                "Mitglied|Jörg Singer|",
                "Mitglied|Stefan Tratz|",
                "Mitglied|Martina Usselmann|",
                "Mitglied|Ludwig Wagner|",
                "Mitglied|Margit Zorn|",
                "Mitglied|Andreas Zwerger|",
                "Mitglied|Eva-Maria Hepp|",
                "Mitglied|Alexander König|",
                "Mitglied|Thomas Kreuzer|",
                "Mitglied|Clemens Lückemann|",
                "Mitglied|Nadine Pallas|",
                "Mitglied|Eberhard Rotter|",
                "Mitglied|Bernd Weiß|",
                "Mitglied|Stephan Lorenz|",
                "Mitglied|Robert Mader|",
                "Mitglied|Reinhard Brey|",
                "Mitglied|Wolfram Schubert|",
                "Mitglied|Rüdiger Imgart|",
                "Mitglied|Anne Riethmüller|",
                "Mitglied|Jerzy Montag|",
                "Mitglied|Franz Schindler|");
    }

    @Test
    void vgh_be() throws IOException {
        assertThat(read("vgh-be")).containsExactly(
                "Präsidentin|Ludgera Selting|",
                "Vizepräsident|Björn Retzlaff|",
                "Mitglied|Christian Burholt|",
                "Mitglied|Lucy Chebout|",
                "Mitglied|Ulrike Lembke|",
                "Mitglied|Juliane Pätzold|",
                "Mitglied|Florian Rödl|",
                "Mitglied|Florian Schärdel|",
                "Mitglied|Rosanna Sieveking|");
    }

    @Test
    void vgh_bb() throws IOException {
        assertThat(read("vgh-bb")).containsExactly(
                "Präsident|Markus Möller|",
                "Vizepräsident|Michael Strauß|",
                "Mitglied|Julia Barbara Finck|",
                "Mitglied|Kathleen Heinrich-Reichow|",
                "Mitglied|Christine Kirbach|",
                "Mitglied|Andreas Koch|",
                "Mitglied|Thomas Gerald Müller|",
                "Mitglied|Alexander Richter|",
                "Mitglied|Karen Sokoll|");
    }

    @Test
    void stgh_hb() throws IOException {
        assertThat(read("stgh-hb")).containsExactly(
                "Präsident|Peter Sperlich|",
                "Vizepräsidentin|Sabine Schlacke|",
                "Mitglied|Jens Florstedt|",
                "Mitglied|Julia Heesen|",
                "Mitglied|Pia Lange|",
                "Mitglied|Andreas Fischer-Lescano|",
                "Mitglied|Lilian Stybel|");
    }

    @Test
    void vgh_hh() throws IOException {
        assertThat(read("vgh-hh")).containsExactly(
                "Präsidentin|Birgit Voßkühler|",
                "Vizepräsident|Lars Hummel|",
                "Mitglied|Nikolaus Berger|",
                "Mitglied|Sigrid Boysen|",
                "Mitglied|Carsten Gericke|",
                "Mitglied|Arnim Karthaus|",
                "Mitglied|Elisabeth Kreth|",
                "Mitglied|Andreas Lambiris|",
                "Mitglied|Marc Tully|",
                "Mitglied|Roda Verheyen|");
    }

    @Test
    void stgh_he() throws IOException {
        assertThat(read("stgh-he")).containsExactly(
                "Präsident|Wilhelm Wolf|",
                "Vizepräsidentin|Ute Sacksofsky|",
                "Mitglied|Desirée Dauber|",
                "Mitglied|Steffen Detterbeck|",
                "Mitglied|Ulrich Fachinger|",
                "Mitglied|Jürgen Gasper|",
                "Mitglied|Dirk Liebermann|",
                "Mitglied|Stephanie Rachor|",
                "Mitglied|Frank Richter|",
                "Mitglied|Harald Wack|",
                "Mitglied|Annett Wunder|");
    }

    @Test
    void vgh_mv() throws IOException {
        assertThat(read("vgh-mv")).containsExactly(
                "Präsidentin|Monika Köster|",
                "Vizepräsidentin|Ulrike Lehmann-Wandschneider|",
                "Mitglied|Claus Dieter Classen|",
                "Mitglied|Konstantin Tränkmann|",
                "Mitglied|Nikolaus Hanenkamp|",
                "Mitglied|Dorothea ter Veen|",
                "Mitglied|Alexander Schmidt|");
    }

    @Test
    void stgh_ni() throws IOException {
        assertThat(read("stgh-ni")).containsExactly(
                "Präsident|Wilhelm Mestwerdt|",
                "Vizepräsidentin|Anke van Hove|",
                "Mitglied|Hannelore Kaiser|",
                "Mitglied|Hermann Butzer|",
                "Mitglied|Thomas Veen|",
                "Mitglied|Lioba Huss|",
                "Mitglied|Stefanie Otte|",
                "Mitglied|Frank Bornemann|",
                "Mitglied|Jann Berghaus|");
    }

    @Test
    void vgh_nw() throws IOException {
        assertThat(read("vgh-nw")).containsExactly(
                "Präsidentin|Dauner-Lieb|",
                "Vizepräsident|Andreas Heusch|",
                "Mitglied|Dirk Gilberg|",
                "Mitglied|Bernd Grzeszick|",
                "Mitglied|Claudio Nedden-Boeger|",
                "Mitglied|Matthias Röhl|",
                "Mitglied|Joachim Wieland|");
    }

    @Test
    void vgh_rp() throws IOException {
        assertThat(read("vgh-rp")).containsExactly(
                "Präsident|Lars Brocker|",
                "Mitglied|Bernhard Thurn|",
                "Mitglied|Thomas Henrichs|",
                "Mitglied|Andreas Hammer|",
                "Mitglied|Astrid Häfner|",
                "Mitglied|Ulrike Müller-Rospert|",
                "Mitglied|Jens Hoffmann|",
                "Mitglied|Benita Marker|");
    }

    @Test
    void vgh_sl() throws IOException {
        assertThat(read("vgh-sl")).containsExactly(
                "Präsident|Roland Rixecker|2026-07-15",
                "Vizepräsident|Rudolf Wendt|2026-07-15",
                "Mitglied|Roberto Bartone|2030-06-12",
                "Mitglied|Claudia Witsch|2027-11-17",
                "Mitglied|Corinna Philipp|2031-03-18",
                "Mitglied|Hans-Georg Warken|2026-07-15",
                "Mitglied|Hans-Peter Freymann|2027-11-17");
    }

    @Test
    void vgh_sn() throws IOException {
        assertThat(read("vgh-sn")).containsExactly(
                "Präsident|Matthias Grünberg|",
                "Vizepräsident|Andreas Wahl|",
                "Mitglied|Simone Herberger|",
                "Mitglied|Markus Jäger|",
                "Mitglied|Dirk Kirst|",
                "Mitglied|Elisa Hoven|",
                "Mitglied|Klaus Schurig|",
                "Mitglied|Stefan Ansgar Strewe|",
                "Mitglied|Arnd Uhle|");
    }

    @Test
    void vgh_st() throws IOException {
        assertThat(read("vgh-st")).containsExactly(
                "Präsident|Uwe Wegehaupt|",
                "Mitglied|Michael Germann|",
                "Mitglied|Volker Buchloh|",
                "Mitglied|Frank Meyer|",
                "Mitglied|Silke Schindler|",
                "Mitglied|Detlef Eckert|");
    }

    @Test
    void vgh_sh() throws IOException {
        assertThat(read("vgh-sh")).containsExactly(
                "Präsident|Christoph Brüning|",
                "Vizepräsident|Carsten Löbbert|",
                "Mitglied|Achim Theis|",
                "Mitglied|Nele Matz-Lück|",
                "Mitglied|Frank Guido Rose|",
                "Mitglied|Sabine Wudtke|",
                "Mitglied|Marc Petit|");
    }

    @Test
    void vgh_th() throws IOException {
        assertThat(read("vgh-th")).containsExactly(
                "Präsident|Klaus von der Weiden|",
                "Vizepräsident|Lars Schmidt|",
                "Mitglied|Barbara Burkert|",
                "Mitglied|Jörg Geibert|",
                "Mitglied|Klaus Hinkel|",
                "Mitglied|Anika Klafki|",
                "Mitglied|Christoph Ohler|",
                "Mitglied|Jens Petermann|");
    }

    // ------------------------------------------------------------------ plugin complet, sans aucune donnée locale

    /** Serveur simulé : chaque adresse de la configuration renvoie la page téléchargée le 4 novembre 2026. */
    private static DePepLandConstitutionalCourtsParser.Fetcher fixtures() throws IOException {
        DePepLandConstitutionalCourtsParser.Config config = DePepLandConstitutionalCourtsParser.loadConfig();
        return url -> {
            for (var c : config.courts()) {
                if (c.url().equals(url)) {
                    return page(c.key());
                }
            }
            throw new IOException("URL inattendue " + url);
        };
    }

    @Test
    void readsTheSixteenCourtsFromTheirPagesAndKeepsTheRawFiles(@TempDir Path raw) throws Exception {
        var config = DePepLandConstitutionalCourtsParser.loadConfig();
        var outcomes = DePepLandConstitutionalCourtsParser.readAll(config, fixtures(), raw);

        assertThat(config.courts()).hasSize(16);
        assertThat(outcomes).allMatch(o -> o.problem() == null);
        assertThat(outcomes.stream().mapToInt(o -> o.members().size()).sum()).isEqualTo(161);
        try (var files = Files.list(raw)) {
            assertThat(files.count()).isEqualTo(16);
        }
    }

    @Test
    void aCourtWhosePageChangedIsReportedAndProducesNoPerson(@TempDir Path raw) throws Exception {
        var config = DePepLandConstitutionalCourtsParser.loadConfig();
        String broken = config.courts().get(0).url();
        var base = fixtures();
        DePepLandConstitutionalCourtsParser.Fetcher fetcher = url -> url.equals(broken) ? "<html><body>Nouvelle page</body></html>" : base.get(url);

        var outcomes = DePepLandConstitutionalCourtsParser.readAll(config, fetcher, raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepLandConstitutionalCourtsParser.ID_PREFIX, DePepLandConstitutionalCourtsParser.TOPIC);
        String message = DePepLandConstitutionalCourtsParser.convert(outcomes, LocalDate.of(2026, 11, 4), dataset);

        assertThat(message).contains("Cours non lues").contains("Baden-Württemberg").contains("la mise en page a changé");
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Malte Graßhof"))).isFalse();
    }

    @Test
    void anUnreachableCourtIsReportedWithoutFallbackToAnyEmbeddedName(@TempDir Path raw) throws Exception {
        var config = DePepLandConstitutionalCourtsParser.loadConfig();
        var base = fixtures();
        String down = config.courts().get(1).url();
        DePepLandConstitutionalCourtsParser.Fetcher fetcher = url -> {
            if (url.equals(down)) {
                throw new IOException("HTTP 404");
            }
            return base.get(url);
        };

        var outcomes = DePepLandConstitutionalCourtsParser.readAll(config, fetcher, raw);

        assertThat(outcomes.get(1).problem()).isEqualTo("HTTP 404");
        assertThat(outcomes.get(1).members()).isEmpty();
    }

    @Test
    void failsWhenNoCourtCanBeRead(@TempDir Path raw) throws Exception {
        var config = DePepLandConstitutionalCourtsParser.loadConfig();

        assertThatThrownBy(() -> DePepLandConstitutionalCourtsParser.readAll(config, url -> {
            throw new IOException("réseau coupé");
        }, raw)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucune cour constitutionnelle lisible");
    }

    @Test
    void writesPersonsPositionsAndEndedTermsOfSaarland(@TempDir Path raw) throws Exception {
        var outcomes = DePepLandConstitutionalCourtsParser.readAll(DePepLandConstitutionalCourtsParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepLandConstitutionalCourtsParser.ID_PREFIX, DePepLandConstitutionalCourtsParser.TOPIC);

        String message = DePepLandConstitutionalCourtsParser.convert(outcomes, LocalDate.of(2026, 11, 4), dataset);

        assertThat(message).startsWith("161 membres lus sur 16 cours constitutionnelles.");
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        long ended = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("status").contains("ended")).count();
        assertThat(ended).isEqualTo(3); // Saarland : trois mandats dont la page indique la fin au 15/07/2026
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Dauner-Lieb") && e.getValues("notes").stream().anyMatch(n -> n.contains("Prénom absent")))).isTrue();
    }
}
