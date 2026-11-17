package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Plans de répartition officiels relevés le 16 novembre 2026, texte extrait par PDFBox avec tri par position. */
class PlanJudgesTest {

    static String resource(String file) throws IOException {
        try (InputStream in = PlanJudgesTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<PlanJudges.PlanJudge> plan(String court, String names) throws IOException {
        List<PlanJudges.PlanJudge> judges = PlanJudges.parse(resource("plans/" + court + "-2026.sorted.txt"), names);
        System.out.println(court + " " + judges.size() + " : " + judges.stream().map(PlanJudges.PlanJudge::displayName).toList());
        return judges;
    }

    @Test
    void readsTheJudgesOfEachPlan() throws IOException {
        assertThat(plan("bgh", "Bundesgerichtshof|BGH")).hasSizeGreaterThan(5);
        assertThat(plan("bverwg", "Bundesverwaltungsgericht|BVerwG")).hasSizeGreaterThan(5);
        assertThat(plan("bfh", "Bundesfinanzhof|BFH")).hasSizeGreaterThan(5);
        assertThat(plan("bag", "Bundesarbeitsgericht|BAG")).hasSizeGreaterThan(5);
        assertThat(plan("bsg", "Bundessozialgericht|BSG")).hasSizeGreaterThan(5);
    }
}
