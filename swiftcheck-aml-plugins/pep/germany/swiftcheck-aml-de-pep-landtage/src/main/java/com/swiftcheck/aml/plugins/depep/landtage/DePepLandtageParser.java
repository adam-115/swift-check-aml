package com.swiftcheck.aml.plugins.depep.landtage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_landtage</b> – Députés des 16 parlements régionaux des Länder (Landtage, Bürgerschaften, Abgeordnetenhaus).
 * <p>
 * Catégorie <b>DE-b</b> (hors liste notifiée). Source : API abgeordnetenwatch.de (CC0), lue à chaque exécution par
 * législature, avec les dates de mandat. Les parlements, leur nom et leurs législatures viennent de l'API : aucune
 * liste n'est embarquée dans le plugin. Un député sorti compte pendant la durée de rétention (12 mois par défaut).
 */
@Extension
public class DePepLandtageParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLandtageParser.class);

    static final String ID_PREFIX = "de-pep-landtage";
    static final String TOPIC = "role.pep";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Parlements des Länder)";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");
    /** Parlements de l'API qui ne sont pas des Landtage : lus par d'autres plugins. */
    private static final Set<String> NOT_LAND = Set.of("Bundestag", "EU-Parlament");

    /** Un parlement régional lu dans l'API. */
    record Land(String key, String state, String organization) {
    }

    record LiveReport(List<AbgeordnetenwatchApi.Mandate> mandates, Map<String, Land> lands, Map<Long, Integer> birthYears, String message) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun dossier de données brutes.");
        }
        Files.createDirectories(ctx.rawDir());
        int months = intParam(p, "monthsAfterEnd", 12);
        long delay = intParam(p, "delayMs", 300);
        AbgeordnetenwatchApi.Fetcher fetcher = AbgeordnetenwatchApi.http(USER_AGENT, delay);
        LocalDate today = LocalDate.now();
        LiveReport report = live(p, ctx.rawDir(), fetcher, today, months);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convertMandates(report.mandates(), report.lands(), report.birthYears(), today, months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        try {
            return Integer.parseInt(p.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String slug(String label) {
        String s = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Lit l'API par législature : liste des parlements, législatures actives de chacun, mandats par pages de 100, puis
     * années de naissance (un appel par personne, à chaque exécution). Chaque réponse est conservée dans {@code rawDir}.
     */
    static LiveReport live(Map<String, String> p, Path rawDir, AbgeordnetenwatchApi.Fetcher fetcher, LocalDate today, int months)
            throws Exception {
        String base = p.getOrDefault("endpoint", AbgeordnetenwatchApi.DEFAULT_ENDPOINT).trim();
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        ObjectMapper mapper = new ObjectMapper();

        String parliamentsJson = fetcher.get(base + "/parliaments?range_end=100");
        Files.writeString(rawDir.resolve("parliaments.json"), parliamentsJson, StandardCharsets.UTF_8);

        List<AbgeordnetenwatchApi.Mandate> mandates = new ArrayList<>();
        Map<String, Land> lands = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int periodCount = 0;
        for (AbgeordnetenwatchApi.Parliament parliament : AbgeordnetenwatchApi.parliaments(parliamentsJson)) {
            if (NOT_LAND.contains(parliament.label())) {
                continue;
            }
            Land land = new Land(slug(parliament.label()), parliament.label(), parliament.longLabel());
            lands.put(land.key(), land);
            try {
                String periodsJson = fetcher.get(base + "/parliament-periods?parliament=" + parliament.id() + "&range_end=50");
                Files.writeString(rawDir.resolve("periods-" + land.key() + ".json"), periodsJson, StandardCharsets.UTF_8);
                for (AbgeordnetenwatchApi.Period period : AbgeordnetenwatchApi.activePeriods(
                        AbgeordnetenwatchApi.periods(periodsJson), today, months)) {
                    periodCount++;
                    int start = 0;
                    int total = Integer.MAX_VALUE;
                    int page = 0;
                    while (start < total) {
                        String json = fetcher.get(base + "/candidacies-mandates?parliament_period=" + period.id()
                                + "&type=mandate&range_start=" + start + "&range_end=" + (start + AbgeordnetenwatchApi.PAGE_SIZE));
                        Files.writeString(rawDir.resolve("mandates-" + land.key() + "-" + period.id() + "-" + page + ".json"),
                                json, StandardCharsets.UTF_8);
                        AbgeordnetenwatchApi.Page result = AbgeordnetenwatchApi.mandatePage(json);
                        total = result.total();
                        for (JsonNode item : result.items()) {
                            mandates.add(AbgeordnetenwatchApi.mandate(item, land.key(), period));
                        }
                        if (result.items().isEmpty()) {
                            break;
                        }
                        start += AbgeordnetenwatchApi.PAGE_SIZE;
                        page++;
                    }
                }
            } catch (java.io.IOException e) {
                failures.add(land.state() + " (" + e.getMessage() + ")");
                log.warn("Landtag {} non lu : {}", land.state(), e.getMessage());
            }
        }
        if (mandates.isEmpty()) {
            throw new IllegalStateException("Aucun mandat lu dans l'API abgeordnetenwatch"
                    + (failures.isEmpty() ? "." : " : " + String.join(", ", failures)));
        }

        // Années de naissance : un appel par personne, à chaque exécution (aucun cache d'un run à l'autre).
        Map<Long, Integer> births = new HashMap<>();
        boolean wantBirths = !"false".equalsIgnoreCase(p.get("birthYears"));
        int budget = intParam(p, "maxPoliticianCalls", 3000);
        int fetched = 0;
        if (wantBirths) {
            Set<Long> seen = new HashSet<>();
            for (AbgeordnetenwatchApi.Mandate m : mandates) {
                if (!seen.add(m.politicianId()) || fetched >= budget) {
                    continue;
                }
                try {
                    Integer year = AbgeordnetenwatchApi.birthYear(fetcher.get(base + "/politicians/" + m.politicianId()));
                    births.put(m.politicianId(), year == null ? 0 : year);
                    fetched++;
                } catch (java.io.IOException e) {
                    log.warn("Année de naissance de {} non lue : {}", m.name(), e.getMessage());
                    break; // l'API refuse ou tombe : on garde ce qui est lu
                }
            }
            Map<String, Integer> out = new java.util.TreeMap<>();
            births.forEach((k, v) -> out.put(String.valueOf(k), v));
            Files.writeString(rawDir.resolve("politicians.json"), mapper.writeValueAsString(out), StandardCharsets.UTF_8);
        }

        Map<String, Integer> perLand = new LinkedHashMap<>();
        for (AbgeordnetenwatchApi.Mandate m : mandates) {
            perLand.merge(m.parliamentKey(), 1, Integer::sum);
        }
        long known = mandates.stream().map(AbgeordnetenwatchApi.Mandate::politicianId).distinct()
                .filter(id -> births.getOrDefault(id, 0) > 0).count();
        String message = mandates.size() + " mandats lus sur " + periodCount + " législatures de " + perLand.size()
                + " parlements ; années de naissance connues pour " + known + " personnes."
                + (failures.isEmpty() ? "" : " Parlements non lus : " + String.join(", ", failures) + ".");
        return new LiveReport(mandates, lands, births, message);
    }

    /** Écrit les mandats dans la fenêtre de rétention : en cours, ou terminés depuis moins de {@code months} mois. */
    static int convertMandates(List<AbgeordnetenwatchApi.Mandate> mandates, Map<String, Land> lands, Map<Long, Integer> births,
                               LocalDate today, int months, FtmDatasetBuilder dataset) {
        LocalDate cutoff = today.minusMonths(months);
        Map<Long, FtmEntity> persons = new LinkedHashMap<>();
        Set<String> positions = new HashSet<>();
        int written = 0;
        for (AbgeordnetenwatchApi.Mandate m : mandates) {
            Land land = lands.get(m.parliamentKey());
            LocalDate end = m.end() != null ? m.end() : (m.periodEnd() != null && m.periodEnd().isBefore(today) ? m.periodEnd() : null);
            if (land == null || (end != null && end.isBefore(cutoff))) {
                continue;
            }
            String positionName = "Mitglied des Landtages (" + land.state() + ")";
            FtmEntity position = dataset.newEntity("Position", land.key() + "-mp");
            if (positions.add(land.key())) {
                position.addValue("name", positionName);
                position.addValue("country", "de");
                position.addValue("organization", land.organization());
                position.addValue("topics", "gov.national");
                position.addValue("topics", "gov.state");
                dataset.addEntity(position);
            }

            String title = null;
            String cleanName = m.name();
            Matcher tm = ACADEMIC_TITLE.matcher(m.name());
            if (tm.find()) {
                title = tm.group(0).trim();
                cleanName = m.name().substring(tm.end()).trim();
            }
            FtmEntity person = persons.get(m.politicianId());
            if (person == null) {
                person = dataset.newEntity("Person", "aw", String.valueOf(m.politicianId()));
                person.addValue("name", cleanName);
                person.addValue("title", title);
                String[] parts = cleanName.split("\\s+", 2);
                person.addValue("firstName", parts[0]);
                if (parts.length > 1) {
                    person.addValue("lastName", parts[1]);
                }
                person.addValue("country", "de");
                Integer year = births.get(m.politicianId());
                if (year != null && year > 0) {
                    person.addValue("birthDate", String.valueOf(year));
                }
                person.addValue("sourceUrl", m.profileUrl());
                if (m.party() != null && !m.party().isBlank()) {
                    person.addValue("political", m.party());
                }
                persons.put(m.politicianId(), person);
            }
            person.addValue("position", positionName);

            FtmEntity occ = dataset.newEntity("Occupancy", "aw", String.valueOf(m.id()));
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("startDate", m.start() == null ? null : m.start().toString());
            occ.addValue("endDate", end == null ? null : end.toString());
            occ.addValue("status", end != null && !end.isAfter(today) ? "ended" : "current");
            if (m.party() != null && !m.party().isBlank()) {
                occ.addValue("politicalGroup", m.party());
            }
            if (m.constituency() != null && !m.constituency().isBlank()) {
                occ.addValue("description", m.constituency());
            }
            dataset.addEntity(occ);
            written++;
        }
        persons.values().forEach(dataset::addEntity);
        if (written == 0) {
            throw new IllegalStateException("Aucun mandat dans la fenêtre de rétention.");
        }
        return written;
    }

    /**
     * Test du parser : relance la lecture de la source vers des dossiers temporaires (la source archivée n'est pas
     * touchée) et renvoie chaque personne avec l'ID et le nom que Yente doit connaître.
     */
    @Override
    public java.util.List<com.swiftcheck.aml.plugin.SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("src-records");
        try {
            java.nio.file.Path out = tmp.resolve("out.ndjson");
            java.nio.file.Path raw = java.nio.file.Files.createDirectories(tmp.resolve("raw"));
            parse(new ParserContext(ctx.datasetName(), ctx.inputFile(), raw, out, ctx.parameters()));
            java.util.regex.Pattern str = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("^\\{\"id\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.List<com.swiftcheck.aml.plugin.SourceRecord> records = new java.util.ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(out, java.nio.charset.StandardCharsets.UTF_8)) {
                if (!line.contains("\"schema\":\"Person\"")) {
                    continue;
                }
                java.util.regex.Matcher id = idPattern.matcher(line);
                int n = line.indexOf("\"name\":[");
                if (!id.find() || n < 0) {
                    continue;
                }
                java.util.regex.Matcher name = str.matcher(line.substring(n + 7));
                if (name.find()) {
                    records.add(new com.swiftcheck.aml.plugin.SourceRecord(unescape(id.group(1)), "Person",
                            unescape(name.group(1)), null));
                }
            }
            return records;
        } finally {
            try (var walk = java.nio.file.Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }
}
