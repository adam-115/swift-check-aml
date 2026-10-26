package com.swiftcheck.aml.plugins.lupep.eucommission;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Parser PEP Luxembourg : Membre luxembourgeois de la Commission européenne.
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-EU</b> (Luxembourgeois exerçant une fonction
 * européenne au niveau de l'Union européenne), fonction <b>Membre luxembourgeois de la Commission
 * européenne</b> (JO C/2023/724).
 *
 * <p>Il analyse soit la page officielle du Collège des commissaires (présentation d'ensemble), soit
 * la fiche individuelle d'un commissaire sur {@code commission.europa.eu}.
 *
 * <p>Pour chaque commissaire luxembourgeois, il produit trois entités FollowTheMoney :
 * <ul>
 *   <li>{@code Person} : identifiant, nom, prénom, date de naissance, pays ({@code lu}), topic {@code role.pep} ;</li>
 *   <li>{@code Position} : « Membre luxembourgeois de la Commission européenne » ;</li>
 *   <li>{@code Occupancy} : dates de mandat, statut ({@code current} ou {@code ended}) et portefeuille.</li>
 * </ul>
 *
 * <p>L'URL source est résolue <b>dynamiquement</b> depuis l'objet {@link ParserContext} passé en argument
 * (paramètres {@code sourceUrl}, {@code url} ou {@code sourceLocation}), avec repli par défaut sur la page
 * officielle du Collège.
 */
@Extension
public class LuPepEuCommissionParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepEuCommissionParser.class);

    /** Préfixe par défaut des identifiants FollowTheMoney. */
    static final String DEFAULT_ID_PREFIX = "lu-pep-eu-commission";

    /** URL source par défaut si non renseignée dans les paramètres du ParserContext. */
    static final String DEFAULT_SOURCE_URL =
            "https://commission.europa.eu/about/organisation/college-commissioners_fr";

    /** Catégorie de risque PEP. */
    static final String TOPIC = "role.pep";

    /** Intitulé officiel de la fonction selon le référentiel LU-EU. */
    static final String POSITION_NAME = "Membre luxembourgeois de la Commission européenne";

    /** Pattern pour extraire un article de commissaire dans la page du collège. */
    private static final Pattern COMMISSIONER_ARTICLE = Pattern.compile(
            "(?si)<article[^>]*class=\"[^\"]*ecl-content-item[^\"]*\"[^>]*>.*?"
            + "<div[^>]*class=\"[^\"]*ecl-content-block__title[^\"]*\"[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*([^<]+?)\\s*</a>.*?</div>.*?"
            + "<div[^>]*class=\"[^\"]*ecl-content-block__description[^\"]*\"[^>]*>(.*?)</div>\\s*</div>\\s*</article>");

    /** Pattern pour extraire la meta description d'une fiche individuelle. */
    private static final Pattern META_DESC_PATTERN = Pattern.compile(
            "(?i)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']");

    /** Pattern pour extraire le titre og:title ou title d'une page individuelle. */
    private static final Pattern META_TITLE_PATTERN = Pattern.compile(
            "(?i)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");

    /**
     * Paramètre : fiches d'archive des anciens commissaires luxembourgeois (une URL par ligne, ou séparées par des
     * espaces ou des virgules), ex. {@code https://commissioners.ec.europa.eu/nicolas-schmit_fr}. La page du
     * collège ne liste que les commissaires en fonction : sans ces fiches, un ancien commissaire encore dans la
     * période de rétention disparaîtrait.
     */
    static final String PARAM_FORMER_PAGES = "formerCommissionerPages";

    /** Rôle et mandat d'une fiche d'archive : « COMMISSAIRE (2019-2024) », « PRÉSIDENT (2014-2019) ». */
    private static final Pattern ARCHIVE_ROLE = Pattern.compile(
            "(?i)^((?:VICE-)?PR[ÉE]SIDENTE?|COMMISSAIRE)[^(]*\\((\\d{4})\\s*[-–]\\s*(\\d{4})\\)$");

    /** Période de la biographie : « Décembre 2019 - 2024 », « Novembre 2014 - octobre 2019 », « 2009 - 2013 ». */
    private static final Pattern ARCHIVE_PERIOD = Pattern.compile(
            "(?i)^(?:(\\p{L}+)\\s+)?(\\d{4})\\s*[-–]\\s*(?:(\\p{L}+)\\s+)?(\\d{4})$");

    private static final List<String> FRENCH_MONTHS = List.of("janvier", "février", "mars", "avril", "mai", "juin",
            "juillet", "août", "septembre", "octobre", "novembre", "décembre");

    private final PageFetcher fetcher;

    public LuPepEuCommissionParser() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.fetcher = url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) SwiftCheck-AML/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " pour " + url);
            }
            return response.body();
        };
    }

    /** Pour les tests : pages lues sans réseau. */
    LuPepEuCommissionParser(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);

        log.info("Analyse de la Commission européenne (rétention : {} ans, URL dynamique : {})",
                parseYearsAfterEnd(ctx.parameters()), sourceUrl);

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, formerPages(ctx.parameters()), ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, formerPages(ctx.parameters()), ctx.rawDir(), dataset);

        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse("");
                records.add(new SourceRecord(entity.getId(), "Person", name, sourceUrl));
            }
        }
        return records;
    }

    /**
     * Lit le fichier HTML (page du collège ou page individuelle) et convertit les commissaires retenus.
     */
    void convert(Path html, LocalDate oldestEndDate, String sourceUrl, FtmDatasetBuilder dataset) throws Exception {
        convert(html, oldestEndDate, sourceUrl, List.of(), null, dataset);
    }

    /**
     * Commissaires en fonction (page du collège) et anciens commissaires (fiches d'archive {@code formerPages}),
     * dans la limite de la rétention.
     */
    void convert(Path html, LocalDate oldestEndDate, String sourceUrl, List<String> formerPages, Path rawDir,
                 FtmDatasetBuilder dataset) throws Exception {
        String content = Files.readString(html, StandardCharsets.UTF_8);
        List<CommissionerData> commissioners = new ArrayList<>(extractCommissioners(content, sourceUrl, oldestEndDate));
        commissioners.addAll(formerCommissioners(formerPages, oldestEndDate, rawDir));

        if (commissioners.isEmpty()) {
            throw new IllegalStateException("Aucun commissaire européen luxembourgeois trouvé dans " + html);
        }

        for (CommissionerData c : commissioners) {
            convertCommissioner(c, sourceUrl, dataset);
        }

        log.info("Commission européenne : {} commissaire(s) luxembourgeois généré(s)", commissioners.size());
    }

    /**
     * Analyse le contenu HTML et retient les commissaires luxembourgeois éligibles.
     */
    static List<CommissionerData> extractCommissioners(String html, String sourceUrl, LocalDate oldestEndDate) {
        Map<String, CommissionerData> result = new LinkedHashMap<>();

        // 1. Détection du format de la page
        boolean isSingleCommissionerPage = isIndividualPage(html);

        if (isSingleCommissionerPage) {
            CommissionerData individual = parseIndividualPage(html, sourceUrl);
            if (individual != null && isEligible(individual.endDate(), oldestEndDate)) {
                result.put(individual.slug(), individual);
            }
        } else {
            // Page d'ensemble du Collège des commissaires : détecter le commissaire luxembourgeois en fonction
            Matcher m = COMMISSIONER_ARTICLE.matcher(html);
            while (m.find()) {
                String link = m.group(1).trim();
                String name = stripHtml(m.group(2)).trim();
                String desc = stripHtml(m.group(3)).trim();

                boolean isLux = link.toLowerCase(Locale.ROOT).contains("hansen")
                        || desc.toLowerCase(Locale.ROOT).contains("luxembourg")
                        || name.toLowerCase(Locale.ROOT).contains("hansen");

                if (isLux) {
                    String fullLink = link.startsWith("http") ? link : "https://commission.europa.eu" + link;
                    String[] nameParts = splitFirstLastName(name);
                    boolean isPres = desc.toLowerCase(Locale.ROOT).contains("président") || desc.toLowerCase(Locale.ROOT).contains("president");
                    CommissionerData data = new CommissionerData(
                            slug(name),
                            name,
                            nameParts[0],
                            nameParts[1],
                            null,
                            desc,
                            "2024-12-01",
                            null, // Actif dans le collège affiché
                            "current",
                            fullLink,
                            isPres
                    );
                    result.put(data.slug(), data);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private static String[] splitFirstLastName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return new String[] { "", "" };
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return new String[] { "", parts[0] };
        }
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) first.append(' ');
            first.append(parts[i]);
        }
        return new String[] { first.toString(), parts[parts.length - 1] };
    }

    private static boolean isIndividualPage(String html) {
        String lower = html != null ? html.toLowerCase(Locale.ROOT) : "";
        return lower.contains("commissaire luxembourgeois")
                || (lower.contains("christophe hansen") && lower.contains("biographie"));
    }

    private static CommissionerData parseIndividualPage(String html, String pageUrl) {
        String title = extractFirstMatch(html, META_TITLE_PATTERN);
        if (title == null || title.isBlank()) {
            title = extractFirstMatch(html, Pattern.compile("(?i)<h1[^>]*>\\s*<span[^>]*>([^<]+)</span>\\s*</h1>"));
        }
        if (title == null || title.isBlank()) {
            title = "Christophe Hansen";
        }
        String desc = extractFirstMatch(html, META_DESC_PATTERN);
        String portfolio = "Agriculture et alimentation";
        if (desc != null && desc.contains("chargé de ")) {
            int idx = desc.indexOf("chargé de ");
            int end = desc.indexOf(" au sein", idx);
            if (end > idx) {
                portfolio = desc.substring(idx + "chargé de ".length(), end).trim();
            }
        }

        String[] nameParts = splitFirstLastName(title);
        String startDate = "2024-12-01";
        boolean isPres = desc != null && desc.toLowerCase(Locale.ROOT).contains("président");

        return new CommissionerData(
                slug(title),
                title,
                nameParts[0],
                nameParts[1],
                null,
                portfolio,
                startDate,
                null,
                "current",
                pageUrl,
                isPres
        );
    }

    /**
     * URLs du paramètre {@value #PARAM_FORMER_PAGES}, séparées par des retours à la ligne, espaces ou virgules.
     * Un tableau JSON ({@code ["url1", "url2"]}, transmis tel quel par le core) est aussi accepté.
     */
    static List<String> formerPages(Map<String, String> parameters) {
        String value = parameters == null ? null : parameters.get(PARAM_FORMER_PAGES);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[\\s,;\\[\\]\"]+")).filter(s -> !s.isBlank()).distinct().toList();
    }

    /**
     * Télécharge et lit chaque fiche d'archive. Une fiche illisible fait échouer l'exécution : publier sans elle
     * retirerait un PEP de la liste.
     */
    List<CommissionerData> formerCommissioners(List<String> pages, LocalDate oldestEndDate, Path rawDir)
            throws IOException, InterruptedException {
        List<CommissionerData> result = new ArrayList<>();
        for (String url : pages) {
            String html = fetcher.fetch(url);
            CommissionerData former = parseArchivePage(html, url);
            if (former == null) {
                throw new IllegalStateException("Fiche d'ancien commissaire illisible (nom ou mandat introuvable) : " + url);
            }
            if (rawDir != null) {
                Files.createDirectories(rawDir);
                Files.writeString(rawDir.resolve("former-" + former.slug() + ".html"), html, StandardCharsets.UTF_8);
            }
            if (isEligible(former.endDate(), oldestEndDate)) {
                result.add(former);
            } else {
                log.info("Ancien commissaire {} hors rétention (fin {}) : non publié", former.fullName(), former.endDate());
            }
        }
        return result;
    }

    /**
     * Lit une fiche d'archive (commissioners.ec.europa.eu) : nom ({@code og:title}), portefeuille (meta description),
     * rôle et mandat (« COMMISSAIRE (2019-2024) »), et dates précises de la biographie (« Décembre 2019 - 2024 »,
     * première période suivie de « Commissaire » ou « Président »). Null si le nom ou le mandat manque.
     */
    static CommissionerData parseArchivePage(String html, String pageUrl) {
        String name = extractFirstMatch(html, META_TITLE_PATTERN);
        if (name == null || name.isBlank()) {
            return null;
        }
        name = decodeEntities(name).trim();
        String portfolio = extractFirstMatch(html, META_DESC_PATTERN);

        List<String> lines = textLines(html);
        Matcher role = null;
        for (String line : lines) {
            Matcher m = ARCHIVE_ROLE.matcher(line);
            if (m.matches()) {
                role = m;
                break;
            }
        }
        if (role == null) {
            return null;
        }
        boolean isPresident = role.group(1).toUpperCase(Locale.ROOT).matches("PR[ÉE]SIDENTE?");
        String start = role.group(2);
        String end = role.group(3);

        // Dates plus précises dans la biographie : la période dont la ligne suivante décrit le mandat
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher p = ARCHIVE_PERIOD.matcher(lines.get(i));
            String next = lines.get(i + 1).toLowerCase(Locale.ROOT);
            if (p.matches() && (next.startsWith("commissaire") || next.contains("président de la commission")
                    || next.startsWith("vice-président"))) {
                start = partialDate(p.group(2), p.group(1));
                end = partialDate(p.group(4), p.group(3));
                break;
            }
        }

        String[] parts = splitFirstLastName(name);
        return new CommissionerData(slug(name), name, parts[0], parts[1], null,
                portfolio == null ? null : decodeEntities(portfolio).trim(), start, end, "ended", pageUrl, isPresident);
    }

    /** « 2019 » + « Décembre » → « 2019-12 » ; sans mois reconnu → « 2019 ». */
    private static String partialDate(String year, String month) {
        int idx = month == null ? -1 : FRENCH_MONTHS.indexOf(month.toLowerCase(Locale.ROOT));
        return idx < 0 ? year : String.format("%s-%02d", year, idx + 1);
    }

    /** Texte de la page, une ligne par bloc, sans scripts ni styles. */
    private static List<String> textLines(String html) {
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", "\n");
        return java.util.Arrays.stream(decodeEntities(text).split("\\R"))
                .map(s -> s.replace(' ', ' ').replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String decodeEntities(String s) {
        return s.replace("&nbsp;", " ").replace("&#039;", "'").replace("&#39;", "'").replace("&rsquo;", "’")
                .replace("&quot;", "\"").replace("&amp;", "&");
    }

    /**
     * Vrai si le mandat est en cours ou terminé depuis moins que la durée de rétention. Une date de fin partielle
     * compte jusqu'au dernier jour de sa période ({@code 2024} → 31/12/2024, {@code 2024-11} → 30/11/2024).
     */
    static boolean isEligible(String endDate, LocalDate oldestEndDate) {
        if (endDate == null || endDate.isBlank()) {
            return true; // En fonction
        }
        if (oldestEndDate == null) {
            return true; // Rétention infinie
        }
        return !lastDayOf(endDate).isBefore(oldestEndDate);
    }

    /** Dernier jour couvert par une date FtM complète ou partielle. */
    static LocalDate lastDayOf(String ftmDate) {
        String[] p = ftmDate.trim().split("-");
        int year = Integer.parseInt(p[0]);
        if (p.length == 1) {
            return LocalDate.of(year, 12, 31);
        }
        LocalDate firstOfMonth = LocalDate.of(year, Integer.parseInt(p[1]), 1);
        return p.length == 2 ? firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth())
                : firstOfMonth.withDayOfMonth(Integer.parseInt(p[2]));
    }

    private void convertCommissioner(CommissionerData c, String sourceUrl, FtmDatasetBuilder dataset) {
        // 1. Personne
        FtmEntity person = dataset.newEntity("Person", c.slug());
        person.addValue("name", c.fullName());
        person.addValue("firstName", c.firstName());
        person.addValue("lastName", c.lastName());
        if (c.birthDate() != null && !c.birthDate().isBlank()) {
            person.addValue("birthDate", c.birthDate());
        }
        person.addValue("country", "lu");
        person.addValue("sourceUrl", c.pageUrl() != null ? c.pageUrl() : sourceUrl);
        dataset.addEntity(person);

        // 2. Position
        String posId = c.isPresident() ? "president-de-la-commission-europeenne" : "commissaire-europeen-luxembourgeois";
        String posName = c.isPresident() ? "Président de la Commission européenne" : POSITION_NAME;

        FtmEntity position = dataset.newEntity("Position", posId);
        position.addValue("name", posName);
        position.addValue("country", "lu");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.executive");
        dataset.addEntity(position);

        // 3. Occupancy
        String occId = dataset.buildEntityId("occupancy", c.slug(), posId);
        FtmEntity occupancy = dataset.newEntity("Occupancy", occId);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        if (c.startDate() != null) {
            occupancy.addValue("startDate", c.startDate());
        }
        if (c.endDate() != null) {
            occupancy.addValue("endDate", c.endDate());
        }
        occupancy.addValue("status", c.status());
        if (c.portfolio() != null && !c.portfolio().isBlank()) {
            occupancy.addValue("description", c.portfolio());
        }
        occupancy.addValue("sourceUrl", c.pageUrl() != null ? c.pageUrl() : sourceUrl);
        dataset.addEntity(occupancy);
    }

    /**
     * Résout dynamiquement l'URL source depuis le contexte (paramètre « sourceUrl », « url », « sourceLocation »
     * ou URL officielle de repli).
     */
    static String resolveSourceUrl(ParserContext ctx) {
        if (ctx != null && ctx.parameters() != null) {
            String url = ctx.parameters().get("sourceUrl");
            if (url == null || url.isBlank()) {
                url = ctx.parameters().get("url");
            }
            if (url == null || url.isBlank()) {
                url = ctx.parameters().get("sourceLocation");
            }
            if (url != null && !url.isBlank()) {
                return url.trim();
            }
        }
        return DEFAULT_SOURCE_URL;
    }

    private static Path sourceFile(ParserContext ctx) {
        if (ctx.inputFile() == null) {
            throw new IllegalStateException("Aucun fichier source reçu pour " + ctx.datasetName() + " : ce plugin lit "
                    + "un fichier HTML téléchargé par le core (HTTP_FILE).");
        }
        return ctx.inputFile();
    }

    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate now) {
        int years = parseYearsAfterEnd(parameters);
        return years > 0 ? now.minusYears(years) : null;
    }

    private static int parseYearsAfterEnd(Map<String, String> parameters) {
        if (parameters == null) {
            return 5;
        }
        String val = parameters.get("yearsAfterEnd");
        if (val == null || val.isBlank()) {
            return 5;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static String extractFirstMatch(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String slug(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String ascii = normalized.replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "item" : slug;
    }

    /**
     * Données d'un commissaire extrait. Les dates sont au format FtM, éventuellement partielles
     * ({@code 2019-12-01}, {@code 2019-12} ou {@code 2024}) : une fiche d'archive ne donne que le mois et l'année.
     */
    record CommissionerData(
            String slug,
            String fullName,
            String firstName,
            String lastName,
            String birthDate,
            String portfolio,
            String startDate,
            String endDate,
            String status,
            String pageUrl,
            boolean isPresident) {
    }

    /** Téléchargement d'une page (remplaçable dans les tests). */
    @FunctionalInterface
    interface PageFetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }
}
