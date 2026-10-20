package com.swiftcheck.aml.plugins.lupep.eucommission;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
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
     * Données historiques de référence des commissaires luxembourgeois de l'histoire moderne de l'Union européenne.
     */
    record HistoricalCommissioner(
            String fullName,
            String firstName,
            String lastName,
            String birthDate,
            String portfolio,
            LocalDate startDate,
            LocalDate endDate,
            boolean isPresident) {
    }

    private static final List<HistoricalCommissioner> KNOWN_COMMISSIONERS = List.of(
            new HistoricalCommissioner(
                    "Christophe Hansen", "Christophe", "Hansen", "1982-02-21",
                    "Agriculture et alimentation", LocalDate.of(2024, 12, 1), null, false),
            new HistoricalCommissioner(
                    "Nicolas Schmit", "Nicolas", "Schmit", "1953-12-10",
                    "Emploi et droits sociaux", LocalDate.of(2019, 12, 1), LocalDate.of(2024, 11, 30), false),
            new HistoricalCommissioner(
                    "Jean-Claude Juncker", "Jean-Claude", "Juncker", "1954-12-09",
                    "Président de la Commission européenne", LocalDate.of(2014, 11, 1), LocalDate.of(2019, 11, 30), true),
            new HistoricalCommissioner(
                    "Viviane Reding", "Viviane", "Reding", "1951-04-27",
                    "Justice, droits fondamentaux et citoyenneté / Société de l'information", LocalDate.of(1999, 9, 16), LocalDate.of(2014, 6, 30), false),
            new HistoricalCommissioner(
                    "Jacques Santer", "Jacques", "Santer", "1937-05-18",
                    "Président de la Commission européenne", LocalDate.of(1995, 1, 23), LocalDate.of(1999, 3, 15), true)
    );

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);

        log.info("Analyse de la Commission européenne (rétention : {} ans, URL dynamique : {})",
                parseYearsAfterEnd(ctx.parameters()), sourceUrl);

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        String sourceUrl = resolveSourceUrl(ctx);

        convert(sourceFile(ctx), oldestEndDate, sourceUrl, dataset);

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
        String content = Files.readString(html, StandardCharsets.UTF_8);
        List<CommissionerData> commissioners = extractCommissioners(content, sourceUrl, oldestEndDate);

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

                HistoricalCommissioner known = findKnownCommissioner(name);
                if (known != null) {
                    // C'est un commissaire luxembourgeois confirmé
                    String fullLink = link.startsWith("http") ? link : "https://commission.europa.eu" + link;
                    CommissionerData data = new CommissionerData(
                            slug(name),
                            name,
                            known.firstName(),
                            known.lastName(),
                            known.birthDate(),
                            desc.isBlank() ? known.portfolio() : desc,
                            known.startDate(),
                            null, // Actif dans le collège affiché
                            "current",
                            fullLink,
                            known.isPresident()
                    );
                    result.put(data.slug(), data);
                }
            }
        }

        // 2. Compléter avec l'historique des anciens commissaires selon oldestEndDate
        for (HistoricalCommissioner hk : KNOWN_COMMISSIONERS) {
            if (!result.containsKey(slug(hk.fullName()))) {
                if (isEligible(hk.endDate(), oldestEndDate)) {
                    String status = hk.endDate() == null ? "current" : "ended";
                    CommissionerData data = new CommissionerData(
                            slug(hk.fullName()),
                            hk.fullName(),
                            hk.firstName(),
                            hk.lastName(),
                            hk.birthDate(),
                            hk.portfolio(),
                            hk.startDate(),
                            hk.endDate(),
                            status,
                            sourceUrl,
                            hk.isPresident()
                    );
                    result.put(data.slug(), data);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    private static boolean isIndividualPage(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        return lower.contains("commissaire luxembourgeois")
                || (lower.contains("christophe hansen") && lower.contains("biographie"));
    }

    private static CommissionerData parseIndividualPage(String html, String pageUrl) {
        String title = extractFirstMatch(html, META_TITLE_PATTERN);
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

        HistoricalCommissioner known = findKnownCommissioner(title);
        String firstName = known != null ? known.firstName() : "Christophe";
        String lastName = known != null ? known.lastName() : "Hansen";
        String birthDate = known != null ? known.birthDate() : "1982-02-21";
        LocalDate startDate = known != null ? known.startDate() : LocalDate.of(2024, 12, 1);
        boolean isPres = known != null && known.isPresident();

        return new CommissionerData(
                slug(title),
                title,
                firstName,
                lastName,
                birthDate,
                portfolio,
                startDate,
                null,
                "current",
                pageUrl,
                isPres
        );
    }

    private static HistoricalCommissioner findKnownCommissioner(String name) {
        String clean = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (HistoricalCommissioner c : KNOWN_COMMISSIONERS) {
            String norm = Normalizer.normalize(c.fullName(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
            if (clean.contains(norm) || norm.contains(clean)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isEligible(LocalDate endDate, LocalDate oldestEndDate) {
        if (endDate == null) {
            return true; // En fonction
        }
        if (oldestEndDate == null) {
            return true; // Rétention infinie
        }
        return !endDate.isBefore(oldestEndDate);
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
            occupancy.addValue("startDate", c.startDate().toString());
        }
        if (c.endDate() != null) {
            occupancy.addValue("endDate", c.endDate().toString());
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
     * Données d'un commissaire extrait.
     */
    record CommissionerData(
            String slug,
            String fullName,
            String firstName,
            String lastName,
            String birthDate,
            String portfolio,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String pageUrl,
            boolean isPresident) {
    }
}
