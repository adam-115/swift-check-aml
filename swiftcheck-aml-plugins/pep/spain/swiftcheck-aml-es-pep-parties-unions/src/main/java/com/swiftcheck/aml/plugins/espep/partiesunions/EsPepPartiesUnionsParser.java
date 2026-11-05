package com.swiftcheck.aml.plugins.espep.partiesunions;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>es_pep_parties_unions</b> – dirigeants des partis représentés au Parlement, des principaux syndicats et des organisations patronales (blocs ES-h et ES-SIND), d'après Wikidata.
 *
 * <p>Source : point SPARQL de Wikidata (licence CC0). Les sources officielles de ce bloc sont bloquées ou sans structure
 * (voir le README) ; Wikidata est le secours prévu par le plan. Le plugin ne garde que les mandats qui ont une preuve
 * de date dans la fenêtre : une fin de mandat postérieure à la date de rétention, ou un début postérieur à
 * {@code openSince} (ou une législature donnée par {@code terms}) pour un mandat sans fin. Un titulaire mort, ou sans
 * date, est écarté. Type {@code PLUGIN_MANAGED}. Mode {@code heads}. Paramètres (tous facultatifs, voir
 * {@link #DEFAULTS}) :
 * <ul>
 *   <li>{@code kind} : {@code positions} (titulaires d'une fonction, QID dans {@code positions}, ou fonctions d'une
 *       juridiction filtrées par {@code positionRegex}), {@code heads} (chef du gouvernement ou dirigeant d'une
 *       entité, propriétés dans {@code properties}), {@code mayors} (maires des communes d'au moins
 *       {@code minPopulation} habitants et des chefs-lieux de province) ;</li>
 *   <li>{@code positionLabel} : libellé de la fonction, avec {@code {subject}} pour le nom de l'entité ;</li>
 *   <li>{@code monthsAfterEnd} (24), {@code openSince}, {@code terms}, {@code delayMs}, {@code acceptUndated} (accepte un
 *       mandat sans aucune date : réservé aux listes très courtes qu'on peut contrôler à la main).</li>
 * </ul>
 */
@Extension
public class EsPepPartiesUnionsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepPartiesUnionsParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "es-pep-parties-unions";
    static final String TOPIC = "role.pep";
    static final String HOLDERS_FILE = "holders.json";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - parties-unions)";
    static final String ENTITY = "http://www.wikidata.org/entity/";

    /** Valeurs par défaut de ce parser (remplacées par les paramètres de l'écran « Parsers »). */
    static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("endpoint", "https://query.wikidata.org/sparql"),
            Map.entry("monthsAfterEnd", "24"),
            Map.entry("delayMs", "2000"),
            Map.entry("kind", "heads"),
            Map.entry("entities", "Q138198 Q185088 Q15630787 Q112098527 Q150068 Q97155800 Q1029761 Q43093 Q885433 Q1104327 Q16059622 Q2001535 Q833196 Q393165 Q5782715"),
            Map.entry("properties", "P488 P3320 P3975"),
            Map.entry("positionLabel", "Dirigeant de {subject}"),
            Map.entry("topics", "gov.national"),
            Map.entry("openSince", "2015-01-01")
    );

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern QID = Pattern.compile("^Q\\d+$");

    /** Un mandat lu dans la réponse. */
    record Holding(String qid, String name, String birth, String gender, String subject, String subjectLabel,
            LocalDate start, LocalDate end) {
    }

    record Report(int rows, int persons, int current, int ended, int dropped) {

        String message() {
            return String.format("%d personnes dans Wikidata (%d en fonction, %d sorties depuis moins de 24 mois, "
                    + "%d mandats sans preuve de date écartés).", persons, current, ended, dropped);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = settings(ctx.parameters());
        download(p, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), p, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), settings(ctx.parameters()), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    static Map<String, String> settings(Map<String, String> given) {
        Map<String, String> p = new LinkedHashMap<>(DEFAULTS);
        if (given != null) {
            given.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    p.put(k, v.trim());
                }
            });
        }
        return p;
    }

    // ------------------------------------------------------------------ requête SPARQL

    /** Liste de QID sûre pour une clause {@code VALUES} : tout ce qui n'est pas un QID fait échouer. */
    static String values(String qids) {
        List<String> list = new ArrayList<>();
        for (String q : qids.trim().split("[\\s,]+")) {
            if (q.isEmpty()) {
                continue;
            }
            if (!QID.matcher(q).matches()) {
                throw new IllegalArgumentException("« " + q + " » n'est pas un identifiant Wikidata (Qnnn).");
            }
            list.add("wd:" + q);
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Liste d'identifiants Wikidata vide.");
        }
        return String.join(" ", list);
    }

    static String date(String iso) {
        return LocalDate.parse(iso).atStartOfDay() + ":00Z";
    }

    /** La requête du mode demandé ; chaque ligne est un mandat (personne, entité ou fonction, début, fin). */
    static String query(Map<String, String> p, LocalDate today) {
        LocalDate cutoff = today.minusMonths(Long.parseLong(p.get("monthsAfterEnd")));
        String kind = p.get("kind");
        StringBuilder subject = new StringBuilder();
        String statement;
        String statementEnd = "";
        switch (kind) {
            case "positions" -> {
                if (p.containsKey("positions")) {
                    subject.append("{ VALUES ?subj { ").append(values(p.get("positions"))).append(" } }");
                }
                if (p.containsKey("jurisdictions")) {
                    if (subject.length() > 0) {
                        subject.append(" UNION ");
                    }
                    String regex = p.getOrDefault("positionRegex", ".").replace("\\", "\\\\").replace("\"", "");
                    subject.append("{ VALUES ?jur { ").append(values(p.get("jurisdictions"))).append(" } ?subj wdt:P1001 ?jur . ")
                            .append("?subj rdfs:label ?lab . FILTER(LANG(?lab) = \"es\" && REGEX(?lab, \"").append(regex).append("\", \"i\")) }");
                }
                statement = "?pep p:P39 ?s . ?s ps:P39 ?subj .";
                if (p.containsKey("terms")) {
                    statementEnd = "OPTIONAL { ?s pq:P2937 ?term . VALUES ?term { " + values(p.get("terms")) + " } }";
                }
            }
            case "heads" -> {
                subject.append("{ VALUES ?subj { ").append(values(p.get("entities"))).append(" } }");
                List<String> parts = new ArrayList<>();
                for (String prop : p.getOrDefault("properties", "P6").split("[\\s,]+")) {
                    if (!prop.matches("P\\d+")) {
                        throw new IllegalArgumentException("« " + prop + " » n'est pas une propriété Wikidata (Pnnn).");
                    }
                    parts.add("{ ?subj p:" + prop + " ?s . ?s ps:" + prop + " ?pep . }");
                }
                statement = String.join(" UNION ", parts);
            }
            case "mayors" -> {
                int min = Integer.parseInt(p.get("minPopulation"));
                subject.append("{ ?subj wdt:P31 wd:Q2074737 ; wdt:P1082 ?pop . FILTER(?pop >= ").append(min).append(") }")
                        .append(" UNION { ?prov wdt:P31 wd:Q162620 ; wdt:P17 wd:Q29 ; wdt:P36 ?subj . }");
                statement = "?subj p:P6 ?s . ?s ps:P6 ?pep .";
            }
            default -> throw new IllegalArgumentException("Mode inconnu : " + kind + " (positions, heads ou mayors).");
        }
        String evidence = "true".equals(p.get("acceptUndated")) ? "(true)"
                : p.containsKey("terms") && "positions".equals(kind)
                ? "(BOUND(?term) || BOUND(?end))"
                : "(BOUND(?end) || (BOUND(?start) && ?start >= \"" + date(p.get("openSince")) + "\"^^xsd:dateTime))";
        return "SELECT DISTINCT ?pep ?pepLabel ?birth ?birthPrec ?gender ?start ?end ?subj ?subjLabel WHERE {\n  "
                + subject + "\n  " + statement + "\n"
                + "  OPTIONAL { ?s pq:P580 ?start } OPTIONAL { ?s pq:P582 ?end }\n  " + statementEnd + "\n"
                + "  FILTER NOT EXISTS { ?pep wdt:P570 ?death }\n"
                + "  FILTER(!BOUND(?end) || ?end >= \"" + date(cutoff.toString()) + "\"^^xsd:dateTime)\n"
                + "  FILTER" + evidence + "\n"
                + "  OPTIONAL { ?pep p:P569/psv:P569 [ wikibase:timeValue ?birth ; wikibase:timePrecision ?birthPrec ] }\n"
                + "  OPTIONAL { ?pep wdt:P21 ?gender }\n"
                + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"es,en\" . }\n}";
    }

    static void download(Map<String, String> p, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String sparql = query(p, LocalDate.now());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(p.get("endpoint") + "?format=json&query="
                    + URLEncoder.encode(sparql, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(120))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/sparql-results+json").GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Files.write(rawDir.resolve(HOLDERS_FILE), response.body());
                Files.writeString(rawDir.resolve("query.rq"), sparql, StandardCharsets.UTF_8);
                return;
            }
            last = new IOException("Wikidata a répondu HTTP " + response.statusCode());
            Thread.sleep(Long.parseLong(p.get("delayMs")) * attempt);
        }
        throw last;
    }

    // ------------------------------------------------------------------ conversion

    Report convert(Path rawDir, LocalDate today, Map<String, String> p, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(HOLDERS_FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(HOLDERS_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Holding> holdings = read(file);
        if (holdings.isEmpty()) {
            throw new IllegalStateException("Wikidata n'a renvoyé aucun titulaire : requête vide ou fonctions mal configurées "
                    + "(voir query.rq dans les données brutes).");
        }
        LocalDate oldestEnd = today.minusMonths(Long.parseLong(p.get("monthsAfterEnd")));
        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        int current = 0;
        int ended = 0;
        int dropped = 0;
        for (Holding h : holdings) {
            if (h.end() != null && h.end().isBefore(oldestEnd)) {
                dropped++;
                continue;
            }
            if (!seen.add(h.qid() + "|" + h.subject() + "|" + h.start() + "|" + h.end())) {
                continue;
            }
            boolean isEnded = h.end() != null && h.end().isBefore(today);
            if (isEnded) {
                ended++;
            } else {
                current++;
            }
            String label = p.get("positionLabel").replace("{subject}", h.subjectLabel());
            FtmEntity person = persons.computeIfAbsent(h.qid(), k -> newPerson(h, dataset));
            person.addValue("position", (isEnded ? "Ancien : " : "") + label);

            FtmEntity position = dataset.newEntity("Position", h.subject());
            position.addValue("name", label);
            position.addValue("country", "es");
            position.addValue("organization", p.getOrDefault("organization", h.subjectLabel()));
            for (String topic : p.get("topics").split("[\\s,]+")) {
                position.addValue("topics", topic);
            }
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", h.qid(), h.subject(), h.start() == null ? "" : h.start().toString());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", h.start() == null ? null : h.start().toString());
            occupancy.addValue("endDate", h.end() == null ? null : h.end().toString());
            occupancy.addValue("status", isEnded ? "ended" : "current");
            occupancy.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + h.qid());
            dataset.addEntity(occupancy);
        }
        persons.values().forEach(dataset::addEntity);
        if (persons.isEmpty()) {
            throw new IllegalStateException("Aucun mandat dans la fenêtre de rétention.");
        }
        Report report = new Report(holdings.size(), persons.size(), current, ended, dropped);
        log.info(report.message());
        return report;
    }

    static List<Holding> read(Path file) throws IOException {
        List<Holding> list = new ArrayList<>();
        for (JsonNode b : JSON.readTree(file.toFile()).path("results").path("bindings")) {
            String qid = qid(b.path("pep"));
            String name = value(b, "pepLabel");
            String subject = qid(b.path("subj"));
            String subjectLabel = value(b, "subjLabel");
            if (qid == null || subject == null || name == null || QID.matcher(name).matches()) {
                continue;   // personne sans libellé dans Wikidata : rien à rapprocher
            }
            list.add(new Holding(qid, name, birth(value(b, "birth"), value(b, "birthPrec")),
                    gender(qid(b.path("gender"))), subject, subjectLabel == null || QID.matcher(subjectLabel).matches()
                            ? subject : subjectLabel,
                    day(value(b, "start")), day(value(b, "end"))));
        }
        return list;
    }

    private static FtmEntity newPerson(Holding h, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", h.qid());
        person.addValue("name", h.name());
        person.addValue("country", "es");
        person.addValue("nationality", "es");
        person.addValue("birthDate", h.birth());
        person.addValue("gender", h.gender());
        person.addValue("wikidataId", h.qid());
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + h.qid());
        return person;
    }

    private static String qid(JsonNode node) {
        String v = node.path("value").asText(null);
        return v != null && v.startsWith(ENTITY) ? v.substring(ENTITY.length()) : null;
    }

    private static String value(JsonNode binding, String field) {
        String v = binding.path(field).path("value").asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    private static LocalDate day(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toLocalDate();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Date de naissance à la précision de Wikidata : 11 = jour, 10 = mois, 9 = année ; moins précis = ignoré. */
    static String birth(String iso, String precision) {
        LocalDate date = day(iso);
        if (date == null || precision == null) {
            return null;
        }
        return switch (precision) {
            case "11" -> date.toString();
            case "10" -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            case "9" -> String.format("%04d", date.getYear());
            default -> null;
        };
    }

    private static String gender(String qid) {
        if ("Q6581097".equals(qid)) {
            return "male";
        }
        return "Q6581072".equals(qid) ? "female" : null;
    }

    static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
