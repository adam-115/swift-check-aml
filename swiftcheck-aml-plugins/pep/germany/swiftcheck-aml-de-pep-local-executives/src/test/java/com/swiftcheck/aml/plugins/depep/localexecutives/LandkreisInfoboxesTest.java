package com.swiftcheck.aml.plugins.depep.localexecutives;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Réponses réelles de Wikidata et de Wikipédia DE relevées le 16 novembre 2026. */
class LandkreisInfoboxesTest {

    private static String resource(String file) throws IOException {
        try (InputStream in = LandkreisInfoboxesTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listsThe294DistrictArticles() throws Exception {
        List<String> titles = LandkreisInfoboxes.districtTitles(resource("wikipedia-landkreise-list.json"));

        assertThat(titles).hasSize(294);
        assertThat(titles).contains("Städteregion Aachen", "Landkreis Ahrweiler", "Alb-Donau-Kreis", "Region Hannover");
    }

    @Test
    void readsTheHeadOfEachDistrictFromItsInfobox() throws Exception {
        Map<String, String> articles = LandkreisInfoboxes.articles(resource("wikipedia-landkreise-articles.json"));

        LandkreisInfoboxes.Head cham = LandkreisInfoboxes.head("Landkreis Cham", articles.get("Landkreis Cham"));
        LandkreisInfoboxes.Head aachen = LandkreisInfoboxes.head("Städteregion Aachen", articles.get("Städteregion Aachen"));
        LandkreisInfoboxes.Head hannover = LandkreisInfoboxes.head("Region Hannover", articles.get("Region Hannover"));
        LandkreisInfoboxes.Head gifhorn = LandkreisInfoboxes.head("Landkreis Gifhorn", articles.get("Landkreis Gifhorn"));

        // lien avec précision entre parenthèses : seul le texte affiché est gardé
        assertThat(cham.name()).isEqualTo("Christian Schindler");
        assertThat(cham.role()).isEqualTo("Landrat");
        assertThat(cham.party()).isEqualTo("Freie Wähler");
        // collectivités particulières : champ Organwalter et titre propre
        assertThat(aachen.name()).isEqualTo("Tim Grüttemeier");
        assertThat(aachen.role()).isEqualTo("Städteregionsrat");
        assertThat(hannover.name()).isEqualTo("Eva Bender");
        assertThat(hannover.role()).isEqualTo("Regionspräsidentin");
        // référence <ref> après le nom
        assertThat(gifhorn.name()).isEqualTo("Philipp Raulfs");
    }

    @Test
    void cleansInfoboxValues() {
        assertThat(LandkreisInfoboxes.clean("Ludwig Schmid (Politiker, 1977){{!}}Ludwig Schmid")).isEqualTo("Ludwig Schmid");
        assertThat(LandkreisInfoboxes.clean("[[Thomas Karmasin]]<ref>{{Internetquelle |autor=Landratsamt}}</ref>"))
                .isEqualTo("Thomas Karmasin");
        assertThat(LandkreisInfoboxes.clean("Roland Bernhard")).isEqualTo("Roland Bernhard");
        assertThat(LandkreisInfoboxes.head("X", "{{Infobox\n|Landrat = \n}}")).isNull();
    }

    @Test
    void addsTheDistrictHeadsAndToleratesAnUnreadableSource() {
        List<DePepLocalExecutivesParser.LocalExecutive> list = new ArrayList<>();
        DePepLocalExecutivesParser.Fetcher fake = url -> url.contains("action=parse")
                ? resource("wikipedia-landkreise-list.json") : resource("wikipedia-landkreise-articles.json");

        String message = DePepLocalExecutivesParser.addDistrictHeads(list, fake, null);

        // le faux serveur renvoie les mêmes 5 articles pour chaque lot
        assertThat(list).extracting(DePepLocalExecutivesParser.LocalExecutive::cleanName)
                .contains("Christian Schindler", "Tim Grüttemeier", "Eva Bender", "Philipp Raulfs", "Otto Lederer");
        assertThat(list).allMatch(e -> "Landkreis".equals(e.type()));
        assertThat(message).contains("Landräte lus sur 294 Landkreise");

        List<DePepLocalExecutivesParser.LocalExecutive> none = new ArrayList<>();
        String broken = DePepLocalExecutivesParser.addDistrictHeads(none, url -> { throw new IOException("HTTP 503"); }, null);
        assertThat(none).isEmpty();
        assertThat(broken).contains("ALERT SOURCE_BROKEN").contains("HTTP 503");
    }

    @Test
    void keepsOnlyTheCurrentMayorOfEachCity() throws Exception {
        List<DePepLocalExecutivesParser.LocalExecutive> mayors =
                DePepLocalExecutivesParser.parseContent(resource("wikidata-cities.json"), 50_000);

        // Wikidata garde d'anciens maires sans date de fin : un seul maire par ville doit rester (sauf égalité de date)
        Map<String, Long> perCity = new java.util.HashMap<>();
        mayors.forEach(m -> perCity.merge(m.entityName(), 1L, Long::sum));
        assertThat(perCity.values().stream().filter(n -> n > 1).count()).isLessThanOrEqualTo(3);
        assertThat(perCity.get("Göttingen")).isEqualTo(1L);
        assertThat(mayors).filteredOn(m -> m.entityName().equals("Aschaffenburg"))
                .extracting(DePepLocalExecutivesParser.LocalExecutive::cleanName).containsExactly("Markus Schlemmer");
        assertThat(perCity).hasSizeGreaterThan(170);
    }
}
