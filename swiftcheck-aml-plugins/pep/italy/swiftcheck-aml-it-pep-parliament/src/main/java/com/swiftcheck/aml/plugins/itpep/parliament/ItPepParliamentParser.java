package com.swiftcheck.aml.plugins.itpep.parliament;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.itpep.parliament.ParliamentSparql.Row;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>it_pep_chamber</b>, <b>it_pep_senate</b> et <b>it_pep_government</b> – députés, sénateurs et membres du
 * gouvernement italiens (D.Lgs. 231/2007, art. 1 c.2 lett. dd), points 1.1 et 1.2 ; liste notifiée, JO C/2023/724).
 *
 * <p>Source : les points SPARQL officiels de la Chambre des députés ({@code dati.camera.it/sparql}, qui publie aussi les
 * membres du gouvernement depuis 1848) et du Sénat ({@code dati.senato.it/sparql}). Type {@code PLUGIN_MANAGED} : le
 * plugin interroge les points d'accès, avec un User-Agent générique et un rythme lent, et dépose chaque page de
 * réponse dans {@code rawDir}. Le paramètre {@code body} choisit le jeu : {@code chamber}, {@code senate} ou
 * {@code government}.
 *
 * <p>Garde les personnes en fonction et celles dont le mandat s'est terminé depuis moins de {@code monthsAfterEnd} mois
 * (12 par défaut). Une personne décédée n'est jamais publiée. Quand un mandat ne porte pas de date de fin, la fin de la
 * législature (ou du gouvernement) tient lieu de fin ; la législature ou le gouvernement en cours n'a pas de fin.
 */
