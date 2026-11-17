package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Lecture complète d'un plan (PDF officiel du BVerwG du 16 novembre 2026), sans réseau. */
class PlanPipelineTest {

    private static byte[] bytes(String file) throws IOException {
        try (InputStream in = PlanPipelineTest.class.getResourceAsStream("/" + file)) {
            return in.readAllBytes();
        }
    }

    @Test
    void findsTheLatestPlanOfTheBgh() throws IOException {
        Map<String, String> pages = Map.of(
                "https://www.bundesgerichtshof.de/DE/DasGericht/Geschaeftsverteilung/geschaeftsverteilung_node.html",
                PlanJudgesTest.resource("plans/bgh-geschaeftsverteilung.html"),
                "https://www.bundesgerichtshof.de/DE/DasGericht/Geschaeftsverteilung/Geschaeftsverteilungsplan2026/geschaeftsverteilungsplan2026_node.html",
                PlanJudgesTest.resource("plans/bgh-plan-2026.html"));
        DePepFederalCourtsParser.CourtConfig bgh = TestParameters.config().courts().stream().filter(c -> c.key().equals("bgh")).findFirst().orElseThrow();

        String pdf = OfficialPlans.discover(bgh.plan().url(), bgh.plan().links(), url -> {
            String page = pages.get(url);
            if (page == null) {
                throw new IOException("URL inattendue " + url);
            }
            return page;
        });

        assertThat(pdf).isEqualTo("https://www.bundesgerichtshof.de/SharedDocs/Downloads/DE/DasGericht/GeschaeftsvertPDF/2026/geschaeftsverteilung2026.pdf?__blob=publicationFile&v=1");
    }

    @Test
    void publishesThePlanJudgesWithOrWithoutFirstName(@TempDir Path raw) throws Exception {
        Map<String, String> p = new HashMap<>(TestParameters.parameters());
        p.put("judgeNames", "[{\"court\":\"bverwg\",\"name\":\"Robert Keller\",\"source\":\"https://www.bverwg.de/jahresbericht_2025.pdf\"}]");
        DePepFederalCourtsParser.Config config = DePepFederalCourtsParser.config(p);
        List<String> calls = new ArrayList<>();
        DePepFederalCourtsParser.Web web = new DePepFederalCourtsParser.Web() {
            @Override
            public String text(String url, long delayMs) throws IOException {
                throw new IOException("hors ligne");
            }

            @Override
            public byte[] bytes(String url, long delayMs) throws IOException {
                calls.add(url);
                if (url.endsWith("gvp_bverwg.pdf")) {
                    return PlanPipelineTest.bytes("plans/bverwg-2026.pdf");
                }
                throw new IOException("hors ligne");
            }
        };

        List<DePepFederalCourtsParser.PlanOutcome> plans = DePepFederalCourtsParser.readPlans(config, web, raw,
                DePepFederalCourtsParser.knownNames(config), 0);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepFederalCourtsParser.ID_PREFIX, DePepFederalCourtsParser.TOPIC);
        String message = DePepFederalCourtsParser.convertPlans(plans, List.of(), dataset);

        assertThat(message).contains("BVerwG 52 (1 avec prénom)").contains("Plans non lus").contains("BGH (ALERT SOURCE_BROKEN");
        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        FtmEntity keller = persons.stream().filter(e -> e.getValues("name").contains("Robert Keller")).findFirst().orElseThrow();
        assertThat(keller.getValues("position")).containsExactly("Vorsitzender Richter am Bundesverwaltungsgericht");
        assertThat(keller.getValues("title")).containsExactly("Dr.");
        FtmEntity schipper = persons.stream().filter(e -> e.getValues("name").contains("Schipper")).findFirst().orElseThrow();
        assertThat(schipper.getValues("gender")).containsExactly("female");
        assertThat(schipper.getValues("notes").iterator().next()).contains("Prénom non trouvé");
        assertThat(raw.resolve("plan-bverwg.pdf")).exists();
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }
}
