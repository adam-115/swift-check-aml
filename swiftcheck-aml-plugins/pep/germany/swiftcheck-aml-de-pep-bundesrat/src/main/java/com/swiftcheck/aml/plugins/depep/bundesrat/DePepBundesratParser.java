package com.swiftcheck.aml.plugins.depep.bundesrat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <b>de_pep_bundesrat</b> – Membres du Bundesrat et membres des gouvernements des 16 Länder (catégories DE-b et DE-a ;
 * GwG § 1 (12) a) et b) ; JO C/2023/724).
 *
 * <p>Explore les pages officielles du Bundesrat sur bundesrat.de :
 * lit les listes (mitglieder-node.html, praesidium-node.html, staendiger-beirat.html, laender/xx-node.html),
 * visite les fiches détaillées (SharedDocs/personen/DE/laender/...) pour le CV et la date de prise de fonction,
 * et analyse aenderungen-node.html pour identifier les membres sortis et appliquer le refroidissement de 12 mois.
 * Gère les alertes SOURCE_BROKEN et STRUCTURE_CHANGED en cas d'erreur de chargement ou d'évolution du DOM.
 */
@Extension
public class DePepBundesratParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesratParser.class);

    static final String ID_PREFIX = "de-pep-bundesrat";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_URL = "https://www.bundesrat.de/DE/bundesrat/mitglieder/mitglieder-node.html?param3=Alle";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesrat)";

    private static final Pattern VCARD_BLOCK = Pattern.compile("(?si)<div[^>]*class=\"[^\"]*vcard[^\"]*\"[^>]*>(.*?)</li>");
    private static final Pattern PERSON_LINK = Pattern.compile("(?i)<a[^>]+href=[\"']([^\"']*/SharedDocs/personen/DE/laender/([^/]+)/([^.]+)\\.html)[\"']");
    private static final Pattern PHOTO = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"'][^>]*class=[\"']photo[\"']");
    private static final Pattern PHOTO_TITLE = Pattern.compile("(?i)Foto:\\s*([^\"']+)");

    private static final Pattern NAME_PARTY = Pattern.compile("(?si)<h2[^>]*>\\s*<a[^>]*>\\s*([^<|]+?)(?:&nbsp;|\\s)*(?:&#x007C;|\\|)(?:&nbsp;|\\s)*<span class=\"organization-name\">([^<]+)</span>\\s*</a>\\s*</h2>");
    private static final Pattern STATE = Pattern.compile("(?si)<p class=\"state\">([^<]+)</p>");
    private static final Pattern FUNCTION = Pattern.compile("(?si)<h3[^>]*>([^<]+)</h3>");

    // Patterns pour la page de détail d'un membre (CV / Zur Person / Dates)
    private static final Pattern DETAIL_NAME_PARTY = Pattern.compile("(?si)<h1[^>]*>\\s*([^<|]+?)(?:&nbsp;|\\s)*(?:&#x007C;|\\|)(?:&nbsp;|\\s)*(?:<span class=\"organization-name\">([^<]+)</span>)?\\s*</h1>");
    private static final Pattern DETAIL_FUNCTION = Pattern.compile("(?si)<h2[^>]*>\\s*([^<]+?)\\s*</h2>");
    private static final Pattern ZUR_PERSON_BLOCK = Pattern.compile("(?si)<div id=\"person\"[^>]*>.*?<div class=\"text-box\">\\s*(.*?)\\s*</div>");
    private static final Pattern SEIT_DATE = Pattern.compile("(?i)seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\d{2}\\.\\d{4}|\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    // Patterns pour la page des changements (aenderungen-node.html)
    // Ex: "Staatsminister a. D. Rainer Robra ist mit Ablauf des 5. Oktober 2026 ... ausgeschieden"
    private static final Pattern AUSGESCHIEDEN = Pattern.compile("(?si)(?:Am|mit Ablauf des)\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4})[^.<>]*?<strong>\\s*([^<]+?)\\s*</strong>[^.<>]*?ausgeschieden");
    private static final Pattern AUSGESCHIEDEN_INV = Pattern.compile("(?si)<strong>\\s*([^<]+?)\\s*</strong>[^.<>]*?ist mit Ablauf des\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4})[^.<>]*?ausgeschieden");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    record Member(String slug, String landCode, String name, String party, String state, String function,
                  String pageUrl, String photo, String photoCredit, String startDate, String endDate,
                  String status, String cvSummary) {

        Member withDetails(String newStartDate, String newCv) {
            return new Member(slug, landCode, name, party, state, function, pageUrl, photo, photoCredit,
                    newStartDate != null ? newStartDate : startDate, endDate, status,
                    newCv != null ? newCv : cvSummary);
        }

        Member withDeparture(String departureDate, String newStatus) {
            return new Member(slug, landCode, name, party, state, function, pageUrl, photo, photoCredit,
                    startDate, departureDate, newStatus, cvSummary);
        }
    }

    record CrawlResult(Map<String, Member> members, List<String> brokenSources, List<String> structureWarnings) {
    }

    record Report(int totalMembers, int ministerPresidents, int ministers, int coolingOff) {
        String message() {
            return String.format("%d membres du Bundesrat extraits (dont %d ministres-présidents, %d ministres de Länder et %d en période de refroidissement).",
                    totalMembers, ministerPresidents, ministers, coolingOff);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        int mpCount = 0;
        int minCount = 0;
        int coolingCount = 0;

        for (Member m : crawl.members().values()) {
            boolean isMp = isMinisterPresident(m.function());
            if (isMp) mpCount++; else minCount++;
            if ("cooling_off".equals(m.status())) coolingCount++;
            write(m, isMp, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());
        Report report = new Report(crawl.members().size(), mpCount, minCount, coolingCount);

        StringBuilder msg = new StringBuilder(report.message());
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

        // 1. Si un fichier d'entrée statique est fourni (tests unitaires ou archive)
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                List<Member> list = extractMembers(html);
                if (list.isEmpty()) {
                    structureWarnings.add("0 membre extrait du fichier d'entrée");
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

        List<String> targetUrls = resolveTargetUrls(parameters);
        Map<String, String> departedMembers = new LinkedHashMap<>(); // Nom -> date de fin ISO

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
                log.warn("SOURCE_BROKEN sur {}: {}", cleanUrl, e.getMessage());
                brokenSources.add(cleanUrl);
                continue;
            }

            // A. Page des changements (aenderungen-node.html) : détection des sorties récentes
            if (cleanUrl.contains("aenderungen")) {
                Map<String, String> departed = parseDepartures(html);
                departedMembers.putAll(departed);
                continue;
            }

            // B. Pages de liste (mitglieder-node.html, praesidium, staendiger-beirat, laender/*-node.html)
            if (cleanUrl.contains("node.html") && !cleanUrl.contains("/personen/")) {
                List<Member> list = extractMembers(html);
                if (list.isEmpty() && !cleanUrl.contains("aenderungen")) {
                    structureWarnings.add("Structure modifiée ou 0 membre sur " + cleanUrl);
                } else {
                    for (Member m : list) {
                        membersBySlug.putIfAbsent(m.slug(), m);
                    }
                }
            } else if (cleanUrl.contains("/personen/")) {
                // C. Fiche individuelle directe
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

        // 2. Approche "Page principale -> Sous-liens" :
        // Pour les membres sans CV, visite de leur fiche person
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

        // 3. Application de la période de refroidissement de 12 mois issue de aenderungen-node.html
        LocalDate today = LocalDate.now();
        LocalDate cutoff12Months = today.minusMonths(12);

        for (Map.Entry<String, String> departure : departedMembers.entrySet()) {
            String depName = cleanName(departure.getKey());
            String depDateStr = departure.getValue();
            LocalDate depDate = LocalDate.parse(depDateStr);

            String status = depDate.isBefore(cutoff12Months) ? "expired" : "cooling_off";

            // Chercher si ce membre est déjà dans le dataset
            boolean found = false;
            for (Map.Entry<String, Member> entry : membersBySlug.entrySet()) {
                if (entry.getValue().name().equalsIgnoreCase(depName)) {
                    membersBySlug.put(entry.getKey(), entry.getValue().withDeparture(depDateStr, status));
                    found = true;
                    break;
                }
            }
            if (!found && !depDate.isBefore(cutoff12Months)) {
                // Ancien membre sorti depuis moins de 12 mois non présent dans la liste active : on le conserve en refroidissement !
                String slug = depName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                Member departed = new Member(slug, "de", depName, null, null, "Ehemaliges Mitglied des Bundesrates",
                        null, null, null, null, depDateStr, "cooling_off", "Aus dem Bundesrat ausgeschieden am " + depDateStr);
                membersBySlug.put(slug, departed);
            }
        }

        return new CrawlResult(membersBySlug, brokenSources, structureWarnings);
    }

    static List<String> resolveTargetUrls(Map<String, String> parameters) {
        List<String> list = new ArrayList<>();
        String urlsParam = parameters.get("urls");
        if (urlsParam != null && !urlsParam.isBlank()) {
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
        Matcher m = VCARD_BLOCK.matcher(html);
        while (m.find()) {
            String block = m.group(1);
            Matcher linkM = PERSON_LINK.matcher(block);
            if (!linkM.find()) {
                continue;
            }
            String pageUrl = linkM.group(1);
            if (!pageUrl.startsWith("http")) {
                pageUrl = "https://www.bundesrat.de" + pageUrl;
            }
            String landCode = linkM.group(2);
            String slug = linkM.group(3);

            String rawName = slug.replace('-', ' ');
            String party = null;
            Matcher nameM = NAME_PARTY.matcher(block);
            if (nameM.find()) {
                rawName = Html.text(nameM.group(1));
                party = Html.text(nameM.group(2));
            }

            Matcher stateM = STATE.matcher(block);
            String state = stateM.find() ? Html.text(stateM.group(1)) : null;

            Matcher funcM = FUNCTION.matcher(block);
            String function = funcM.find() ? Html.text(funcM.group(1)) : null;

            String photo = null;
            String photoCredit = null;
            Matcher photoM = PHOTO.matcher(block);
            if (photoM.find()) {
                photo = photoM.group(1);
                Matcher creditM = PHOTO_TITLE.matcher(block);
                if (creditM.find()) {
                    photoCredit = "Foto: " + Html.text(creditM.group(1));
                }
            }

            list.add(new Member(slug, landCode, cleanName(rawName), party, state, function, pageUrl, photo, photoCredit, null, null, "current", null));
        }
        return list;
    }

    static Member parseDetailPage(String url, String html) {
        String slug = extractSlug(url);
        if (slug == null || slug.isBlank()) return null;

        String name = null;
        String party = null;
        Matcher nameM = DETAIL_NAME_PARTY.matcher(html);
        if (nameM.find()) {
            name = Html.text(nameM.group(1));
            party = nameM.group(2) != null ? Html.text(nameM.group(2)) : null;
        }
        if (name == null || name.isBlank()) {
            name = slug.replace('-', ' ');
        }

        String function = null;
        Matcher funcM = DETAIL_FUNCTION.matcher(html);
        if (funcM.find()) {
            function = Html.text(funcM.group(1));
        }

        String cvSummary = null;
        String startDate = null;
        Matcher personM = ZUR_PERSON_BLOCK.matcher(html);
        if (personM.find()) {
            cvSummary = Html.text(personM.group(1));
            Matcher seitM = SEIT_DATE.matcher(cvSummary);
            if (seitM.find()) {
                startDate = parseIsoDate(seitM.group(1));
            }
        }

        if (startDate == null) {
            Matcher seitM = SEIT_DATE.matcher(html);
            if (seitM.find()) {
                startDate = parseIsoDate(seitM.group(1));
            }
        }

        String landCode = extractLandCode(url);
        return new Member(slug, landCode != null ? landCode : "de", cleanName(name), party, null, function, url, null, null, startDate, null, "current", cvSummary);
    }

    static Map<String, String> parseDepartures(String html) {
        Map<String, String> departures = new LinkedHashMap<>();
        // Découper par paragraphe <p>...</p>
        Matcher pMatcher = Pattern.compile("(?si)<p>(.*?)</p>").matcher(html);
        while (pMatcher.find()) {
            String pText = pMatcher.group(1);
            if (!pText.toLowerCase().contains("ausgeschieden") && !pText.toLowerCase().contains("endeten")) {
                continue;
            }

            // Chercher la date dans ce paragraphe
            String date = null;
            Matcher dateM = Pattern.compile("(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4})").matcher(pText);
            if (dateM.find()) {
                date = parseIsoDate(dateM.group(1));
            }
            if (date == null) continue;

            // Chercher tous les noms en gras <strong>...</strong>
            Matcher nameM = Pattern.compile("<strong>\\s*([^<]+?)\\s*</strong>").matcher(pText);
            while (nameM.find()) {
                String clean = cleanName(nameM.group(1));
                if (!clean.isBlank() && !clean.equalsIgnoreCase("Hinweis") && !clean.contains("Ablauf")) {
                    departures.put(clean, date);
                }
            }
        }
        return departures;
    }

    static String extractSlug(String url) {
        Matcher m = Pattern.compile("/([^/]+)\\.html").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String extractLandCode(String url) {
        Matcher m = Pattern.compile("/laender/([^/]+)/").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);

        // Format DD.MM.YYYY
        if (s.matches("^\\d{1,2}\\.\\d{2}\\.\\d{4}$")) {
            String[] p = s.split("\\.");
            return String.format("%s-%02d-%02d", p[2], Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }

        // Format YYYY
        if (s.matches("^\\d{4}$")) {
            return s + "-01-01";
        }

        // Format D. MMMM YYYY
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

    private static void write(Member m, boolean isMp, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        if (m.party() != null) person.addValue("political", m.party());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", "Membre du Bundesrat");
        if (m.function() != null) {
            person.addValue("position", m.function());
        }
        person.addValue("sourceUrl", m.pageUrl());
        if (m.cvSummary() != null) {
            person.addValue("summary", m.cvSummary());
            person.addValue("notes", "Lebenslauf : " + m.cvSummary());
        }
        dataset.addEntity(person);

        if (m.photo() != null) {
            dataset.addMedia(person.getId(), m.photo(), m.pageUrl(), m.photoCredit() != null ? m.photoCredit() : "© Bundesrat");
        }

        // 1. Position Bundesrat
        FtmEntity posBundesrat = dataset.newEntity("Position", "bundesrat-member");
        posBundesrat.addValue("name", "Membre du Bundesrat (Mitglied des Bundesrates)");
        posBundesrat.addValue("country", "de");
        posBundesrat.addValue("organization", "Bundesrat");
        posBundesrat.addValue("topics", "gov.national");
        posBundesrat.addValue("topics", "gov.legislative");
        dataset.addEntity(posBundesrat);

        FtmEntity occBundesrat = dataset.newEntity("Occupancy", m.slug(), "bundesrat");
        occBundesrat.addValue("holder", person.getId());
        occBundesrat.addValue("post", posBundesrat.getId());
        occBundesrat.addValue("status", m.status());
        occBundesrat.addValue("politicalGroup", m.party());
        occBundesrat.addValue("sourceUrl", m.pageUrl());
        if (m.startDate() != null) {
            occBundesrat.addValue("startDate", m.startDate());
        }
        if (m.endDate() != null) {
            occBundesrat.addValue("endDate", m.endDate());
        }
        dataset.addEntity(occBundesrat);

        // 2. Position Gouvernement de Land (DE-a)
        String regionalPosName = isMp ? "Ministre-président d'un Land (Ministerpräsident)"
                : "Ministre d'un gouvernement de Land (Landesminister, Senator)";
        String orgName = m.state() != null ? "Gouvernement du Land " + m.state() : "Gouvernement de Land";

        FtmEntity posLand = dataset.newEntity("Position", isMp ? "land-minister-president" : "land-minister", m.landCode());
        posLand.addValue("name", regionalPosName);
        posLand.addValue("country", "de");
        posLand.addValue("organization", orgName);
        posLand.addValue("topics", "gov.regional");
        posLand.addValue("topics", "gov.executive");
        dataset.addEntity(posLand);

        FtmEntity occLand = dataset.newEntity("Occupancy", m.slug(), "regional-exec");
        occLand.addValue("holder", person.getId());
        occLand.addValue("post", posLand.getId());
        occLand.addValue("status", m.status());
        if (m.function() != null) {
            occLand.addValue("description", m.function());
        }
        occLand.addValue("sourceUrl", m.pageUrl());
        if (m.startDate() != null) {
            occLand.addValue("startDate", m.startDate());
        }
        if (m.endDate() != null) {
            occLand.addValue("endDate", m.endDate());
        }
        dataset.addEntity(occLand);
    }

    static boolean isMinisterPresident(String function) {
        if (function == null) return false;
        String f = function.toLowerCase();
        return f.contains("ministerpräsident") || f.contains("regierende") || f.contains("präsident des senats")
                || f.contains("bürgermeister") && (f.contains("hamburg") || f.contains("bremen") || f.contains("berlin"));
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)\\b(Prof\\.|Dr\\.|h\\.c\\.|a\\.\\s*D\\.)\\b", "").replaceAll("\\s+", " ").trim();
    }
}
