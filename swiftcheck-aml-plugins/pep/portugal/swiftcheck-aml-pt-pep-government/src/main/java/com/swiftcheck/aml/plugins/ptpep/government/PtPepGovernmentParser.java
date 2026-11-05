package com.swiftcheck.aml.plugins.ptpep.government;

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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <b>pt_pep_government</b> – Premier ministre, ministres et secrétaires d'État du Gouvernement portugais, d'après les
 * pages officielles du portail du Gouvernement ({@code portugal.gov.pt}).
 *
 * <p>La page « composition » charge ses noms par JavaScript depuis une API interne : elle n'est pas utilisée. Les pages
 * de chaque ministre et de chaque secrétaire d'État, elles, sont rendues côté serveur ; elles sont découvertes par le
 * {@code sitemap.xml} public (autorisé par {@code robots.txt}) :
 * <ul>
 *   <li>{@code /gcNN/area-de-governo/<domaine>/ministro} et {@code …/secretarios-de-estado/<poste>} ;</li>
 *   <li>{@code /gcNN/primeiro-ministro/secretarios-de-estado/<poste>} ;</li>
 *   <li>{@code /gcNN/primeiro-ministro} : biographie du Premier ministre (« X nasceu em …, a … »).</li>
 * </ul>
 * Chaque page de poste donne dans son titre « Nom - Fonction - Domaine - XXV Governo Constitucional » et, dans ses
 * données de page, l'historique des titulaires du poste (identifiant, dates de début et de fin, fonction générique).
 * Les titulaires partis sont repris pendant {@code monthsAfterEnd} mois.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"sitemapUrl": "https://portugal.gov.pt/sitemap.xml", "government": "gc25",
 *  "monthsAfterEnd": "12", "delayMs": "1500"}
 * }</pre>
 * {@code government} est facultatif : par défaut, le gouvernement le plus récent du sitemap (plus grand {@code gcNN}).
 */
