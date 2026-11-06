package com.swiftcheck.aml.plugins.plpep.sejm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>pl_pep_parliament</b> et <b>pl_pep_clubs</b> – députés de la Diète (Sejm) et présidents de groupes parlementaires (kluby)
 * polonais, personnes politiquement exposées au sens de la loi polonaise sur la lutte contre le blanchiment (ustawa o
 * przeciwdziałaniu praniu pieniędzy).
 *
 * <p>Source officielle : l'API ouverte de la Diète {@code https://api.sejm.gov.pl/sejm} (JSON, sans clé) :
 * <ul>
 *   <li>{@code /term} : les législatures et leurs dates ;</li>
 *   <li>{@code /term{n}/MP} : les députés d'une législature (nom, naissance, club, circonscription, serment, et pour un mandat
 *       terminé : {@code mandateExpiryDate} et cause : renonciation, décès…) ;</li>
 *   <li>{@code /term{n}/clubs} : les groupes parlementaires et leurs membres avec leur fonction.</li>
 * </ul>
 * L'identifiant d'un député n'est pas stable d'une législature à l'autre : une personne est reconnue par prénom, nom et date de
 * naissance. La source ne publie pas le décès des anciens députés : seuls les décès enregistrés pendant la législature
 * ({@code inactiveCause = Zgon}) sont écartés, d'où une fenêtre d'historique raisonnable (15 ans). Le Sénat (WAF), la Cour suprême,
 * la Présidence et la Banque nationale refusent les requêtes automatiques et ne sont pas utilisés.
 *
 * <p>Paramètre {@code scope} : {@code parliament} ou {@code clubs} ; {@code monthsAfterEnd} : fenêtre après la fin du mandat
 * (12 par défaut). Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://api.sejm.gov.pl/sejm", "scope": "parliament", "monthsAfterEnd": "12", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class PlPepSejmParser implements Iparser {

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String TERMS_FILE = "terms.json";
    static final String MP_PREFIX = "mp-";
    static final String CLUBS_PREFIX = "clubs-";
    static final List<String> SCOPES = List.of("parliament", "clubs");
    static final String SEAT = "Député à la Diète (poseł na Sejm)";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Pologne - Sejm)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Une législature. */
    record Term(int num, LocalDate from, LocalDate to) {
    }

    /** Un député d'une législature. */
    record Mp(int id, String firstName, String secondName, String lastName, String birthDate, boolean active, String club,
            String district, String inactiveCause, LocalDate oath, LocalDate expiry) {

        String name() {
            return (firstName + " " + lastName).trim();
        }

        String key() {
            return firstName + "|" + lastName + "|" + birthDate;
        }
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        int months = monthsAfterEnd(p);
        download(httpFetcher(delayMs(p)), apiUrl(p), LocalDate.now(), months, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        String message = convert(ctx.rawDir(), LocalDate.now(), months, scope, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String scope = scope(p);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(prefix(scope), TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), scope, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge les législatures, les députés des législatures dans la fenêtre et les groupes de la législature en cours. */
    static void download(Fetcher fetcher, String apiUrl, LocalDate today, int monthsAfterEnd, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String termsJson = fetcher.get(base + "/term");
        Files.writeString(rawDir.resolve(TERMS_FILE), termsJson, StandardCharsets.UTF_8);
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        for (Term t : terms(termsJson)) {
            if (t.to() != null && t.to().isBefore(cutoff)) {
                continue;
            }
            Files.writeString(rawDir.resolve(MP_PREFIX + t.num() + ".json"), fetcher.get(base + "/term" + t.num() + "/MP"), StandardCharsets.UTF_8);
            if (t.to() == null) {
                Files.writeString(rawDir.resolve(CLUBS_PREFIX + t.num() + ".json"), fetcher.get(base + "/term" + t.num() + "/clubs"),
                        StandardCharsets.UTF_8);
            }
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit la couche demandée. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, String scope, FtmDatasetBuilder dataset) throws IOException {
        Path termsFile = rawDir.resolve(TERMS_FILE);
        if (!Files.exists(termsFile)) {
            throw new IllegalStateException(TERMS_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Term> terms = terms(Files.readString(termsFile, StandardCharsets.UTF_8));
        return "clubs".equals(scope) ? convertClubs(rawDir, terms, dataset) : convertSeats(rawDir, terms, today, cutoff, dataset);
    }

    private String convertSeats(Path rawDir, List<Term> terms, LocalDate today, LocalDate cutoff, FtmDatasetBuilder dataset) throws IOException {
        Map<String, List<Object[]>> byPerson = new LinkedHashMap<>();
        Map<String, Mp> sample = new LinkedHashMap<>();
        int read = 0;
        int dead = 0;
        for (Term t : terms) {
            Path file = rawDir.resolve(MP_PREFIX + t.num() + ".json");
            if (!Files.exists(file) || (t.to() != null && t.to().isBefore(cutoff))) {
                continue;
            }
            for (Mp m : mps(Files.readString(file, StandardCharsets.UTF_8))) {
                read++;
                if ("Zgon".equals(m.inactiveCause())) {
                    dead++;
                    continue;
                }
                LocalDate start = m.oath() != null ? m.oath() : t.from();
                LocalDate end = t.to() == null ? (m.active() ? null : m.expiry()) : (m.expiry() != null && m.expiry().isBefore(t.to()) ? m.expiry() : t.to());
                if (end != null && end.isBefore(cutoff)) {
                    continue;
                }
                if (t.to() == null && !m.active() && m.expiry() == null) {
                    continue;                               // mandat inactif sans date : on ne publie pas de date inventée
                }
                byPerson.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(new Object[] {m, t, start, end});
                sample.put(m.key(), m);
            }
        }
        if (read == 0) {
            throw new IllegalStateException("Aucun député lu dans " + rawDir + " : le format de l'API a peut-être changé.");
        }
        for (Map.Entry<String, List<Object[]>> e : byPerson.entrySet()) {
            Mp m = sample.get(e.getKey());
            FtmEntity person = dataset.newEntity("Person", m.firstName(), String.valueOf(m.secondName()), m.lastName(), m.birthDate());
            person.addValue("name", m.name());
            if (m.secondName() != null) {
                person.addValue("alias", m.firstName() + " " + m.secondName() + " " + m.lastName());
            }
            person.addValue("firstName", m.firstName());
            person.addValue("lastName", m.lastName());
            person.addValue("birthDate", m.birthDate());
            person.addValue("country", "pl");
            person.addValue("position", SEAT);
            dataset.addEntity(person);

            FtmEntity position = dataset.newEntity("Position", "sejm");
            position.addValue("name", SEAT);
            position.addValue("country", "pl");
            position.addValue("organization", "Sejm Rzeczypospolitej Polskiej");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.legislative");
            dataset.addEntity(position);

            for (Object[] o : e.getValue()) {
                Mp mm = (Mp) o[0];
                Term t = (Term) o[1];
                LocalDate start = (LocalDate) o[2];
                LocalDate end = (LocalDate) o[3];
                FtmEntity occupancy = dataset.newEntity("Occupancy", m.firstName(), m.lastName(), m.birthDate(), "sejm", String.valueOf(t.num()));
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("startDate", start == null ? null : start.toString());
                occupancy.addValue("endDate", end == null ? null : end.toString());
                occupancy.addValue("status", end == null ? "current" : "ended");
                List<String> parts = new ArrayList<>();
                parts.add("Législature " + t.num());
                if (mm.club() != null) {
                    parts.add("Club : " + mm.club());
                }
                if (mm.district() != null) {
                    parts.add("Circonscription : " + mm.district());
                }
                if (mm.inactiveCause() != null) {
                    parts.add("Fin de mandat : " + mm.inactiveCause());
                }
                occupancy.addValue("description", String.join(" ; ", parts));
                dataset.addEntity(occupancy);
            }
        }
        return String.format("%d mandats lus, %d décès enregistrés écartés, %d personnes gardées (parliament).", read, dead, byPerson.size());
    }

    private String convertClubs(Path rawDir, List<Term> terms, FtmDatasetBuilder dataset) throws IOException {
        int n = 0;
        for (Term t : terms) {
            Path clubsFile = rawDir.resolve(CLUBS_PREFIX + t.num() + ".json");
            Path mpFile = rawDir.resolve(MP_PREFIX + t.num() + ".json");
            if (t.to() != null || !Files.exists(clubsFile) || !Files.exists(mpFile)) {
                continue;
            }
            Map<Integer, Mp> byId = new LinkedHashMap<>();
            mps(Files.readString(mpFile, StandardCharsets.UTF_8)).forEach(m -> byId.put(m.id(), m));
            for (JsonNode club : MAPPER.readTree(Files.readString(clubsFile, StandardCharsets.UTF_8))) {
                for (JsonNode member : club.path("members")) {
                    if (!"przewodniczący".equals(text(member, "function"))) {
                        continue;
                    }
                    Mp m = byId.get(member.path("id").asInt());
                    if (m == null) {
                        continue;
                    }
                    String clubName = club.path("name").asText(club.path("id").asText());
                    String role = "Président du groupe parlementaire « " + club.path("id").asText() + " » (przewodniczący klubu)";
                    FtmEntity person = dataset.newEntity("Person", m.firstName(), String.valueOf(m.secondName()), m.lastName(), m.birthDate());
                    person.addValue("name", m.name());
                    person.addValue("firstName", m.firstName());
                    person.addValue("lastName", m.lastName());
                    person.addValue("birthDate", m.birthDate());
                    person.addValue("country", "pl");
                    person.addValue("position", role);
                    dataset.addEntity(person);

                    FtmEntity position = dataset.newEntity("Position", "club", club.path("id").asText());
                    position.addValue("name", role);
                    position.addValue("country", "pl");
                    position.addValue("organization", clubName);
                    position.addValue("topics", "gov.national");
                    position.addValue("topics", "gov.legislative");
                    dataset.addEntity(position);

                    FtmEntity occupancy = dataset.newEntity("Occupancy", m.firstName(), m.lastName(), m.birthDate(), "club", club.path("id").asText());
                    occupancy.addValue("holder", person.getId());
                    occupancy.addValue("post", position.getId());
                    occupancy.addValue("startDate", date(text(member, "joinDate")) == null ? null : text(member, "joinDate"));
                    occupancy.addValue("status", "current");
                    occupancy.addValue("description", "Législature " + t.num() + " ; président du club depuis l'adhésion indiquée");
                    dataset.addEntity(occupancy);
                    n++;
                }
            }
        }
        if (n == 0) {
            throw new IllegalStateException("Aucun président de club lu : le format de l'API a peut-être changé.");
        }
        return n + " présidents de groupes parlementaires (clubs).";
    }

    /** Les législatures du fichier {@code /term}. */
    static List<Term> terms(String json) throws IOException {
        List<Term> out = new ArrayList<>();
        for (JsonNode t : MAPPER.readTree(json)) {
            out.add(new Term(t.path("num").asInt(), date(text(t, "from")), date(text(t, "to"))));
        }
        return out;
    }

    /** Les députés d'un fichier {@code /term{n}/MP}. */
    static List<Mp> mps(String json) throws IOException {
        List<Mp> out = new ArrayList<>();
        for (JsonNode m : MAPPER.readTree(json)) {
            out.add(new Mp(m.path("id").asInt(), text(m, "firstName"), text(m, "secondName"), text(m, "lastName"), text(m, "birthDate"),
                    m.path("active").asBoolean(true), text(m, "club"), text(m, "districtName"), text(m, "inactiveCause"),
                    date(text(m, "oathDate")), date(text(m, "mandateExpiryDate"))));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(String v) {
        if (v == null || v.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    static String scope(Map<String, String> p) {
        String v = p.get("scope");
        if (v == null || v.isBlank()) {
            return "parliament";
        }
        if (!SCOPES.contains(v.trim())) {
            throw new IllegalArgumentException("Paramètre « scope » invalide : « " + v + " » (valeurs : " + SCOPES + ").");
        }
        return v.trim();
    }

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static String prefix(String scope) {
        return "pl-pep-" + scope;
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return 12;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        return v == null || v.isBlank() ? "https://api.sejm.gov.pl/sejm" : v.trim();
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 500 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
