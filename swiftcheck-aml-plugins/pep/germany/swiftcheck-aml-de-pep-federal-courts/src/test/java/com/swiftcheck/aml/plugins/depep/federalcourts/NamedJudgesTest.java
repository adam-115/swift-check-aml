package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Communiqués officiels d'élection (BMJV 2018-2026, BMAS 2014-2025) et rapports annuels 2025 (BVerwG, BAG, BSG)
 * relevés le 16 novembre 2026.
 */
class NamedJudgesTest {

    static final List<NamedJudges.Court> COURTS = List.of(
            new NamedJudges.Court("bgh", "Bundesgerichtshof", "BGH"),
            new NamedJudges.Court("bverwg", "Bundesverwaltungsgericht", "BVerwG"),
            new NamedJudges.Court("bfh", "Bundesfinanzhof", "BFH"),
            new NamedJudges.Court("bag", "Bundesarbeitsgericht", "BAG"),
            new NamedJudges.Court("bsg", "Bundessozialgericht", "BSG"));

    static List<NamedJudges.NamedJudge> allSources() throws IOException, URISyntaxException {
        List<NamedJudges.NamedJudge> out = new ArrayList<>();
        for (String dir : List.of("/press", "/reports")) {
            try (Stream<Path> files = Files.list(Path.of(NamedJudgesTest.class.getResource(dir).toURI()))) {
                for (Path f : files.sorted().toList()) {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    String name = f.getFileName().toString();
                    out.addAll(name.endsWith(".html") ? NamedJudges.fromHtml(content, name, COURTS)
                            : NamedJudges.fromText(content, name, COURTS));
                }
            }
        }
        return out;
    }

    private static List<JudgeRoster.Judge> roster(NamedJudges.Court c, List<NamedJudges.NamedJudge> named) throws IOException {
        List<PlanJudges.PlanJudge> plan = PlanJudges.parse(PlanJudgesTest.resource("plans/" + c.key() + "-2026.sorted.txt"),
                c.name() + "|" + c.acronym());
        return JudgeRoster.match(c.key(), plan, named);
    }

    @Test
    void completesThePlanJudgesWithTheirFirstNames() throws Exception {
        List<NamedJudges.NamedJudge> named = allSources();
        for (NamedJudges.Court c : COURTS) {
            List<JudgeRoster.Judge> roster = roster(c, named);
            long complete = roster.stream().filter(j -> j.firstName() != null).count();
            Files.createDirectories(Path.of("target"));
            Files.write(Path.of("target", "missing-" + c.key() + ".txt"), roster.stream().filter(j -> j.firstName() == null)
                    .map(j -> j.plan().lastName()).toList(), StandardCharsets.UTF_8);
            System.out.println("ROSTER " + c.key() + " " + complete + "/" + roster.size() + " complets : "
                    + roster.stream().filter(j -> j.firstName() != null).map(JudgeRoster.Judge::fullName).toList()
                    + " ; sans prénom : " + roster.stream().filter(j -> j.firstName() == null)
                    .map(j -> j.plan().displayName() + " (" + j.missing() + ")").toList());
        }
    }

    @Test
    void readsAnAppointmentSentence() {
        String html = "<p>Der Bundespräsident hat Vorsitzenden Richter am Kammergericht Gregor Herb zum Richter am Bundesgerichtshof "
                + "und Richterin am Oberlandesgericht Dr. Jana Zapf zur Richterin am Bundesgerichtshof ernannt.</p>"
                + "<p>Antje Weihs ist heute vom Bundespräsidenten zur Richterin am Bundesfinanzhof ernannt worden.</p>";
        List<NamedJudges.NamedJudge> named = NamedJudges.fromHtml(html, "pm", COURTS);
        assertThat(named).extracting(NamedJudges.NamedJudge::text).containsExactlyInAnyOrder("Gregor Herb", "Jana Zapf", "Antje Weihs");
    }

    @Test
    void repairsTheTextOfAnnualReports() {
        assertThat(NamedJudges.repair("Ri’nBVerwG Heidi Stengelhofen- Weiß ; RiBVerwG Hans-J örg Holtbrügge ; Rich- ter"))
                .isEqualTo("Ri’nBVerwG Heidi Stengelhofen-Weiß ; RiBVerwG Hans-Jörg Holtbrügge ; Richter");
        assertThat(JudgeRoster.firstNameFor("Keller", List.of("Robert", "Keller", "von", "Flughäfen"))).isEqualTo("Robert");
        assertThat(JudgeRoster.firstNameFor("von Selle", List.of("Dirk", "von", "Selle"))).isEqualTo("Dirk");
    }
}
