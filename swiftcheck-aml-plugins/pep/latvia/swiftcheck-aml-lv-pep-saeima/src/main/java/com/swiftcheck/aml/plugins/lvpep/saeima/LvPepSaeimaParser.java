package com.swiftcheck.aml.plugins.lvpep.saeima;

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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lv_pep_parliament</b> – députés de la Saeima (Parlement letton), personnes politiquement exposées au sens de la loi lettonne
 * sur la prévention du blanchiment de capitaux et du financement du terrorisme.
 *
 * <p>Source officielle : le site {@code saeima.lv}. Pour chaque législature demandée : la page « Deputāti » de la législature
 * ({@code /lv/sasaukumi/N-saeima/deputati/} : nom, circonscription, groupe) puis la fiche de chaque député (historique du
 * mandat avec dates et statut). Le {@code robots.txt} interdit {@code /api/} et {@code /debug/} : seules les pages publiques sont
 * lues. Le site ne publie ni naissance ni décès : une personne décédée n'est écartée que si le statut de son mandat le dit, d'où
 * une fenêtre limitée aux législatures récentes ({@code compositions}). Une personne est reconnue d'une législature à l'autre par
 * son nom.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"baseUrl": "https://www.saeima.lv", "compositions": "15,14", "monthsAfterEnd": "12", "delayMs": "1000"}
 * }</pre>
 */
