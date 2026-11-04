package com.swiftcheck.aml.plugins.lupep.politicalparties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/**
 * Réponse réelle de Wikidata (SPARQL) pour les partis politiques du Luxembourg, relevée le 4 novembre 2026.
 * Aucune liste de dirigeants n'est embarquée dans le plugin : tout vient de cette réponse, téléchargée à chaque exécution.
 */
class LuPepPoliticalPartiesParserTest {

    private static LuPepPoliticalPartiesParser parserWithFixture() {
        return new LuPepPoliticalPartiesParser((endpoint, country, raw) -> {
            try (InputStream in = LuPepPoliticalPartiesParserTest.class.getResourceAsStream("/leaders.json")) {
                Files.write(raw.resolve("leaders.json"), in.readAllBytes());
            }
        });
    }

    private static ParserContext context(Path dir, Map<String, String> params) {
        // wikidataEndpoint vide : pas de recherche de photos pendant le test
        return new ParserContext("lu_pep_political_parties", null, dir, dir.resolve("out.ndjson"), params);
    }

    @Test
    void readsTheLeadersOfTheElevenPartiesFromWikidata(@TempDir Path dir) throws Exception {
        ParserResult r = parserWithFixture().parse(context(dir, Map.of("wikidataEndpoint", "")));

        assertThat(r.message()).contains("11 partis politiques luxembourgeois dans Wikidata").contains("13 dirigeants");
        String all = Files.readString(dir.resolve("out.ndjson"));
        assertThat(all).contains("\"name\":[\"Luc Frieden\"]", "\"name\":[\"Carole Hartmann\"]", "\"name\":[\"Francine Closener\"]",
                "\"name\":[\"Dan Biancalana\"]").contains("role.pep").contains("\"country\":[\"lu\"]");
    }

    @Test
    void publishesNothingWhenWikidataCannotBeReached(@TempDir Path dir) {
        LuPepPoliticalPartiesParser failing = new LuPepPoliticalPartiesParser((endpoint, country, raw) -> {
            throw new java.io.IOException("réseau coupé");
        });

        assertThatThrownBy(() -> failing.parse(context(dir, Map.of()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotReuseAFileLeftInTheWorkFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("leaders.json"), "{\"results\":{\"bindings\":[]}}");

        ParserResult r = parserWithFixture().parse(context(dir, Map.of("wikidataEndpoint", "")));

        assertThat(r.message()).contains("13 dirigeants"); // le fichier laissé a été remplacé par le téléchargement
    }

    @Test
    void embedsNoPersonalDataInTheJar() {
        assertThat(LuPepPoliticalPartiesParser.class.getResource("/partis_politiques_luxembourg.csv")).isNull();
        assertThat(List.of("Luc Frieden", "Carole Hartmann")).allMatch(n -> {
            try (InputStream in = LuPepPoliticalPartiesParser.class.getResourceAsStream("/" + n)) {
                return in == null;
            } catch (java.io.IOException e) {
                return true;
            }
        });
    }
}