@Extension
public class PtPepGovernmentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "pt-pep-government";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String MANIFEST = "manifest.tsv";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lista PEP Portugal - governo)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern GOVERNMENT_IN_URL = Pattern.compile("/(gc\\d+)/area-de-governo$");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>([^<]*)</title>");
    /** Un titulaire dans les données de page. */
    private static final Pattern HOLDER = Pattern.compile("\\{\"itemId\":\"\\{[^}\"]*\\}\",\"officialId\":\"\\{[^}\"]*\\}\",\"startDate\":\"[^\"]*\",\"endDate\":\"[^\"]*\","
                    + "\"officialName\":\"[^\"]*\",\"governmentRole\":\"[^\"]*\"\\}");
    private static final Pattern BIOGRAPHY = Pattern.compile(
            "(?s)biography-title\">Biografia</h3><div><p>\\s*([^<]*?)\\s+nasceu\\s+(?:em|no|na)\\s+([^<,]+?),\\s+a\\s+(\\d{1,2})\\s+de\\s+([a-zç]+)\\s+de\\s+(\\d{4})");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("janeiro", 1), Map.entry("fevereiro", 2),
            Map.entry("março", 3), Map.entry("abril", 4), Map.entry("maio", 5), Map.entry("junho", 6),
            Map.entry("julho", 7), Map.entry("agosto", 8), Map.entry("setembro", 9), Map.entry("outubro", 10),
            Map.entry("novembro", 11), Map.entry("dezembro", 12));

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Un titulaire lu dans les données d'une page de poste. */
    record Holder(String id, String name, String genericRole, LocalDate start, LocalDate end) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(String government, int pages, int current, int former, int skipped) {

        String message() {
            return String.format("Gouvernement %s : %d pages lues, %d membres en fonction, %d anciens membres (moins d'un an), "
                    + "%d plus anciens ignorés.", government, pages, current, former, skipped);
        }
    }


    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String government = download(httpFetcher(delayMs(p)), required(p, "sitemapUrl"), p.get("government"), ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), government, LocalDate.now(), monthsAfterEnd(p), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), p.get("government"), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /**
     * Lit le sitemap, choisit le gouvernement, télécharge chaque page de poste et la biographie du Premier ministre
     * dans {@code rawDir/pages} et écrit le manifeste (adresse, fichier).
     *
     * @return le code du gouvernement lu (« gc25 »)
     */
    static String download(Fetcher fetcher, String sitemapUrl, String wanted, Path rawDir) throws IOException, InterruptedException {
        Path pages = rawDir.resolve("pages");
        Files.createDirectories(pages);
        String sitemap = fetcher.get(sitemapUrl);
        Files.writeString(rawDir.resolve("sitemap.xml"), sitemap, StandardCharsets.UTF_8);
        List<String> locations = new ArrayList<>();
        Matcher m = LOC.matcher(sitemap);
        while (m.find()) {
            locations.add(m.group(1));
        }
        String government = governmentCode(locations, wanted);
        Pattern member = Pattern.compile("^https?://[^/]+/" + government
                + "/(?:area-de-governo/[^/]+/(?:ministro|secretarios-de-estado/[^/]+)|primeiro-ministro/secretarios-de-estado/[^/]+|primeiro-ministro)$");
        Set<String> urls = new LinkedHashSet<>();
        for (String loc : locations) {
            if (member.matcher(loc).matches()) {
                urls.add(loc);
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("Aucune page de membre du gouvernement « " + government + " » dans le sitemap "
                    + sitemapUrl + " : la structure du site a peut-être changé.");
        }
        StringBuilder manifest = new StringBuilder();
        int i = 0;
        for (String url : urls) {
            String file = String.format("page-%03d.html", i++);
            Files.writeString(pages.resolve(file), fetcher.get(url), StandardCharsets.UTF_8);
            manifest.append(url).append('\t').append(file).append('\n');
        }
        Files.writeString(rawDir.resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);
        return government;
    }

    /** Gouvernement demandé, ou le plus récent du sitemap (plus grand numéro parmi les pages {@code /gcNN/area-de-governo}). */
    static String governmentCode(List<String> locations, String wanted) {
        if (wanted != null && !wanted.isBlank()) {
            return wanted.trim();
        }
        TreeSet<Integer> numbers = new TreeSet<>();
        for (String loc : locations) {
            Matcher m = GOVERNMENT_IN_URL.matcher(loc);
            if (m.find()) {
                numbers.add(Integer.parseInt(m.group(1).substring(2)));
            }
        }
        if (numbers.isEmpty()) {
            throw new IllegalStateException("Aucun gouvernement (/gcNN/area-de-governo) dans le sitemap.");
        }
        return "gc" + numbers.last();
    }

    /** Lit les pages du manifeste et écrit les membres. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, String government, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset)
            throws IOException {
        Path manifest = rawDir.resolve(MANIFEST);
        if (!Files.exists(manifest)) {
            throw new IllegalStateException(MANIFEST + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        int pages = 0, current = 0, former = 0, skipped = 0;
        boolean primeMinister = false;
        Set<String> seenCurrent = new LinkedHashSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            String url = parts[0];
            String html = Files.readString(rawDir.resolve("pages").resolve(parts[1]), StandardCharsets.UTF_8);
            pages++;
            if (url.endsWith("/primeiro-ministro")) {
                primeMinister = writePrimeMinister(html, url, dataset);
                if (primeMinister) {
                    current++;
                }
                continue;
            }
            String[] title = title(html);
            if (title == null) {
                throw new IllegalStateException("Titre « Nom - Fonction - Domaine - Gouvernement » introuvable dans " + url
                        + " : la page a peut-être changé.");
            }
            for (Holder holder : holders(html)) {
                boolean isCurrent = holder.end() == null;
                if (!isCurrent && holder.end().isBefore(cutoff)) {
                    skipped++;
                    continue;
                }
                String role = holder.name().equalsIgnoreCase(title[0]) ? title[1]
                        : holder.genericRole() + " (" + title[2] + ")";
                write(holder, role, title[3], url, dataset);
                if (isCurrent) {
                    if (seenCurrent.add(holder.id())) {
                        current++;
                    }
                } else {
                    former++;
                }
            }
        }
        if (!primeMinister) {
            throw new IllegalStateException("Biographie du Premier ministre introuvable (page /" + government
                    + "/primeiro-ministro, phrase « X nasceu em …, a … de … de … ») : la page a peut-être changé.");
        }
        return new Report(government, pages, current, former, skipped);
    }

    /** {nom, fonction, domaine, gouvernement} du titre de la page, ou {@code null}. */
    static String[] title(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) {
            return null;
        }
        String[] parts = unescape(m.group(1)).split("\\s+-\\s+");
        if (parts.length < 4) {
            return null;
        }
        String role = String.join(" - ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 2));
        return new String[] { parts[0].trim(), role.trim(), parts[parts.length - 2].trim(), parts[parts.length - 1].trim() };
    }

    /** Historique des titulaires du poste, sans doublon (les données de page sont répétées dans le HTML). */
    static List<Holder> holders(String html) throws IOException {
        Map<String, Holder> out = new LinkedHashMap<>();
        Matcher m = HOLDER.matcher(html);
        while (m.find()) {
            JsonNode n = JSON.readTree(m.group());
            String id = n.path("officialId").asText("").replaceAll("[{}]", "").toLowerCase();
            Holder h = new Holder(id, n.path("officialName").asText(), n.path("governmentRole").asText(),
                    date(n.path("startDate").asText()), date(n.path("endDate").asText()));
            out.putIfAbsent(id + "|" + h.start(), h);
        }
        return new ArrayList<>(out.values());
    }

    /** « 20260223T000000Z » → date ; « 00010101T000000Z » (absence de date) → {@code null}. */
    private static LocalDate date(String s) {
        if (s == null || s.length() < 8 || s.startsWith("0001")) {
            return null;
        }
        return LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)));
    }

    private static void write(Holder h, String role, String governmentName, String url, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", h.id());
        person.addValue("name", h.name());
        person.addValue("gender", gender(h.genericRole()));
        person.addValue("country", "pt");
        person.addValue("position", role);
        person.addValue("sourceUrl", url);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", role);
        position.addValue("name", role);
        position.addValue("country", "pt");
        position.addValue("organization", "Governo de Portugal");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", h.id(), role, String.valueOf(h.start()));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", h.start() == null ? null : h.start().toString());
        occupancy.addValue("endDate", h.end() == null ? null : h.end().toString());
        occupancy.addValue("status", h.end() == null ? "current" : "ended");
        occupancy.addValue("description", governmentName);
        occupancy.addValue("sourceUrl", url);
        dataset.addEntity(occupancy);
    }

    /** Premier ministre : nom, lieu et date de naissance lus dans sa biographie officielle. */
    private static boolean writePrimeMinister(String html, String url, FtmDatasetBuilder dataset) {
        Matcher m = BIOGRAPHY.matcher(html);
        if (!m.find()) {
            return false;
        }
        String name = unescape(m.group(1)).trim();
        Integer month = MONTHS.get(m.group(4).toLowerCase());
        if (month == null) {
            return false;
        }
        LocalDate birth = LocalDate.of(Integer.parseInt(m.group(5)), month, Integer.parseInt(m.group(3)));
        String[] title = title(html);
        String governmentName = title == null ? null : title[title.length - 1];
        String role = "Primeiro-Ministro";

        FtmEntity person = dataset.newEntity("Person", "primeiro-ministro", name);
        person.addValue("name", name);
        person.addValue("birthDate", birth.toString());
        person.addValue("birthPlace", unescape(m.group(2)).trim());
        person.addValue("country", "pt");
        person.addValue("position", role);
        person.addValue("sourceUrl", url);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", role);
        position.addValue("name", role);
        position.addValue("country", "pt");
        position.addValue("organization", "Governo de Portugal");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        position.addValue("topics", "gov.head");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "primeiro-ministro", name, "poste");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", governmentName);
        occupancy.addValue("sourceUrl", url);
        dataset.addEntity(occupancy);
        return true;
    }

    private static String gender(String genericRole) {
        String r = genericRole == null ? "" : genericRole.trim();
        if (r.startsWith("Ministra") || r.startsWith("Secretária")) {
            return "female";
        }
        if (r.startsWith("Ministro") || r.startsWith("Secretário")) {
            return "male";
        }
        return null;
    }

    private static String unescape(String s) {
        String out = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#x27;", "'").replace("&#39;", "'").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser (voir le README du plugin).");
        }
        return v.trim();
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

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
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
