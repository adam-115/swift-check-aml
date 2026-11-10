package com.swiftcheck.aml.plugins.bgpep.parliament;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>bg_pep_parliament</b> – députés de l'Assemblée nationale bulgare (Народно събрание) en fonction ou dont le mandat s'est terminé
 * depuis moins de 12 mois, personnes politiquement exposées au sens de la loi bulgare sur les mesures contre le blanchiment
 * d'argent (ЗМИП).
 *
 * <p>Source officielle : l'API JSON publique que le site {@code www.parliament.bg} interroge lui-même (robots.txt sans restriction) :
 * <ul>
 *   <li>{@code coll-list-ns/bg} : la composition actuelle (nom en trois parties, groupe parlementaire, circonscription, fonction) ;</li>
 *   <li>{@code coll-list-ns/bg?nsId=<législature>&date=<jour>} : la composition à un jour donné, avec la date de fin de chaque
 *       mandat. Une composition n'inclut que ceux qui siègent ce jour-là : le plugin la lit tous les 30 jours sur la fenêtre de
 *       rétention, pour la législature en cours et les précédentes encore concernées, et aux jours de passage d'une législature à la
 *       suivante, afin de ne perdre ni les remplaçants ni ceux qui sont partis entre deux lectures ;</li>
 *   <li>{@code mp-profile/bg/<id>} : la fiche du député ; seuls la date et le lieu de naissance sont gardés (adresse électronique et
 *       téléphones ne sont jamais enregistrés).</li>
 * </ul>
 * L'identifiant d'un député change à chaque législature : une personne réélue est reconnue à son nom et à sa date de naissance.
 * Chaque nom est écrit « PRÉNOM PATRONYME NOM » en capitales cyrilliques : il est remis en casse normale, avec en alias les ordres
 * usuels et la translittération latine officielle (système rationalisé, loi de 2009).
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"baseUrl": "https://www.parliament.bg/api/v1", "monthsAfterEnd": "12", "probeDays": "30", "delayMs": "300"}
 * }</pre>
 */