@Extension
public class LvPepSaeimaParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lv-pep-parliament";
    static final String TOPIC = "role.pep";
    static final String SEAT = "Député à la Saeima (Saeimas deputāts)";
    static final String DEFAULT_BASE_URL = "https://www.saeima.lv";
    static final String DEFAULT_COMPOSITIONS = "15,14";
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final long DEFAULT_DELAY_MS = 1000;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Lettonie - Saeima)";
    private static final DateTimeFormatter LV_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern ITEM = Pattern.compile("<li data-name=\"([^\"]*)\" data-status=\"([^\"]*)\"([^>]*)>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("class=\"dep-card__name\" href=\"([^\"]+)\">([^<]+)<");
    private static final Pattern FACTION = Pattern.compile("class=\"dep-card__faction\">([^<]*)<");
    private static final Pattern DISTRICT = Pattern.compile("data-apgabals=\"([^\"]*)\"");
    private static final Pattern DATE = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{4}\\.?$");

    /** Un député listé sur la page d'une législature. */
    record Listed(int composition, String key, String name, String path, String district, String faction, boolean current) {

        String file() {
            String slug = path.replaceAll("/+$", "");
            return "deputy-" + composition + "-" + slug.substring(slug.lastIndexOf('/') + 1) + ".html";
        }
    }

    /** Une période de mandat lue sur la fiche : {@code end == null} = en cours. */
    record Stint(LocalDate start, LocalDate end) {
    }

    /** Fiche d'un député : périodes de mandat et statuts de mandat. */
    record Detail(List<Stint> stints, List<String> statuses) {

        boolean deceased() {
            return statuses.stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("nāv"));
        }
    }

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        download(httpFetcher(delayMs(p)), baseUrl(p), compositions(p), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        String message = convert(ctx.rawDir(), compositions(p), LocalDate.now(), months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(message);
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), compositions(p), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge la page de chaque législature puis la fiche de chacun de ses députés. */
    static void download(Fetcher fetcher, String baseUrl, List<Integer> compositions, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        for (int c : compositions) {
            String list = fetcher.get(base + listPath(c));
            Files.writeString(rawDir.resolve("list-" + c + ".html"), list, StandardCharsets.UTF_8);
            for (Listed l : listed(c, list)) {
                Files.writeString(rawDir.resolve(l.file()), fetcher.get(base + l.path()), StandardCharsets.UTF_8);
            }
        }
    }

    static String listPath(int composition) {
        return "/lv/sasaukumi/" + composition + "-saeima/deputati/";
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour les tests. */
    String convert(Path rawDir, List<Integer> compositions, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset)
            throws IOException {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        Map<String, List<Object[]>> byPerson = new LinkedHashMap<>();
        Map<String, Listed> sample = new LinkedHashMap<>();
        int read = 0;
        int noDetail = 0;
        int dead = 0;
        int outside = 0;
        for (int c : compositions) {
            Path listFile = rawDir.resolve("list-" + c + ".html");
            if (!Files.exists(listFile)) {
                throw new IllegalStateException(listFile.getFileName() + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
            }
            for (Listed l : listed(c, Files.readString(listFile, StandardCharsets.UTF_8))) {
                read++;
                Path detailFile = rawDir.resolve(l.file());
                Detail detail = Files.exists(detailFile) ? detail(Files.readString(detailFile, StandardCharsets.UTF_8)) : null;
                if (detail == null || detail.stints().isEmpty()) {
                    noDetail++;                         // pas de date lue : on ne publie pas de date inventée
                    continue;
                }
                if (detail.deceased()) {
                    dead++;
                    continue;
                }
                boolean kept = false;
                for (Stint s : detail.stints()) {
                    if (s.end() != null && s.end().isBefore(cutoff)) {
                        continue;
                    }
                    byPerson.computeIfAbsent(l.key(), k -> new ArrayList<>()).add(new Object[] {l, s});
                    kept = true;
                }
                if (kept) {
                    sample.put(l.key(), l);
                } else {
                    outside++;
                }
            }
        }
        if (read == 0) {
            throw new IllegalStateException("Aucun député lu dans " + rawDir + " : le format du site a peut-être changé.");
        }

        FtmEntity position = dataset.newEntity("Position", "saeima");
        position.addValue("name", SEAT);
        position.addValue("country", "lv");
        position.addValue("organization", "Saeima");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        for (Map.Entry<String, List<Object[]>> e : byPerson.entrySet()) {
            Listed l = sample.get(e.getKey());
            FtmEntity person = dataset.newEntity("Person", l.key());
            person.addValue("name", l.name());
            person.addValue("country", "lv");
            person.addValue("nationality", "lv");
            person.addValue("position", SEAT);
            dataset.addEntity(person);
            Set<String> seen = new LinkedHashSet<>();
            for (Object[] o : e.getValue()) {
                Listed ll = (Listed) o[0];
                Stint s = (Stint) o[1];
                if (!seen.add(ll.composition() + "|" + s.start())) {
                    continue;
                }
                FtmEntity occupancy = dataset.newEntity("Occupancy", l.key(), "saeima", String.valueOf(ll.composition()), String.valueOf(s.start()));
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("startDate", s.start().toString());
                occupancy.addValue("endDate", s.end() == null ? null : s.end().toString());
                occupancy.addValue("status", s.end() == null ? "current" : "ended");
                List<String> parts = new ArrayList<>();
                parts.add(ll.composition() + ". Saeima");
                if (ll.faction() != null) {
                    parts.add("Groupe : " + ll.faction());
                }
                if (ll.district() != null) {
                    parts.add("Circonscription : " + ll.district());
                }
                occupancy.addValue("description", String.join(" ; ", parts));
                dataset.addEntity(occupancy);
            }
        }
        return String.format("%d députés lus (%s), %d sans fiche lisible, %d décès enregistrés et %d mandats antérieurs à la fenêtre "
                + "écartés, %d personnes gardées. Source : Saeima, saeima.lv.", read, compositions, noDetail, dead, outside, byPerson.size());
    }

    /** Les députés listés sur la page d'une législature. */
    static List<Listed> listed(int composition, String html) {
        List<Listed> out = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();           // la page présente chaque député deux fois (liste et galerie)
        String clean = html.replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "");
        Matcher m = ITEM.matcher(clean);
        while (m.find()) {
            String body = m.group(4);
            Matcher link = LINK.matcher(body);
            if (!link.find() || !paths.add(link.group(1))) {
                continue;
            }
            Matcher district = DISTRICT.matcher(m.group(3));
            Matcher faction = FACTION.matcher(body);
            String name = text(link.group(2));
            String factionName = faction.find() ? text(faction.group(1)).replaceFirst("^Frakcija ", "") : null;
            out.add(new Listed(composition, m.group(1).trim(), name, link.group(1),
                    district.find() && !district.group(1).isBlank() ? district.group(1) : null,
                    factionName == null || factionName.isBlank() ? null : factionName,
                    "current".equals(m.group(2))));
        }
        return out;
    }

    /**
     * Fiche d'un député : l'historique du mandat (« Datums | date | Statuss | … ») donne les périodes : un mandat commence à
     * « Apstiprināts » ou « Atjaunots » (confirmé, rétabli) et finit à « Izbeigušās pilnvaras » (terme échu) ou « Nolicis /
     * Nolikusi … mandātu » (mandat déposé, par exemple pour devenir ministre). Un chef de groupe n'a pas de ligne « Deputāts » dans
     * l'activité : l'historique du mandat est donc la seule source de dates fiable. Sans historique, repli sur les lignes
     * « Amats | Deputāt… | No | date | Līdz | date ou šobrīd ».
     */
    static Detail detail(String html) {
        List<String> lines = lines(html);
        List<String> statuses = new ArrayList<>();
        List<Object[]> events = new ArrayList<>();
        List<Stint> fallback = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if ("Statuss".equals(lines.get(i)) && i + 1 < lines.size()) {
                statuses.add(lines.get(i + 1));
            }
            if ("Datums".equals(lines.get(i)) && i + 3 < lines.size() && DATE.matcher(lines.get(i + 1)).matches()
                    && "Statuss".equals(lines.get(i + 2))) {
                LocalDate d = date(lines.get(i + 1));
                if (d != null) {
                    events.add(new Object[] {d, lines.get(i + 3)});
                }
            }
            if ("Amats".equals(lines.get(i)) && i + 5 < lines.size() && lines.get(i + 1).startsWith("Deputāt")
                    && "No".equals(lines.get(i + 2)) && DATE.matcher(lines.get(i + 3)).matches() && "Līdz".equals(lines.get(i + 4))) {
                LocalDate start = date(lines.get(i + 3));
                String end = lines.get(i + 5);
                if (start != null && (DATE.matcher(end).matches() || "šobrīd".equals(end))) {
                    Stint s = new Stint(start, "šobrīd".equals(end) ? null : date(end));
                    if (!fallback.contains(s)) {
                        fallback.add(s);
                    }
                }
            }
        }
        events.sort((a, b) -> ((LocalDate) a[0]).compareTo((LocalDate) b[0]));
        List<Stint> stints = new ArrayList<>();
        LocalDate open = null;
        for (Object[] e : events) {
            LocalDate d = (LocalDate) e[0];
            String status = (String) e[1];
            if (status.startsWith("Apstiprināts") || status.startsWith("Atjaunots")) {
                if (open == null) {
                    open = d;
                }
            } else if (status.startsWith("Izbeigušās") || status.startsWith("Nolicis") || status.startsWith("Nolikusi")) {
                if (open != null) {
                    stints.add(new Stint(open, d));
                    open = null;
                }
            }
        }
        if (open != null) {
            stints.add(new Stint(open, null));
        }
        return new Detail(stints.isEmpty() ? fallback : stints, statuses);
    }

    private static LocalDate date(String v) {
        try {
            return LocalDate.parse(v.replaceAll("\\.$", ""), LV_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** Lignes de texte non vides de la page, sans balises ni entités. */
    static List<String> lines(String html) {
        List<String> out = new ArrayList<>();
        String body = html.replaceAll("(?s)<svg.*?</svg>", "").replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("(?s)<style.*?</style>", "");
        for (String raw : body.replaceAll("<[^>]+>", "\n").split("\n")) {
            String line = text(raw);
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    static String text(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    static List<Integer> compositions(Map<String, String> p) {
        String v = p.get("compositions");
        String value = v == null || v.isBlank() ? DEFAULT_COMPOSITIONS : v;
        try {
            return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « compositions » invalide : « " + value + " » (numéros séparés par des virgules).");
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

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        if (v == null || v.isBlank()) {
            return DEFAULT_DELAY_MS;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « delayMs » invalide : « " + v + " » (nombre de millisecondes attendu).");
        }
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
