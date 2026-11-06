package com.swiftcheck.aml.plugins.eepep.riigikogu;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>ee_pep_parliament</b> – membres du Parlement estonien (Riigikogu), personnes politiquement exposées au sens de la loi
 * estonienne sur la prévention du blanchiment de capitaux et du financement du terrorisme (rahapesu ja terrorismi rahastamise
 * tõkestamise seadus).
 *
 * <p>Source officielle : l'API ouverte du Riigikogu {@code https://api.riigikogu.ee/api} (JSON, sans clé) :
 * <ul>
 *   <li>{@code /memberships} : les compositions (législatures) et leurs dates ;</li>
 *   <li>{@code /plenary-members?status=ALL&membership=N} : tous les membres d'une composition, avec date de naissance, <b>date de
 *       décès</b>, sexe, début et fin de mandat, fonction (président, vice-présidents), groupe parlementaire et circonscription.</li>
 * </ul>
 * La liste est demandée en estonien ({@code lang=et}) : en anglais, l'API masque le nom des anciens membres (453 entrées sans nom
 * sur 1 248), en estonien tous les noms sont présents. L'identifiant {@code uuid} d'un membre est stable d'une composition à
 * l'autre : ses mandats sont réunis sous une seule personne. Une personne décédée n'est pas publiée. Une entrée sans nom est
 * ignorée et comptée dans le message. Le robots.txt de riigikogu.ee impose 5 secondes entre deux requêtes ; l'API répond 429 quand on
 * l'interroge plus vite : le délai par défaut est de 6 secondes, avec reprise après un 429.
 *
 * <p>La même classe sert la couche standard (12 mois après la fin du mandat) et la couche historique ({@code monthsAfterEnd}
 * élevé : toutes les compositions depuis 1992). Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://api.riigikogu.ee/api", "monthsAfterEnd": "12", "delayMs": "6000"}
 * }</pre>
 */