@Extension
public class BgPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "bg-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Député à l'Assemblée nationale (народен представител)";
    static final String SOURCE_URL = "https://www.parliament.bg/bg/MP";
    static final String DEFAULT_BASE_URL = "https://www.parliament.bg/api/v1";
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final int DEFAULT_PROBE_DAYS = 30;
    static final long DEFAULT_DELAY_MS = 300;
    /** Date de fin des mandats en cours dans la source. */
    private static final String OPEN_END = "9999-12-31";
    /** Nombre maximal de législatures lues en remontant (garde-fou). */
    private static final int MAX_ASSEMBLIES = 4;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Bulgarie - Assemblée nationale)";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Un mandat lu dans une composition : {@code end == null} = en cours. */
    record Mandate(String mpId, String first, String patronymic, String last, String party, String region, String role,
            LocalDate start, LocalDate end) {
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        LocalDate today = LocalDate.now();
        download(httpFetcher(longParam(p, "delayMs", DEFAULT_DELAY_MS)), baseUrl(p), today, monthsAfterEnd(p),
                (int) longParam(p, "probeDays", DEFAULT_PROBE_DAYS), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), today, monthsAfterEnd(p), dataset);
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

    // ------------------------------------------------------------------ téléchargement

    /**
     * Télécharge la composition actuelle, celles des jours de la fenêtre pour la législature en cours et les précédentes, puis la
     * fiche (naissance) de chaque député gardé.
     */
    static void download(Fetcher fetcher, String baseUrl, LocalDate today, int monthsAfterEnd, int probeDays, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);

        String current = fetcher.get(base + "/coll-list-ns/bg");
        List<Mandate> currentMandates = mandates(current);
        if (currentMandates.isEmpty()) {
            throw new IllegalStateException("La composition actuelle de l'Assemblée est vide : le format de l'API a peut-être changé.");
        }
        // Numéro interne de la législature en cours : celui de la fiche d'un député en fonction
        int currentNs = profileNs(fetcher.get(base + "/mp-profile/bg/" + currentMandates.get(0).mpId()));
        Files.writeString(rawDir.resolve(nsFile(currentNs, today)), current, StandardCharsets.UTF_8);

        List<Integer> assemblies = new ArrayList<>();
        Map<Integer, LocalDate> lastEnd = new LinkedHashMap<>();
        for (int ns = currentNs, depth = 0; depth < MAX_ASSEMBLIES; ns--, depth++) {
            boolean any = false;
            LocalDate earliestStart = null;
            LocalDate latestEnd = null;
            for (LocalDate day : probeDays(cutoff, today, probeDays)) {
                List<Mandate> list = ns == currentNs && day.equals(today) ? currentMandates
                        : probe(fetcher, base, ns, day, rawDir);
                for (Mandate m : list) {
                    any = true;
                    earliestStart = min(earliestStart, m.start());
                    latestEnd = max(latestEnd, m.end());
                }
            }
            if (!any) {
                break;
            }
            assemblies.add(ns);
            lastEnd.put(ns, latestEnd);
            if (earliestStart == null || !earliestStart.isAfter(cutoff)) {
                break;                       // cette législature couvrait déjà le début de la fenêtre
            }
        }
        // Jours de passage d'une législature à la suivante : les remplaçants et partants de la première semaine
        for (int i = 0; i + 1 < assemblies.size(); i++) {
            LocalDate end = lastEnd.get(assemblies.get(i + 1));
            if (end != null) {
                for (LocalDate day : List.of(end, end.plusDays(1))) {
                    if (!day.isBefore(cutoff) && !day.isAfter(today) && !Files.exists(rawDir.resolve(nsFile(assemblies.get(i), day)))) {
                        probe(fetcher, base, assemblies.get(i), day, rawDir);
                    }
                }
            }
        }

        // Fiche de chaque personne gardée, une fois par identifiant : naissance seulement
        Set<String> ids = new LinkedHashSet<>();
        for (Mandate m : readMandates(rawDir)) {
            if (m.end() == null || !m.end().isBefore(cutoff)) {
                ids.add(m.mpId());
            }
        }
        for (String id : ids) {
            Path file = rawDir.resolve("mp-" + id + ".json");
            if (Files.exists(file)) {
                continue;
            }
            Files.writeString(file, trimProfile(id, fetcher.get(base + "/mp-profile/bg/" + id)), StandardCharsets.UTF_8);
        }
    }

    /** Jours lus : le début de la fenêtre, puis tous les {@code step} jours, et aujourd'hui. */
    static List<LocalDate> probeDays(LocalDate cutoff, LocalDate today, int step) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = cutoff; d.isBefore(today); d = d.plusDays(Math.max(1, step))) {
            days.add(d);
        }
        days.add(today);
        return days;
    }

    /** Lit la composition d'un jour ; ne garde sur disque que les jours où la législature siège. */
    private static List<Mandate> probe(Fetcher fetcher, String base, int ns, LocalDate day, Path rawDir)
            throws IOException, InterruptedException {
        String body = fetcher.get(base + "/coll-list-ns/bg?nsId=" + ns + "&date=" + day);
        List<Mandate> list = mandates(body);
        if (!list.isEmpty()) {
            Files.writeString(rawDir.resolve(nsFile(ns, day)), body, StandardCharsets.UTF_8);
        }
        return list;
    }

    static String nsFile(int ns, LocalDate day) {
        return "ns-" + ns + "-" + day + ".json";
    }

    private static int profileNs(String profileJson) throws IOException {
        JsonNode n = MAPPER.readTree(profileJson).path("A_ns_id");
        if (!n.canConvertToInt()) {
            throw new IllegalStateException("Fiche de député sans numéro de législature (A_ns_id) : le format de l'API a changé.");
        }
        return n.asInt();
    }

    /** Fiche réduite à la naissance : pas d'adresse électronique, pas de téléphone. */
    static String trimProfile(String id, String profileJson) throws IOException {
        JsonNode n = MAPPER.readTree(profileJson);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("id", id);
        out.put("birthDate", text(n, "A_ns_MP_BDate"));
        out.put("birthCity", text(n, "A_ns_B_City"));
        return MAPPER.writeValueAsString(out);
    }

    // ------------------------------------------------------------------ conversion

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Mandate> all = readMandates(rawDir);
        if (all.isEmpty()) {
            throw new IllegalStateException("Aucune composition (ns-*.json) dans " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        int current = 0;
        int outside = 0;
        List<Mandate> kept = new ArrayList<>();
        for (Mandate m : all) {
            if (m.end() == null) {
                current++;
            } else if (m.end().isBefore(cutoff)) {
                outside++;
                continue;
            }
            kept.add(m);
        }
        if (current == 0) {
            throw new IllegalStateException("Aucun député en fonction dans les compositions lues : le format a peut-être changé.");
        }

        FtmEntity position = dataset.newEntity("Position", "narodno-sabranie");
        position.addValue("name", SEAT);
        position.addValue("country", "bg");
        position.addValue("organization", "Народно събрание на Република България");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        int withBirth = 0;
        for (Mandate m : kept) {
            JsonNode profile = profile(rawDir, m.mpId());
            String birth = profile == null ? null : text(profile, "birthDate");
            String name = titleCase(m.first() + " " + m.patronymic() + " " + m.last());
            // Les clés d'identifiant ne gardent que A-Z, a-z et 0-9 : le cyrillique est translittéré
            String key = transliterate(name).toLowerCase() + "|" + (birth == null ? "" : birth);
            FtmEntity person = persons.get(key);
            if (person == null) {
                person = dataset.newEntity("Person", key);
                person.addValue("name", name);
                for (String alias : aliases(m.first(), m.patronymic(), m.last())) {
                    if (!alias.equals(name)) {
                        person.addValue("alias", alias);
                    }
                }
                person.addValue("country", "bg");
                person.addValue("nationality", "bg");
                if (birth != null) {
                    person.addValue("birthDate", birth);
                    withBirth++;
                }
                String city = profile == null ? null : cityName(text(profile, "birthCity"));
                if (city != null) {
                    person.addValue("birthPlace", city);
                }
                person.addValue("position", SEAT);
                person.addValue("sourceUrl", SOURCE_URL);
                dataset.addEntity(person);
                persons.put(key, person);
            }
            FtmEntity occupancy = dataset.newEntity("Occupancy", key, "narodno-sabranie", String.valueOf(m.start()), m.mpId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("startDate", m.start() == null ? null : m.start().toString());
            occupancy.addValue("endDate", m.end() == null ? null : m.end().toString());
            occupancy.addValue("status", m.end() == null ? "current" : "ended");
            List<String> parts = new ArrayList<>();
            if (m.party() != null) {
                parts.add("Groupe parlementaire : " + m.party());
            }
            if (m.region() != null) {
                parts.add("Circonscription : " + m.region());
            }
            if (m.role() != null) {
                parts.add("Fonction : " + m.role());
            }
            occupancy.addValue("description", String.join(" ; ", parts));
            occupancy.addValue("sourceUrl", SOURCE_URL);
            dataset.addEntity(occupancy);
        }
        return String.format("%d mandats en cours et %d terminés depuis moins de %d mois (%d plus anciens écartés), %d personnes gardées dont %d avec date de naissance. "
                + "Source : Народно събрание, www.parliament.bg.", current, kept.size() - current, monthsAfterEnd, outside, persons.size(), withBirth);
    }

    /** Tous les mandats des compositions de {@code rawDir}, sans doublon (un mandat par député et début), du plus ancien au plus récent. */
    static List<Mandate> readMandates(Path rawDir) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)) {
            files = s.filter(f -> f.getFileName().toString().matches("ns-\\d+-\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString())).toList();
        }
        Map<String, Mandate> byKey = new LinkedHashMap<>();
        for (Path f : files) {
            for (Mandate m : mandates(Files.readString(f, StandardCharsets.UTF_8))) {
                byKey.put(m.mpId() + "|" + m.start(), m);        // la lecture la plus récente l'emporte
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Les mandats d'une composition (réponse de {@code coll-list-ns}). */
    static List<Mandate> mandates(String json) throws IOException {
        List<Mandate> out = new ArrayList<>();
        for (JsonNode n : MAPPER.readTree(json).path("colListMP")) {
            String id = text(n, "A_ns_MP_id");
            String first = text(n, "A_ns_MPL_Name1");
            String last = text(n, "A_ns_MPL_Name3");
            if (id == null || first == null || last == null) {
                continue;
            }
            String end = text(n, "A_ns_MSP_date_T");
            String role = text(n, "A_ns_MP_PosL_value");
            out.add(new Mandate(id, first, text(n, "A_ns_MPL_Name2") == null ? "" : text(n, "A_ns_MPL_Name2"), last,
                    text(n, "A_ns_CL_value_short"), text(n, "A_ns_Va_name"),
                    role == null || role.equalsIgnoreCase("член") || role.equalsIgnoreCase("член на НС") ? null : role,
                    date(text(n, "A_ns_MSP_date_F")), end == null || end.equals(OPEN_END) ? null : date(end)));
        }
        return out;
    }

    private static JsonNode profile(Path rawDir, String id) throws IOException {
        Path file = rawDir.resolve("mp-" + id + ".json");
        return Files.exists(file) ? MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)) : null;
    }

    // ------------------------------------------------------------------ noms

    /** « МИХАЕЛА МИЛЧЕВА ДОЦОВА » → « Михаела Милчева Доцова » ; trait d'union et apostrophe ouvrent une majuscule. */
    static String titleCase(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : name.trim().replaceAll("\\s+", " ").toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = c == ' ' || c == '-' || c == '\'' || c == '’';
        }
        return out.toString();
    }

    /** Noms en alias : sans patronyme, ordres inverses, et leurs translittérations latines. */
    static Set<String> aliases(String first, String patronymic, String last) {
        String f = titleCase(first);
        String p = titleCase(patronymic);
        String l = titleCase(last);
        Set<String> out = new LinkedHashSet<>();
        for (String[] set : new String[][] { { f, p, l }, { f, "", l } }) {
            out.add(join(set[0], set[1], set[2]));
            out.add(join(set[2], set[0], set[1]));
        }
        Set<String> latin = new LinkedHashSet<>();
        for (String a : out) {
            String t = transliterate(a);
            latin.add(t);
            if (t.contains("iya") || t.contains("Iya")) {
                latin.add(t.replace("iya", "ia").replace("Iya", "Ia"));        // usage courant : Iliana, Mariana…
            }
        }
        out.addAll(latin);
        return out;
    }

    private static String join(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                kept.add(part);
            }
        }
        return String.join(" ", kept);
    }

    private static final Map<Character, String> LATIN = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"),
            Map.entry('к', "k"), Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
            Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"),
            Map.entry('щ', "sht"), Map.entry('ъ', "a"), Map.entry('ь', "y"), Map.entry('ю', "yu"), Map.entry('я', "ya"));

    /** Translittération officielle bulgare (système rationalisé) : « Михаела Доцова » → « Mihaela Dotsova » ; « -ия » final → « -ia ». */
    static String transliterate(String text) {
        StringBuilder out = new StringBuilder();
        String[] words = text.split(" ");
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            String lower = word.toLowerCase();
            boolean endsIya = lower.endsWith("ия") && lower.length() > 2;
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (endsIya && i == lower.length() - 2) {
                    t.append("ia");
                    break;
                }
                t.append(LATIN.getOrDefault(c, String.valueOf(c)));
            }
            boolean upper = true;
            StringBuilder cased = new StringBuilder();
            for (char c : t.toString().toCharArray()) {
                cased.append(upper ? Character.toUpperCase(c) : c);
                upper = c == '-' || c == '\'';
            }
            out.append(w == 0 ? "" : " ").append(cased);
        }
        return out.toString();
    }

    /** « гр. Монтана » / « с. Бохот » → « Монтана ». */
    static String cityName(String city) {
        if (city == null) {
            return null;
        }
        String c = city.replaceFirst("^(гр|с|к\\.к|ж\\.к|кв)\\.?\\s+", "").trim();
        return c.isEmpty() ? null : c;
    }

    // ------------------------------------------------------------------ utilitaires

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : b.isBefore(a) ? b : a;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : b.isAfter(a) ? b : a;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    static LocalDate date(String v) {
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

    private static String baseUrl(Map<String, String> p) {
        String v = p.get("baseUrl");
        return v == null || v.isBlank() ? DEFAULT_BASE_URL : v.trim();
    }

    private static long longParam(Map<String, String> p, String name, long fallback) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + name + " » invalide : « " + v + " » (nombre attendu).");
        }
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
