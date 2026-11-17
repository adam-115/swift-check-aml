package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Complément Wikipédia : listes « Liste der Richter am … » relevées le 15 novembre 2026, plans officiels et sources
 * officielles de prénoms relevés le 16 novembre 2026.
 */
class WikipediaComplementTest {

    /** Réponse de l'API MediaWiki pour un wikitext. */
    private static String apiResponse(String court) throws IOException {
        return new ObjectMapper().createObjectNode().set("parse", new ObjectMapper().createObjectNode()
                .set("wikitext", new ObjectMapper().createObjectNode().put("*", PlanJudgesTest.resource("wikipedia/" + court + ".wikitext"))))
                .toString();
    }

    @Test
    void completesOnlyTheJudgesOfThePlanWithoutAnOfficialFirstName() throws Exception {
        List<NamedJudges.NamedJudge> official = NamedJudgesTest.allSources();
        DePepFederalCourtsParser.Config config = TestParameters.config();
        DePepFederalCourtsParser.Web web = new DePepFederalCourtsParser.Web() {
            @Override
            public String text(String url, long delayMs) throws IOException {
                for (String court : List.of("bgh", "bverwg", "bfh", "bag", "bsg")) {
                    DePepFederalCourtsParser.CourtConfig c = config.courts().stream().filter(x -> x.key().equals(court)).findFirst().orElseThrow();
                    if (url.equals(WikipediaJudges.apiUrl(c.wikipedia()))) {
                        return apiResponse(court);
                    }
                }
                throw new IOException("URL inattendue " + url);
            }

            @Override
            public byte[] bytes(String url, long delayMs) throws IOException {
                throw new IOException("hors ligne");
            }
        };
        for (DePepFederalCourtsParser.CourtConfig c : config.courts()) {
            if (c.plan() == null) {
                continue;
            }
            List<PlanJudges.PlanJudge> plan = PlanJudges.parse(PlanJudgesTest.resource("plans/" + c.key() + "-2026.sorted.txt"),
                    c.name() + "|" + c.acronym());
            List<JudgeRoster.Judge> before = JudgeRoster.match(c.key(), plan, official);
            List<JudgeRoster.Judge> after = DePepFederalCourtsParser.completeFromWikipedia(c, before, web, 0, null);

            long officialNames = after.stream().filter(j -> j.firstName() != null && !WikipediaJudges.isWikipedia(j.nameSource())).count();
            long wiki = after.stream().filter(j -> WikipediaJudges.isWikipedia(j.nameSource())).count();
            System.out.println("HYBRID " + c.key() + " " + after.size() + " juges : " + officialNames + " prénoms officiels, " + wiki
                    + " par Wikipédia, sans prénom : " + after.stream().filter(j -> j.firstName() == null).map(j -> j.plan().displayName()).toList());

            // Wikipédia n'ajoute aucun juge et ne remplace aucun prénom officiel
            assertThat(after).hasSameSizeAs(before);
            assertThat(officialNames).isEqualTo(before.stream().filter(j -> j.firstName() != null).count());
        }
    }
}