@Extension
public class EePepRiigikoguParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ee-pep-parliament";
    static final String TOPIC = "role.pep";

    static final String MEMBERSHIPS_FILE = "memberships.json";
    static final String MEMBERS_PREFIX = "members-";
    static final String SEAT = "Membre du Riigikogu (Riigikogu liige)";
    static final long DEFAULT_DELAY_MS = 6000;
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final String DEFAULT_API_URL = "https://api.riigikogu.ee/api";

    /** Contact générique : jamais l'adresse d'un utilisateur. */
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Estonie - open data du Riigikogu)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Une composition (législature) du Riigikogu. */
    record Composition(int number, LocalDate start, LocalDate end) {
    }

    /** Un membre dans une composition. */
    record Member(String uuid, String name, String firstName, String lastName, String birthDate, String gender,
            LocalDate start, LocalDate end, String jobTitleCode, String jobTitle, String factions, String district) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        LocalDate today = LocalDate.now();
        download(httpFetcher(delayMs(p)), apiUrl(p), today, months, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), today, months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge la liste des compositions puis les membres de celles qui touchent la fenêtre. */
    static void download(Fetcher fetcher, String apiUrl, LocalDate today, int monthsAfterEnd, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String membershipsJson = fetcher.get(base + "/memberships");
        Files.writeString(rawDir.resolve(MEMBERSHIPS_FILE), membershipsJson, StandardCharsets.UTF_8);
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        for (Composition c : compositions(membershipsJson)) {
            if (!inWindow(c, cutoff, today)) {
                continue;
            }
            Files.writeString(rawDir.resolve(MEMBERS_PREFIX + c.number() + ".json"),
                    fetcher.get(base + "/plenary-members?lang=et&status=ALL&membership=" + c.number()), StandardCharsets.UTF_8);
        }
    }

    /** Une composition compte si elle n'est pas terminée avant la fenêtre et n'a pas encore commencé après aujourd'hui. */
    private static boolean inWindow(Composition c, LocalDate cutoff, LocalDate today) {
        return (c.end() == null || !c.end().isBefore(cutoff)) && (c.start() == null || !c.start().isAfter(today));
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(MEMBERSHIPS_FILE);
        if (!Files.exists(file)) {
            throw new IllegalStateException(MEMBERSHIPS_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        Map<String, List<Member>> byPerson = new LinkedHashMap<>();
        int read = 0;
        int dead = 0;
        int unnamed = 0;
        int outside = 0;
        for (Composition c : compositions(Files.readString(file, StandardCharsets.UTF_8))) {
            Path membersFile = rawDir.resolve(MEMBERS_PREFIX + c.number() + ".json");
            if (!Files.exists(membersFile) || !inWindow(c, cutoff, today)) {
                continue;
            }
            for (JsonNode node : MAPPER.readTree(Files.readString(membersFile, StandardCharsets.UTF_8))) {
                read++;
                if (text(node, "dateOfDeath") != null) {
                    dead++;
                    continue;
                }
                Member m = member(node, c.number());
                if (m == null) {
                    unnamed++;
                    continue;
                }
                if (m.end() != null && m.end().isBefore(cutoff)) {
                    outside++;
                    continue;
                }
                byPerson.computeIfAbsent(m.uuid(), k -> new ArrayList<>()).add(m);
            }
        }
        if (read == 0) {
            throw new IllegalStateException("Aucun membre lu dans " + rawDir + " : le format de l'API a peut-être changé.");
        }

        FtmEntity position = dataset.newEntity("Position", "riigikogu");
        position.addValue("name", SEAT);
        position.addValue("country", "ee");
        position.addValue("organization", "Riigikogu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        for (Map.Entry<String, List<Member>> e : byPerson.entrySet()) {
            List<Member> mandates = e.getValue();
            Member m = mandates.get(mandates.size() - 1);
            FtmEntity person = dataset.newEntity("Person", m.uuid());
            person.addValue("name", m.name());
            person.addValue("firstName", m.firstName());
            person.addValue("lastName", m.lastName());
            person.addValue("birthDate", m.birthDate());
            person.addValue("gender", m.gender());
            person.addValue("country", "ee");
            person.addValue("nationality", "ee");
            person.addValue("position", SEAT);
            Set<String> titles = new LinkedHashSet<>();
            for (Member x : mandates) {
                if (!"RIIGIKOGU_LIIGE".equals(x.jobTitleCode()) && x.jobTitle() != null) {
                    titles.add(x.jobTitle());
                }
            }
            titles.forEach(t -> person.addValue("position", t));
            dataset.addEntity(person);

            for (Member x : mandates) {
                FtmEntity occupancy = dataset.newEntity("Occupancy", x.uuid(), "riigikogu", x.start() == null ? "" : x.start().toString());
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("startDate", x.start() == null ? null : x.start().toString());
                occupancy.addValue("endDate", x.end() == null ? null : x.end().toString());
                occupancy.addValue("status", x.end() == null ? "current" : "ended");
                List<String> parts = new ArrayList<>();
                if (x.jobTitle() != null && !"RIIGIKOGU_LIIGE".equals(x.jobTitleCode())) {
                    parts.add("Fonction : " + x.jobTitle());
                }
                if (x.factions() != null) {
                    parts.add("Groupe : " + x.factions());
                }
                if (x.district() != null) {
                    parts.add("Circonscription : " + x.district());
                }
                if (!parts.isEmpty()) {
                    occupancy.addValue("description", String.join(" ; ", parts));
                }
                dataset.addEntity(occupancy);
            }
        }
        return String.format("%d mandats lus, %d personnes décédées et %d mandats antérieurs à la fenêtre écartés, "
                + "%d entrées sans nom ignorées, %d personnes gardées. Source : Riigikogu, api.riigikogu.ee.",
                read, dead, outside, unnamed, byPerson.size());
    }

    /** Les compositions du fichier {@code /memberships}. */
    static List<Composition> compositions(String json) throws IOException {
        List<Composition> out = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(json)) {
            out.add(new Composition(n.path("number").asInt(), date(text(n, "startDate")), date(text(n, "endDate"))));
        }
        return out;
    }

    /** Un membre d'une composition ; {@code null} s'il n'a pas de nom. */
    static Member member(JsonNode n, int composition) {
        String first = text(n, "firstName");
        String last = text(n, "lastName");
        String name = text(n, "fullName");
        if (name == null && first != null && last != null) {
            name = first + " " + last;
        }
        String uuid = text(n, "uuid");
        JsonNode pm = n.path("plenaryMembership");
        if (name == null || uuid == null || pm.isMissingNode() || pm.isNull()) {
            return null;
        }
        List<String> factions = new ArrayList<>();
        for (JsonNode f : n.path("factions")) {
            if (f.path("membership").path("membershipNumber").asInt(-1) == composition && text(f, "name") != null
                    && !factions.contains(text(f, "name"))) {
                factions.add(text(f, "name"));
            }
        }
        List<String> districts = new ArrayList<>();
        for (JsonNode d : n.path("electoralDistrictHistory")) {
            String value = text(d.path("electoralDistrict"), "value");
            if (d.path("membership").asInt(-1) == composition && value != null && !districts.contains(value)) {
                districts.add(value);
            }
        }
        String gender = text(n, "gender");
        return new Member(uuid, name, first, last, text(n, "dateOfBirth"),
                gender == null ? null : gender.toLowerCase(java.util.Locale.ROOT),
                date(text(pm, "startDate")), date(text(pm, "endDate")),
                text(pm.path("jobTitle"), "code"), title(text(pm.path("jobTitle"), "code"), text(pm.path("jobTitle"), "value")),
                factions.isEmpty() ? null : String.join(" → ", factions),
                districts.isEmpty() ? null : String.join(", ", districts));
    }

    /** Titre de la fonction : libellé français des trois fonctions du bureau, sinon le libellé estonien de la source. */
    static String title(String code, String value) {
        if (code == null) {
            return value;
        }
        return switch (code) {
            case "RIIGIKOGU_ESIMEES" -> "Président du Riigikogu (Riigikogu esimees)";
            case "RIIGIKOGU_I_ASEESIMEES" -> "Premier vice-président du Riigikogu (Riigikogu I aseesimees)";
            case "RIIGIKOGU_II_ASEESIMEES" -> "Second vice-président du Riigikogu (Riigikogu II aseesimees)";
            default -> value;
        };
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

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return DEFAULT_MONTHS_AFTER_END;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static String apiUrl(Map<String, String> p) {
        String v = p.get("apiUrl");
        return v == null || v.isBlank() ? DEFAULT_API_URL : v.trim();
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return DEFAULT_DELAY_MS;
        }
        try {
            return Math.max(5000, Long.parseLong(v.trim()));   // plancher : Crawl-Delay de 5 s du robots.txt
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « delayMs » invalide : « " + v + " » (nombre de millisecondes attendu).");
        }
    }

    /** Un appel toutes les {@code delayMs} ; sur 429, trois reprises espacées de 30 secondes. */
    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            for (int attempt = 1; ; attempt++) {
                Thread.sleep(delayMs);
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(3)).header("User-Agent", USER_AGENT).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (response.statusCode() == 429 && attempt < 4) {
                    Thread.sleep(30_000);
                    continue;
                }
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
        };
    }
}
