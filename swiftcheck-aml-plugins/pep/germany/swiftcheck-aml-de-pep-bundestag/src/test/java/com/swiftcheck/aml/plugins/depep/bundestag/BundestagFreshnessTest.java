package com.swiftcheck.aml.plugins.depep.bundestag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Extrait réel de la page https://www.bundestag.de/services/opendata relevé le 4 novembre 2026. */
class BundestagFreshnessTest {

    private static String resource(String file) throws IOException {
        try (InputStream in = BundestagFreshnessTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void discoversTheVersionedZipAddress() throws IOException {
        assertThat(DePepBundestagParser.discoverZipUrl(resource("opendata-extract.html")))
                .isEqualTo("https://www.bundestag.de/resource/blob/472878/MdB-Stammdaten.zip");
        assertThat(DePepBundestagParser.discoverZipUrl("<html>rien</html>")).isNull();
    }

    @Test
    void readsTheDateOfTheFile() throws IOException {
        assertThat(DePepBundestagParser.standDate(resource("opendata-extract.html"))).isEqualTo(LocalDate.of(2026, 4, 29));
        assertThat(DePepBundestagParser.standDate("<a href=\"x\">autre</a>")).isNull();
    }

    @Test
    void readsTheActiveMembersOfTheLiveIndex() {
        String index = "<mdbs><mdb><mdbID status=\"Aktiv\">2314</mdbID></mdb><mdb><mdbID status=\"Ausgeschieden\">1</mdbID></mdb>"
                + "<mdb><mdbID status=\"Aktiv\">77</mdbID></mdb><mdb><mdbID status=\"Verstorben\">5</mdbID></mdb></mdbs>";

        assertThat(DePepBundestagParser.activeIds(index)).containsExactlyInAnyOrder("2314", "77");
    }
}