@Extension
public class ItPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "it-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String CHAMBER_URL = "https://dati.camera.it/sparql";
    static final String SENATE_URL = "https://dati.senato.it/sparql";
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final int DEFAULT_PAGE_SIZE = 2000;
    static final long DEFAULT_DELAY_MS = 500;
    /** Contact générique : jamais l'adresse d'un utilisateur. */
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - open data de la Chambre et du Senat)";

    private static final String PREFIXES = "PREFIX ocd: <http://dati.camera.it/ocd/> "
            + "PREFIX foaf: <http://xmlns.com/foaf/0.1/> PREFIX bio: <http://purl.org/vocab/bio/0.1/> "
            + "PREFIX dc: <http://purl.org/dc/elements/1.1/> PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
            + "PREFIX osr: <http://dati.senato.it/osr/> PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> ";

    /** Jeu de données lu. */
    enum Body {
        CHAMBER("chamber", "Deputato", "Député à la Chambre des députés (Italie)", "Camera dei deputati"),
        SENATE("senate", "Senatore", "Sénateur de la République (Italie)", "Senato della Repubblica"),
        GOVERNMENT("government", null, null, "Governo italiano");

        final String key;
        final String role;
        final String positionLabel;
        final String organization;

        Body(String key, String role, String positionLabel, String organization) {
            this.key = key;
            this.role = role;
            this.positionLabel = positionLabel;
            this.organization = organization;
        }

        static Body of(String value) {
            for (Body b : values()) {
                if (b.key.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return b;
                }
            }
            throw new IllegalArgumentException("Paramètre « body » invalide : « " + value
                    + " » (attendu : chamber, senate ou government).");
        }
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(Body body, int current, int former, int deceased, int monthsAfterEnd) {
        String message() {
            return String.format(Locale.ROOT, "%s : %d personnes publiées (%d en fonction, %d sorties depuis moins de %d mois) ;"
                    + " %d décédées écartées. Source : %s.", body.organization, current + former, current, former,
                    monthsAfterEnd, deceased, body == Body.SENATE ? "dati.senato.it" : "dati.camera.it");
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Body body = Body.of(p.get("body"));
        int months = monthsAfterEnd(p);
        int pageSize = (int) number(p, "pageSize", DEFAULT_PAGE_SIZE);
        long delay = number(p, "delayMs", DEFAULT_DELAY_MS);
        ParliamentSparql.Endpoint endpoint = ParliamentSparql.httpEndpoint(USER_AGENT);
        LocalDate today = LocalDate.now();

        download(endpoint, body, p, today, months, pageSize, delay, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX + "-" + body.key, TOPIC);
        Report report = convert(ctx.rawDir(), body, dataset);
        report = new Report(report.body(), report.current(), report.former(), report.deceased(), months);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    static void download(ParliamentSparql.Endpoint endpoint, Body body, Map<String, String> p, LocalDate today,
            int months, int pageSize, long delay, Path rawDir) throws IOException, InterruptedException {
        LocalDate cutoff = today.minusMonths(months);
        String compact = cutoff.format(DateTimeFormatter.BASIC_ISO_DATE);
        switch (body) {
            case CHAMBER -> ParliamentSparql.download(endpoint, text(p, "chamberUrl", CHAMBER_URL), chamberQuery(compact),
                    body.key, pageSize, delay, rawDir);
            case GOVERNMENT -> ParliamentSparql.download(endpoint, text(p, "chamberUrl", CHAMBER_URL),
                    governmentQuery(compact), body.key, pageSize, delay, rawDir);
            case SENATE -> {
                String url = text(p, "senateUrl", SENATE_URL);
                String latest = latestSenateLegislature(endpoint, url);
                ParliamentSparql.download(endpoint, url, senateQuery(cutoff.toString(), latest), body.key, pageSize,
                        delay, rawDir);
            }
        }
    }

    static String chamberQuery(String cutoffYyyymmdd) {
        return PREFIXES + "SELECT DISTINCT ?pers ?fn ?sn ?bd ?bp ?dd ?start ?end WHERE { "
                + "?pers a foaf:Person ; foaf:firstName ?fn ; foaf:surname ?sn ; ocd:rif_mandatoCamera ?m . "
                + "?m ocd:rif_leg ?leg ; ocd:startDate ?start . "
                + "OPTIONAL { ?m ocd:endDate ?mEnd } OPTIONAL { ?leg ocd:endDate ?lEnd } "
                + "BIND(COALESCE(?mEnd, ?lEnd) AS ?end) "
                + "FILTER(!BOUND(?end) || STR(?end) >= \"" + cutoffYyyymmdd + "\") "
                + "OPTIONAL { ?pers bio:Birth ?b . ?b bio:date ?bd . OPTIONAL { ?b rdfs:label ?bp } } "
                + "OPTIONAL { ?pers bio:Death ?x . ?x bio:date ?dd } } ORDER BY ?pers ?start";
    }

    static String governmentQuery(String cutoffYyyymmdd) {
        return PREFIXES + "SELECT DISTINCT ?pers ?fn ?sn ?title ?organ ?start ?end ?bd ?bp ?dd WHERE { "
                + "{ ?m a ocd:membroGoverno } UNION { ?m a ocd:presidenteConsiglioMinistri } "
                + "?m ocd:rif_persona ?pers ; ocd:rif_governo ?g ; dc:title ?title ; ocd:startDate ?start . "
                + "OPTIONAL { ?m ocd:endDate ?mEnd } OPTIONAL { ?g ocd:endDate ?gEnd } "
                + "BIND(COALESCE(?mEnd, ?gEnd) AS ?end) "
                + "FILTER(!BOUND(?end) || STR(?end) >= \"" + cutoffYyyymmdd + "\") "
                + "?pers foaf:firstName ?fn ; foaf:surname ?sn . "
                + "OPTIONAL { ?m ocd:rif_organoGoverno ?o . ?o rdfs:label ?organ } "
                + "OPTIONAL { ?pers bio:Birth ?b . ?b bio:date ?bd . OPTIONAL { ?b rdfs:label ?bp } } "
                + "OPTIONAL { ?pers bio:Death ?x . ?x bio:date ?dd } } ORDER BY ?pers ?start ?title";
    }

    static String senateQuery(String cutoffIso, String latestLegislature) {
        return PREFIXES + "SELECT DISTINCT ?s ?fn ?ln ?bd ?bp ?dd ?start ?end WHERE { "
                + "?s a osr:Senatore ; foaf:firstName ?fn ; foaf:lastName ?ln ; osr:mandato ?m . "
                + "?m osr:inizio ?start ; osr:legislatura ?leg . OPTIONAL { ?m osr:fine ?end } "
                + "OPTIONAL { ?s osr:dataNascita ?bd } OPTIONAL { ?s osr:cittaNascita ?bp } "
                + "OPTIONAL { ?s osr:dataMorte ?dd } "
                + "FILTER((BOUND(?end) && STR(?end) >= \"" + cutoffIso + "\") || (!BOUND(?end) && STR(?leg) = \""
                + latestLegislature + "\")) } ORDER BY ?s ?start";
    }

    /** La dernière législature connue du Sénat : un mandat sans fin n'est « en cours » que dans celle-là. */
    static String latestSenateLegislature(ParliamentSparql.Endpoint endpoint, String url)
            throws IOException, InterruptedException {
        String json = endpoint.query(url, PREFIXES + "SELECT (MAX(xsd:integer(?l)) AS ?max) WHERE { ?m osr:legislatura ?l . FILTER(REGEX(STR(?l), \"^[0-9]+$\")) }");
        try {
            String value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("results")
                    .path("bindings").path(0).path("max").path("value").asText("");
            if (value.isBlank()) {
                throw new IllegalStateException("Législature en cours du Sénat introuvable.");
            }
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Réponse SPARQL illisible du Sénat", e);
        }
    }

    /** Test du parser : chaque personne publiée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Body body = Body.of(p.get("body"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX + "-" + body.key, TOPIC);
        convert(ctx.rawDir(), body, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Une période de fonctions d'une personne. */
    private record Period(String role, String organ, String start, String end) {
    }

    /** Lit les pages de {@code rawDir} et écrit les entités. */
    Report convert(Path rawDir, Body body, FtmDatasetBuilder dataset) throws IOException {
        Map<String, List<Row>> byPerson = new LinkedHashMap<>();
        for (Row row : ParliamentSparql.read(rawDir, body.key)) {
            byPerson.computeIfAbsent(personId(row), k -> new ArrayList<>()).add(row);
        }
        if (byPerson.isEmpty()) {
            throw new IllegalStateException("Aucune personne lue pour « " + body.key + " » : réponse vide ou format changé.");
        }
        int current = 0;
        int former = 0;
        int deceased = 0;
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> entry : byPerson.entrySet()) {
            List<Row> rows = entry.getValue();
            Row first = rows.get(0);
            if (rows.stream().anyMatch(r -> blank(deathOf(r)) != null)) {
                deceased++;
                continue;
            }
            List<Period> periods = new ArrayList<>();
            for (Row r : rows) {
                periods.add(new Period(role(body, r), blank(r.get("organ")), isoDate(r.get("start")), isoDate(r.get("end"))));
            }
            periods = dedupe(periods);
            boolean active = periods.stream().anyMatch(x -> x.end() == null);
            if (active) {
                current++;
            } else {
                former++;
            }
            write(body, entry.getKey(), rows, periods, positions, dataset);
        }
        return new Report(body, current, former, deceased, DEFAULT_MONTHS_AFTER_END);
    }

    /** La même fonction écrite deux fois (« Ministri » / « ministri ») et à la même date ne compte qu'une fois. */
    private static List<Period> dedupe(List<Period> periods) {
        Map<String, Period> unique = new LinkedHashMap<>();
        for (Period p : periods) {
            unique.putIfAbsent(p.role().toLowerCase(Locale.ROOT) + "|" + p.start(), p);
        }
        return new ArrayList<>(unique.values());
    }

    private static String personId(Row row) {
        String iri = row.get("pers") != null ? row.get("pers") : row.get("s");
        return iri.replaceAll("^.*/[a-zA-Z]*?(\\d+)$", "$1");
    }

    private static String deathOf(Row row) {
        return row.get("dd");
    }

    private static String role(Body body, Row row) {
        if (body != Body.GOVERNMENT) {
            return body.role;
        }
        return cleanTitle(row.get("title"));
    }

    /** « Ministro del Turismo (03.04.2026) » → « Ministro del Turismo » ; « GIORGIA MELONI Presidente… » → sans le nom. */
    static String cleanTitle(String title) {
        String t = title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        t = t.replaceAll("^(?:[\\p{Lu}'’.-]{2,}\\s+)+(?=\\p{Lu}\\p{Ll})", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private void write(Body body, String id, List<Row> rows, List<Period> periods, Map<String, FtmEntity> positions,
            FtmDatasetBuilder dataset) {
        Row first = rows.get(0);
        String first_ = titleCase(first.get("fn"));
        String last = titleCase(first.get("sn") != null ? first.get("sn") : first.get("ln"));
        String name = (first_ + " " + last).trim();
        String pageUrl = body == Body.SENATE ? "https://dati.senato.it/senatore/" + id
                : "https://dati.camera.it/ocd/persona.rdf/p" + id;

        // ID = identifiant officiel de la personne dans l'open data (Chambre : pNNN ; Sénat : senatore/NNN)
        FtmEntity person = dataset.newEntity("Person", body.key, id);
        person.addValue("name", name);
        person.addValue("firstName", first_);
        person.addValue("lastName", last);
        person.addValue("birthDate", isoDate(first.get("bd")));
        person.addValue("birthPlace", birthPlace(first.get("bp")));
        person.addValue("country", "it");
        person.addValue("nationality", "it");
        person.addValue("sourceUrl", pageUrl);
        person.addValue("position", periods.get(periods.size() - 1).role());
        dataset.addEntity(person);

        for (Period period : periods) {
            String key = body.key + "|" + period.role().toLowerCase(Locale.ROOT) + "|" + (period.organ() == null ? "" : period.organ());
            FtmEntity position = positions.computeIfAbsent(key, k -> {
                FtmEntity pos = dataset.newEntity("Position", k);
                pos.addValue("name", body == Body.GOVERNMENT ? period.role() : body.positionLabel);
                pos.addValue("country", "it");
                pos.addValue("organization", body == Body.GOVERNMENT && period.organ() != null ? period.organ()
                        : body.organization);
                pos.addValue("topics", "gov.national");
                if (body == Body.GOVERNMENT) {
                    pos.addValue("topics", period.role().toLowerCase(Locale.ROOT).startsWith("presidente del consiglio")
                            ? "gov.head" : "gov.executive");
                } else {
                    pos.addValue("topics", "gov.legislative");
                }
                dataset.addEntity(pos);
                return pos;
            });
            FtmEntity occupancy = dataset.newEntity("Occupancy", body.key, id, period.role(), period.start());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", period.start());
            occupancy.addValue("endDate", period.end());
            occupancy.addValue("status", period.end() == null ? "current" : "ended");
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    /** « nato a SAN BENEDETTO DEL TRONTO, ASCOLI PICENO, MARCHE, il 11.02.1965 » → « San Benedetto Del Tronto ». */
    static String birthPlace(String text) {
        String t = blank(text);
        if (t == null) {
            return null;
        }
        t = t.replaceFirst("^nato a ", "").replaceFirst(",? il \\d{2}\\.\\d{2}\\.\\d{4}$", "");
        return titleCase(t.split(",")[0].trim());
    }

    /** « 19650211 » ou « 1965-02-11 » → « 1965-02-11 » ; vide → {@code null}. */
    static String isoDate(String value) {
        String v = blank(value);
        if (v == null) {
            return null;
        }
        if (v.matches("\\d{8}")) {
            return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6);
        }
        return v.length() >= 10 ? v.substring(0, 10) : v;
    }

    /** « DE CORATO » → « De Corato » ; « D'ALEMA » → « D'Alema » ; « GARNERO SANTANCHE' » → « Garnero Santanche' ». */
    static String titleCase(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean up = true;
        for (char c : s.trim().toCharArray()) {
            out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = c == ' ' || c == '-' || c == '\'' || c == '’';
        }
        return out.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static int monthsAfterEnd(Map<String, String> p) {
        return (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
    }

    private static long number(Map<String, String> p, String key, long defaultValue) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + key + " » invalide : « " + value + " » (nombre attendu).");
        }
    }

    private static String text(Map<String, String> p, String key, String defaultValue) {
        String value = p.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
