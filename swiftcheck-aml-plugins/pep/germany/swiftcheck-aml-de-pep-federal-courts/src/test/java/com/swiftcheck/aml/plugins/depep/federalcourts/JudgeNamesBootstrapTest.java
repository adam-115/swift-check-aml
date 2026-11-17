package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Prépare le paramètre {@code judgeNames} à partir de communiqués officiels déjà téléchargés (un dossier par cour,
 * chaque fichier commençant par {@code <!-- adresse -->}). Lancé à la main :
 * {@code mvn test -Dtest=JudgeNamesBootstrapTest -Dboot.dir=… -Dboot.out=…}. Seuls les juges du plan sans prénom
 * dans les autres sources sont écrits, avec l'adresse du communiqué qui donne le prénom.
 */
@EnabledIfSystemProperty(named = "boot.dir", matches = ".+")
class JudgeNamesBootstrapTest {

    @Test
    void writesTheJudgeNamesParameter() throws Exception {
        Path dir = Path.of(System.getProperty("boot.dir"));
        List<NamedJudges.NamedJudge> base = NamedJudgesTest.allSources();
        List<Map<String, String>> out = new ArrayList<>();
        for (NamedJudges.Court c : NamedJudgesTest.COURTS) {
            Path courtDir = dir.resolve(c.key());
            List<NamedJudges.NamedJudge> found = new ArrayList<>();
            if (Files.isDirectory(courtDir)) {
                try (Stream<Path> files = Files.list(courtDir)) {
                    for (Path f : files.sorted().toList()) {
                        String html = Files.readString(f, StandardCharsets.UTF_8);
                        String url = html.startsWith("<!-- ") ? html.substring(5, html.indexOf(" -->")) : f.toString();
                        found.addAll(NamedJudges.fromHtml(html, url, List.of(c)));
                    }
                }
            }
            List<PlanJudges.PlanJudge> plan = PlanJudges.parse(PlanJudgesTest.resource("plans/" + c.key() + "-2026.sorted.txt"),
                    c.name() + "|" + c.acronym());
            List<JudgeRoster.Judge> before = JudgeRoster.match(c.key(), plan, base);
            List<NamedJudges.NamedJudge> all = new ArrayList<>(base);
            all.addAll(found);
            List<JudgeRoster.Judge> after = JudgeRoster.match(c.key(), plan, all);
            int added = 0;
            for (JudgeRoster.Judge j : after) {
                boolean wasMissing = before.stream().anyMatch(b -> b.plan().equals(j.plan()) && b.firstName() == null);
                if (wasMissing && j.firstName() != null) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("court", c.key());
                    entry.put("name", j.fullName());
                    entry.put("source", j.nameSource());
                    out.add(entry);
                    added++;
                }
            }
            System.out.println("BOOT " + c.key() + " +" + added + " ; toujours sans prénom : "
                    + after.stream().filter(j -> j.firstName() == null).map(j -> j.plan().displayName()).toList());
        }
        Files.writeString(Path.of(System.getProperty("boot.out")), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(out),
                StandardCharsets.UTF_8);
    }
}
