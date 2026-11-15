package com.swiftcheck.aml.plugins.depep.federalgovernment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * <b>de_pep_federal_government</b> – Gouvernement fédéral d'Allemagne (Bundesregierung, Bundeskabinett ;
 * catégorie DE-a ; GwG § 1 (12) a) ; JO C/2023/724).
 *
 * <p>Explore la composition officielle du cabinet fédéral sur bundesregierung.de :
 * lit la page de synthèse /bundeskabinett, découvre les sous-liens de détail des ministres,
 * visite chaque sous-page pour en extraire le CV (Lebenslauf) et la date de prise de fonction (startDate).
 * Gère les alertes SOURCE_BROKEN et STRUCTURE_CHANGED en cas d'erreur de chargement ou d'évolution du DOM.
 */
@Extension
public class DePepFederalGovernmentParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalGovernmentParser.class);

    static final String ID_PREFIX = "de-pep-federal-government";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_URL = "https://www.bundesregierung.de/breg-de/bundesregierung/bundeskabinett";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Gouvernement fédéral)";
    static final String PHOTO_LICENCE = "© Bundesregierung";

    private static final Pattern CABINET_TEASER = Pattern.compile("(?si)<li[^>]*class=\"[^\"]*bpa-teaser-wrapper[^\"]*\"[^>]*>(.*?)</li>\\s*(?=<li[^>]*class=\"[^\"]*bpa-teaser-wrapper\"|</ul>|$)");

    private static final Pattern LINK = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']*/bundeskabinett/([^\"'#]+)(?:#[^\"']*)?)[\"'][^>]*class=[\"']bpa-link[\"']");
    private static final Pattern NAME = Pattern.compile("(?si)<span class=\"bpa-teaser-title-text-inner\">\\s*([^<]+?)\\s*</span>");
    private static final Pattern PHOTO = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"']");
    private static final Pattern CABINET_ITEM = Pattern.compile("(?si)<li[^>]*class=\"[^\"]*bpa-teaser-cabinet-item[^\"]*\"[^>]*>\\s*<a[^>]*>(.*?)</a>");

    // Patterns pour la page détaillée de chaque ministre (CV & date de début)
    private static final Pattern DETAIL_NAME = Pattern.compile("(?si)<h1[^>]*class=\"[^\"]*(?:bpa-title|bpa-accessibility)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</h1>");
    private static final Pattern VITA_ITEMS = Pattern.compile("(?si)<li[^>]*class=\"[^\"]*bpa-vita-item[^\"]*\"[^>]*>(.*?)</li>");
    private static final Pattern SEIT_DATE = Pattern.compile("(?i)Seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    record Member(String slug, String name, String role, String pageUrl, String photo, String startDate, String cvSummary) {
        Member withDetails(String newStartDate, String newCv) {
            return new Member(slug, name, role, pageUrl, photo,
                    newStartDate != null ? newStartDate : startDate,
                    newCv != null ? newCv : cvSummary);
        }
    }

    record CrawlResult(Map<String, Member> members, List<String> brokenSources, List<String> structureWarnings) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        for (Member m : crawl.members().values()) {
            write(m, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.members().size()).append(" membres du cabinet fédéral extraits de bundesregierung.de.");
        if (!crawl.brokenSources().isEmpty()) {
            msg.append(" [ALERT SOURCE_BROKEN: ").append(crawl.brokenSources().size()).append(" source(s) en échec: ")
               .append(String.join(", ", crawl.brokenSources())).append("]");
        }
        if (!crawl.structureWarnings().isEmpty()) {
            msg.append(" [ALERT STRUCTURE_CHANGED: ").append(String.join(", ", crawl.structureWarnings())).append("]");
        }

        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);

        List<SourceRecord> records = new ArrayList<>();
        for (Member m : crawl.members().values()) {
            records.add(new SourceRecord(ids.buildEntityId(m.slug()), "Person", m.name(), m.pageUrl()));
        }
        return records;
    }

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        Map<String, Member> membersBySlug = new LinkedHashMap<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> structureWarnings = new ArrayList<>();

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 1. Si un fichier d'entrée statique est fourni (ex: tests unitaires ou snapshot)
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                List<Member> list = extractMembers(html);
                if (list.isEmpty()) {
                    structureWarnings.add("0 profil extrait du fichier d'entrée");
                }
                for (Member m : list) {
                    membersBySlug.put(m.slug(), m);
                }
                return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
            } catch (Exception e) {
                brokenSources.add("inputFile: " + e.getMessage());
                return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
            }
        }

        // 2. Extraire la liste des URLs cibles (soit le paramètre "urls", soit "url", soit DEFAULT_URL)
        List<String> targetUrls = resolveTargetUrls(parameters);

        for (String rawUrl : targetUrls) {
            String cleanUrl = rawUrl.trim();
            if (cleanUrl.isEmpty()) continue;

            String html;
            try {
                html = fetchSafe(client, cleanUrl);
                if (html == null) {
                    brokenSources.add(cleanUrl);
                    continue;
                }
            } catch (Exception e) {
                log.warn("SOURCE_BROKEN: Échec sur l'URL {}: {}", cleanUrl, e.getMessage());
                brokenSources.add(cleanUrl);
                continue;
            }

            // Si c'est la page racine ou une liste
            if (cleanUrl.endsWith("/bundeskabinett") || cleanUrl.endsWith("/bundeskabinett/")) {
                List<Member> list = extractMembers(html);
                if (list.isEmpty()) {
                    structureWarnings.add("Structure modifiée sur " + cleanUrl + " (0 membre détecté)");
                } else {
                    for (Member m : list) {
                        membersBySlug.put(m.slug(), m);
                    }
                }
            } else {
                // C'est une URL de fiche individuelle directe
                Member detail = parseDetailPage(cleanUrl, html);
                if (detail != null) {
                    Member existing = membersBySlug.get(detail.slug());
                    if (existing != null) {
                        membersBySlug.put(detail.slug(), existing.withDetails(detail.startDate(), detail.cvSummary()));
                    } else {
                        membersBySlug.put(detail.slug(), detail);
                    }
                } else {
                    structureWarnings.add("Structure modifiée sur la fiche " + cleanUrl);
                }
            }
        }

        // 3. Approche "Page principale -> Sous-liens" :
        // Pour tous les membres trouvés sur la page principale dont on n'a pas encore le CV,
        // on visite leur page individuelle si elle n'a pas encore été traitée
        for (Map.Entry<String, Member> entry : new ArrayList<>(membersBySlug.entrySet())) {
            Member m = entry.getValue();
            if (m.cvSummary() == null && m.pageUrl() != null && m.pageUrl().startsWith("http")) {
                try {
                    String detailHtml = fetchSafe(client, m.pageUrl());
                    if (detailHtml != null) {
                        Member detail = parseDetailPage(m.pageUrl(), detailHtml);
                        if (detail != null) {
                            membersBySlug.put(m.slug(), m.withDetails(detail.startDate(), detail.cvSummary()));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Impossible d'enrichir le profil {} depuis {}: {}", m.name(), m.pageUrl(), e.getMessage());
                }
            }
        }

        return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
    }

    static List<String> resolveTargetUrls(Map<String, String> parameters) {
        List<String> list = new ArrayList<>();
        String urlsParam = parameters.get("urls");
        if (urlsParam != null && !urlsParam.isBlank()) {
            // Support JSON array ou liste séparée par des retours chariot ou virgules
            if (urlsParam.trim().startsWith("[")) {
                Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(urlsParam);
                while (m.find()) {
                    list.add(m.group(1));
                }
            } else {
                for (String part : urlsParam.split("[\r\n,]+")) {
                    if (!part.isBlank()) list.add(part.trim());
                }
            }
        }
        if (list.isEmpty()) {
            list.add(parameters.getOrDefault("url", DEFAULT_URL).trim());
        }
        return list;
    }

    private String fetchSafe(HttpClient client, String rawUrl) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            log.warn("URL malformée (SOURCE_BROKEN) : {}", rawUrl);
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            log.warn("HTTP {} pour {} (SOURCE_BROKEN)", resp.statusCode(), rawUrl);
            return null;
        }
        return resp.body();
    }

    static List<Member> extractMembers(String html) {
        List<Member> list = new ArrayList<>();
        Matcher m = CABINET_TEASER.matcher(html);
        while (m.find()) {
            String block = m.group(1);

            Matcher linkM = LINK.matcher(block);
            if (!linkM.find()) {
                continue;
            }
            String rawUrl = linkM.group(1);
            String slug = linkM.group(2);
            String pageUrl = rawUrl.startsWith("http") ? rawUrl : "https://www.bundesregierung.de" + rawUrl;

            Matcher nameM = NAME.matcher(block);
            String name = nameM.find() ? Html.text(nameM.group(1)) : slug.replace('-', ' ');

            String photo = null;
            Matcher photoM = PHOTO.matcher(block);
            if (photoM.find()) {
                String src = photoM.group(1);
                photo = src.startsWith("http") ? src : "https://www.bundesregierung.de" + src;
            }

            String role = "Mitglied des Bundeskabinetts";
            Matcher roleM = CABINET_ITEM.matcher(block);
            while (roleM.find()) {
                String itemText = Html.text(roleM.group(1));
                if (!itemText.equalsIgnoreCase("Lebenslauf") && !itemText.isBlank()) {
                    role = itemText;
                    break;
                }
            }

            list.add(new Member(slug, cleanName(name), role, pageUrl, photo, null, null));
        }
        return list;
    }

    static Member parseDetailPage(String url, String html) {
        String slug = extractSlug(url);
        if (slug == null || slug.isBlank()) return null;

        String name = null;
        Matcher nameM = DETAIL_NAME.matcher(html);
        if (nameM.find()) {
            name = Html.text(nameM.group(1));
        }
        if (name == null || name.isBlank()) {
            name = slug.replace('-', ' ');
        }

        String startDate = null;
        List<String> cvItems = new ArrayList<>();

        Matcher vitaM = VITA_ITEMS.matcher(html);
        while (vitaM.find()) {
            String itemHtml = vitaM.group(1);
            String itemText = Html.text(itemHtml);
            if (!itemText.isBlank()) {
                cvItems.add(itemText);
            }
            Matcher seitM = SEIT_DATE.matcher(itemText);
            if (seitM.find()) {
                startDate = parseIsoDate(seitM.group(1));
            }
        }

        // Si la date n'est pas trouvée dans la vita, chercher dans la description ou le texte global
        if (startDate == null) {
            Matcher textSeitM = SEIT_DATE.matcher(html);
            if (textSeitM.find()) {
                startDate = parseIsoDate(textSeitM.group(1));
            }
        }

        String cvSummary = cvItems.isEmpty() ? null : String.join(" | ", cvItems);
        return new Member(slug, cleanName(name), "Mitglied des Bundeskabinetts", url, null, startDate, cvSummary);
    }

    static String extractSlug(String url) {
        Matcher m = Pattern.compile("/bundeskabinett/([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        // Année seule (ex: "2025")
        if (s.matches("^\\d{4}$")) {
            return s + "-01-01";
        }

        // Format jour. mois année (ex: "6. mai 2025" ou "06. Mai 2025")
        Matcher m = Pattern.compile("(\\d{1,2})\\.\\s*([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find()) {
            int day = Integer.parseInt(m.group(1));
            String monthName = m.group(2);
            String year = m.group(3);
            String month = GERMAN_MONTHS.getOrDefault(monthName, "01");
            return String.format("%s-%s-%02d", year, month, day);
        }

        return null;
    }

    private static void write(Member m, FtmDatasetBuilder dataset) {
        boolean isChancellor = isChancellor(m.role());
        String posName = isChancellor ? "Chancelier fédéral (Bundeskanzler)"
                : "Ministre fédéral ou ministre d'État (Bundesminister, Staatsminister)";

        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        String[] parts = m.name().split("\\s+", 2);
        person.addValue("firstName", parts[0]);
        if (parts.length > 1) person.addValue("lastName", parts[1]);
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", m.role());
        person.addValue("sourceUrl", m.pageUrl());
        if (m.cvSummary() != null) {
            person.addValue("summary", m.cvSummary());
            person.addValue("notes", "Lebenslauf : " + m.cvSummary());
        }
        dataset.addEntity(person);

        if (m.photo() != null) {
            dataset.addMedia(person.getId(), m.photo(), m.pageUrl(), PHOTO_LICENCE);
        }

        FtmEntity position = dataset.newEntity("Position", isChancellor ? "chancellor" : "minister", m.slug());
        position.addValue("name", posName);
        position.addValue("country", "de");
        position.addValue("organization", "Gouvernement fédéral");
        position.addValue("topics", "gov.national");
        position.addValue("topics", isChancellor ? "gov.headofgov" : "gov.executive");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), "cabinet");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", m.role());
        occupancy.addValue("sourceUrl", m.pageUrl());
        if (m.startDate() != null) {
            occupancy.addValue("startDate", m.startDate());
        }
        dataset.addEntity(occupancy);
    }

    static boolean isChancellor(String role) {
        if (role == null) return false;
        String r = role.toLowerCase();
        return r.contains("bundeskanzler") && !r.contains("chefin") && !r.contains("chef des");
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)\\b(Prof\\.|Dr\\.|h\\.c\\.)\\b", "").replaceAll("\\s+", " ").trim();
    }
}
