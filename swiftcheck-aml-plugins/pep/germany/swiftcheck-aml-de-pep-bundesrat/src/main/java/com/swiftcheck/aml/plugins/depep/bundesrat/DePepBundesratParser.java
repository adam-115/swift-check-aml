package com.swiftcheck.aml.plugins.depep.bundesrat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <b>de_pep_bundesrat</b> – Membres du Bundesrat, son Präsidium, son Ständiger Beirat et les membres des
 * gouvernements des 16 Länder qui y siègent (GwG § 1 Abs. 12 ; classes B2, A1 et A3 de la Story 2).
 * <p>
 * Pages lues sur bundesrat.de :
 * <ul>
 *   <li>listes de membres ({@code mitglieder-node.html}, {@code laender/xx-node.html}) : blocs {@code vcard} ;</li>
 *   <li>Präsidium : la page {@code praesidium-node.html} renvoie vers {@code praesidium/aktuell/aktuell.html}, qui
 *       donne le président et les deux vice-présidents ;</li>
 *   <li>Ständiger Beirat ({@code staendiger-beirat.html}) : les 16 Bevollmächtigte des Länder, qui ne sont pas tous
 *       membres du Bundesrat (blocs {@code rack-teaser}) ;</li>
 *   <li>fiches individuelles ({@code SharedDocs/personen/...}) : fonctions d'origine, CV, date de prise de
 *       fonction ;</li>
 *   <li>{@code aenderungen-node.html} : sorties (« ausgeschieden », « a. D. ») qui ouvrent 12 mois de COOLING.</li>
 * </ul>
 * Les titres académiques vont dans {@code title}, jamais dans le nom ; les fonctions gardent leur libellé allemand.
 * Classe PEP, niveau (FEDERAL / LAENDER), empreinte SHA-256 et date d'extraction de chaque page vont dans
 * {@code notes}, car le schéma FtM n'a pas de propriété pour eux.
 */
@Extension
public class DePepBundesratParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesratParser.class);

    static final String ID_PREFIX = "de-pep-bundesrat";
    static final String TOPIC = "role.pep";
    static final String SITE = "https://www.bundesrat.de";

    static final String DEFAULT_URL = "https://www.bundesrat.de/DE/bundesrat/mitglieder/mitglieder-node.html?param3=Alle";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesrat)";

    /** Ce que la personne est au Bundesrat ; une personne peut cumuler plusieurs rôles. */
    enum Role { MEMBER, PRESIDENT, VICE_PRESIDENT, BEIRAT }

    private static final Pattern VCARD_BLOCK = Pattern
            .compile("(?si)<div[^>]*class=\"[^\"]*vcard[^\"]*\"[^>]*>(.*?)</li>");
    private static final Pattern RACK_TEASER = Pattern
            .compile("(?si)<div class=\"rack-teaser\">(.*?)</a>");
    private static final Pattern PERSON_LINK = Pattern
            .compile("(?i)<a[^>]+href=[\"']([^\"']*/SharedDocs/personen/DE/laender/([^/]+)/([^./]+)\\.html)[\"']");
    private static final Pattern PHOTO = Pattern
            .compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"'][^>]*class=[\"']photo[\"']");
    private static final Pattern PHOTO_TITLE = Pattern.compile("(?i)Foto:\\s*([^\"']+)");

    private static final Pattern NAME_PARTY = Pattern.compile(
            "(?si)<h2[^>]*>\\s*<a[^>]*>\\s*([^<|]+?)(?:&nbsp;|\\s)*(?:&#x007C;|\\|)(?:&nbsp;|\\s)*<span class=\"organization-name\">([^<]+)</span>\\s*</a>\\s*</h2>");
    private static final Pattern NAME_ONLY = Pattern.compile("(?si)<h2[^>]*>\\s*<a[^>]*>\\s*([^<|]+?)\\s*</a>\\s*</h2>");
    private static final Pattern STATE = Pattern.compile("(?si)<p class=\"(?:state|bundesland)\">([^<]+)</p>");
    private static final Pattern FUNCTION = Pattern.compile("(?si)<h3[^>]*>(.*?)</h3>");
    private static final Pattern TEASER_PARTY = Pattern.compile("(?si)<p class=\"organization-name\">([^<]+)</p>");
    private static final Pattern TEASER_PLAIN_P = Pattern.compile("(?si)</h3>\\s*<p>([^<]+)</p>");
    private static final Pattern PRAESIDIUM_LINK = Pattern
            .compile("(?i)href=\"([^\"]*/DE/bundesrat/praesidium/aktuell/[^\"]+\\.html)\"");

    // Fiche individuelle : bloc d'en-tête <div class="text-box"><h1>Nom | Partei</h1><h2>Fonction</h2>…</div>
    private static final Pattern DETAIL_HEADER = Pattern.compile(
            "(?si)<div class=\"text-box\">\\s*<h1[^>]*>(.*?)</h1>((?:\\s*<h2[^>]*>.*?</h2>)*)");
    private static final Pattern H2 = Pattern.compile("(?si)<h2[^>]*>(.*?)</h2>");
    private static final Pattern ZUR_PERSON_BLOCK = Pattern
            .compile("(?si)<div id=\"person\"[^>]*>.*?<div class=\"text-box\">\\s*(.*?)\\s*</div>");
    private static final Pattern SEIT_DATE = Pattern.compile(
            "(?i)seit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\d{2}\\.\\d{4}|\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");

    private static final Pattern TITLE = Pattern.compile("^(?:(?:Prof\\.|Dr\\.(?:\\s?-?Ing\\.)?|h\\.\\s?c\\.)\\s*)+");
    private static final Pattern RETIRED = Pattern.compile("(?i)\\s*\\ba\\.\\s*D\\.");
    private static final Pattern PRESIDENT_FN = Pattern.compile("(?i)^Präsident(?:in)? des Bundesrate?s$");
    private static final Pattern VICE_FN = Pattern.compile("(?i)Vizepräsident(?:in)? des Bundesrate?s");
    private static final Pattern BEIRAT_FN = Pattern.compile("(?i)Bevollmächtigte[rn]?\\b.*\\bbeim Bund");
    private static final Pattern MINISTER_FN = Pattern.compile(
            "(?i)(?:Minister|Ministerin|Staatsminister|Senator|Senatorin|Bürgermeister|Bürgermeisterin)\\b");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12"));

    /**
     * Une personne. {@code name} est sans titre ; {@code functions} garde les libellés d'origine ; {@code sources}
     * liste les pages qui l'attestent.
     */
    record Member(String slug, String landCode, String title, String name, String party, String state,
                  List<String> functions, Set<Role> roles, String pageUrl, String photo, String photoCredit,
                  String startDate, String endDate, String status, String cvSummary, List<String> sources) {

        /** Première fonction affichée (compatibilité). */
        String function() {
            return functions.isEmpty() ? null : functions.get(0);
        }

        /** Réunit deux lectures de la même personne (liste, Präsidium, Beirat, fiche). */
        Member merge(Member o) {
            List<String> fns = new ArrayList<>(new LinkedHashSet<>(concat(functions, o.functions)));
            Set<Role> rs = EnumSet.noneOf(Role.class);
            rs.addAll(roles);
            rs.addAll(o.roles);
            List<String> src = new ArrayList<>(new LinkedHashSet<>(concat(sources, o.sources)));
            return new Member(slug, landCode != null && !"de".equals(landCode) ? landCode : o.landCode,
                    title != null ? title : o.title, name, party != null ? party : o.party,
                    state != null ? state : o.state, fns, rs, pageUrl != null ? pageUrl : o.pageUrl,
                    photo != null ? photo : o.photo, photoCredit != null ? photoCredit : o.photoCredit,
                    startDate != null ? startDate : o.startDate, endDate != null ? endDate : o.endDate, status,
                    cvSummary != null ? cvSummary : o.cvSummary, src);
        }

        Member withDeparture(String departureDate, String newStatus) {
            return new Member(slug, landCode, title, name, party, state, functions, roles, pageUrl, photo,
                    photoCredit, startDate, departureDate, newStatus, cvSummary, sources);
        }

        private static List<String> concat(List<String> a, List<String> b) {
            List<String> l = new ArrayList<>(a);
            l.addAll(b);
            return l;
        }
    }

    record CrawlResult(Map<String, Member> members, List<String> brokenSources, List<String> structureWarnings,
                       Map<String, String> sha256ByUrl) {
    }

    // --- Point d'entrée ------------------------------------------------------------------------------------------

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);
        String extractedAt = LocalDate.now(ZoneOffset.UTC).toString();

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int members = 0, mp = 0, ministers = 0, cooling = 0, beirat = 0, praesidium = 0;
        for (Member m : crawl.members().values()) {
            if (m.roles().contains(Role.MEMBER)) {
                members++;
                if (isMinisterPresident(m.functions())) mp++; else ministers++;
            }
            if (m.roles().contains(Role.BEIRAT)) beirat++;
            if (m.roles().contains(Role.PRESIDENT) || m.roles().contains(Role.VICE_PRESIDENT)) praesidium++;
            if ("cooling_off".equals(m.status())) cooling++;
            write(m, dataset, crawl.sha256ByUrl(), extractedAt);
        }
        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder(String.format(
                "%d membres du Bundesrat extraits (dont %d chefs de gouvernement de Land, %d autres membres de "
                        + "gouvernement, %d en période de refroidissement) ; Präsidium : %d ; Ständiger Beirat : %d.",
                members, mp, ministers, cooling, praesidium, beirat));
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

    // --- Collecte ------------------------------------------------------------------------------------------------

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        Map<String, Member> bySlug = new LinkedHashMap<>();
        List<String> broken = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> shaByUrl = new LinkedHashMap<>();

        // Fichier d'entrée statique (tests unitaires ou archive) : une page de liste.
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                byte[] bytes = Files.readAllBytes(ctx.inputFile());
                String html = new String(bytes, StandardCharsets.UTF_8);
                String url = ctx.inputFile().toUri().toString();
                shaByUrl.put(url, sha256(bytes));
                List<Member> list = extractMembers(html, url, EnumSet.of(Role.MEMBER));
                if (list.isEmpty()) warnings.add("0 membre extrait du fichier d'entrée");
                for (Member m : list) bySlug.merge(m.slug(), m, Member::merge);
            } catch (Exception e) {
                broken.add("inputFile: " + e.getMessage());
            }
            rolesFromFunctions(bySlug);
            return new CrawlResult(bySlug, broken, warnings, shaByUrl);
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        List<String> queue = new ArrayList<>(resolveTargetUrls(parameters));
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> departed = new LinkedHashMap<>(); // nom -> date de fin ISO

        for (int i = 0; i < queue.size(); i++) {
            String url = queue.get(i).trim();
            if (url.isEmpty() || !seen.add(url)) continue;
            String html = fetch(client, url, ctx.rawDir(), broken, shaByUrl);
            if (html == null) continue;

            if (url.contains("aenderungen")) {
                departed.putAll(parseDepartures(html));
            } else if (url.contains("/personen/")) {
                Member detail = parseDetailPage(url, html);
                if (detail == null) {
                    warnings.add("Structure modifiée sur la fiche " + url);
                } else {
                    bySlug.merge(detail.slug(), detail, Member::merge);
                }
            } else {
                // Page de liste : membres (vcard), Präsidium (vcard + fonctions) ou Ständiger Beirat (rack-teaser).
                // Les personnes du Präsidium sont des chefs de gouvernement de Land, donc membres du Bundesrat.
                List<Member> list = new ArrayList<>(extractMembers(html, url, EnumSet.of(Role.MEMBER)));
                // Ständiger Beirat et pages des Länder présentent les personnes en blocs rack-teaser.
                list.addAll(extractTeasers(html, url,
                        url.contains("staendiger-beirat") ? Role.BEIRAT : Role.MEMBER));
                // La page d'accueil du Präsidium ne liste personne : la composition est sur la page « aktuell ».
                Matcher pl = PRAESIDIUM_LINK.matcher(html);
                if (url.contains("praesidium-node") && pl.find()) {
                    queue.add(pl.group(1).startsWith("http") ? pl.group(1) : SITE + pl.group(1));
                } else if (list.isEmpty()) {
                    warnings.add("Structure modifiée ou 0 membre sur " + url);
                }
                for (Member m : list) bySlug.merge(m.slug(), m, Member::merge);
            }
        }

        // Fiches individuelles des personnes sans CV : fonctions d'origine et date de prise de fonction.
        for (Member m : new ArrayList<>(bySlug.values())) {
            if (m.cvSummary() != null || m.pageUrl() == null || !m.pageUrl().startsWith("http")
                    || seen.contains(m.pageUrl())) {
                continue;
            }
            seen.add(m.pageUrl());
            String html = fetch(client, m.pageUrl(), ctx.rawDir(), new ArrayList<>(), shaByUrl);
            Member detail = html == null ? null : parseDetailPage(m.pageUrl(), html);
            if (detail != null) bySlug.merge(m.slug(), detail, Member::merge);
        }

        // Sorties récentes (aenderungen-node.html) : 12 mois de refroidissement, sans suppression.
        LocalDate cutoff = LocalDate.now().minusMonths(12);
        for (Map.Entry<String, String> dep : departed.entrySet()) {
            String depName = splitTitle(dep.getKey())[1];
            LocalDate depDate = LocalDate.parse(dep.getValue());
            String status = depDate.isBefore(cutoff) ? "expired" : "cooling_off";
            boolean found = false;
            for (Map.Entry<String, Member> e : bySlug.entrySet()) {
                if (e.getValue().name().equalsIgnoreCase(depName)) {
                    bySlug.put(e.getKey(), e.getValue().withDeparture(dep.getValue(), status));
                    found = true;
                    break;
                }
            }
            if (!found && !depDate.isBefore(cutoff)) {
                String slug = depName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                // La page des changements atteste la sortie : c'est la source de la fiche, toujours renseignée.
                String src = queue.stream().filter(u -> u.contains("aenderungen")).findFirst()
                        .orElse("https://www.bundesrat.de/DE/bundesrat/mitglieder/aenderungen/aenderungen-node.html");
                bySlug.put(slug, new Member(slug, null, splitTitle(dep.getKey())[0], depName, null, null,
                        List.of("Ehemaliges Mitglied des Bundesrates"), EnumSet.of(Role.MEMBER), src, null, null,
                        null, dep.getValue(), "cooling_off", "Aus dem Bundesrat ausgeschieden am " + dep.getValue(),
                        src == null ? List.of() : List.of(src)));
            }
        }

        rolesFromFunctions(bySlug);
        return new CrawlResult(bySlug, broken, warnings, shaByUrl);
    }

    /** Les fonctions décident des rôles de Präsidium et de Beirat, d'où qu'elles viennent. */
    static void rolesFromFunctions(Map<String, Member> bySlug) {
        for (Map.Entry<String, Member> e : bySlug.entrySet()) {
            Member m = e.getValue();
            Set<Role> rs = EnumSet.noneOf(Role.class);
            rs.addAll(m.roles());
            for (String f : m.functions()) {
                if (PRESIDENT_FN.matcher(f).find()) rs.add(Role.PRESIDENT);
                if (VICE_FN.matcher(f).find()) rs.add(Role.VICE_PRESIDENT);
                if (BEIRAT_FN.matcher(f).find()) rs.add(Role.BEIRAT);
            }
            if (!rs.equals(m.roles())) {
                e.setValue(new Member(m.slug(), m.landCode(), m.title(), m.name(), m.party(), m.state(),
                        m.functions(), rs, m.pageUrl(), m.photo(), m.photoCredit(), m.startDate(), m.endDate(),
                        m.status(), m.cvSummary(), m.sources()));
            }
        }
    }

    static List<String> resolveTargetUrls(Map<String, String> parameters) {
        List<String> list = new ArrayList<>();
        String urlsParam = parameters.get("urls");
        if (urlsParam != null && !urlsParam.isBlank()) {
            if (urlsParam.trim().startsWith("[")) {
                Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(urlsParam);
                while (m.find()) list.add(m.group(1));
            } else {
                for (String part : urlsParam.split("[\r\n,]+")) {
                    if (!part.isBlank()) list.add(part.trim());
                }
            }
        }
        if (list.isEmpty()) list.add(parameters.getOrDefault("url", DEFAULT_URL).trim());
        return list;
    }

    private String fetch(HttpClient client, String url, java.nio.file.Path rawDir, List<String> broken,
                         Map<String, String> shaByUrl) {
        // Une erreur réseau (délai dépassé, connexion coupée) a droit à une seconde tentative ; une réponse 4xx/5xx
        // non : elle est journalisée en SOURCE_BROKEN sans bloquer les autres pages.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String html = fetchSafe(client, url, rawDir, shaByUrl);
                if (html == null) broken.add(url);
                return html;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broken.add(url);
                return null;
            } catch (Exception e) {
                if (attempt == 2) {
                    log.warn("SOURCE_BROKEN sur {}: {}", url, e.getMessage());
                    broken.add(url);
                    return null;
                }
                log.info("Nouvel essai pour {} après : {}", url, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    broken.add(url);
                    return null;
                }
            }
        }
        return null;
    }

    private String fetchSafe(HttpClient client, String rawUrl, java.nio.file.Path rawDir, Map<String, String> shaByUrl)
            throws IOException, InterruptedException {
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
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() >= 400) {
            log.warn("HTTP {} pour {} (SOURCE_BROKEN)", resp.statusCode(), rawUrl);
            return null;
        }
        // Chaque page reçue est déposée dans rawDir : le core la signe et l'archive (preuve des données
        // utilisées). Sans fichier dans rawDir, l'exécution est bloquée.
        if (rawDir != null) {
            Files.createDirectories(rawDir);
            Files.write(rawDir.resolve(rawFileName(uri)), resp.body());
        }
        shaByUrl.put(rawUrl, sha256(resp.body()));
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    /** Nom du fichier brut d'une page : chemin et requête de l'URL, ex. DE-bundesrat-mitglieder-mitglieder-node-html-param3-Alle.html. */
    static String rawFileName(URI uri) {
        String path = (uri.getPath() == null ? "" : uri.getPath())
                + (uri.getQuery() == null ? "" : "-" + uri.getQuery());
        String name = path.replaceAll("\\.html$", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 150) name = name.substring(0, 150);
        return (name.isEmpty() ? "page" : name) + ".html";
    }

    // --- Lecture des pages ---------------------------------------------------------------------------------------

    static List<Member> extractMembers(String html) {
        return extractMembers(html, null, EnumSet.of(Role.MEMBER));
    }

    /** Blocs vcard d'une liste de membres ou du Präsidium : nom, parti, Land et toutes les fonctions (h3). */
    static List<Member> extractMembers(String html, String sourceUrl, Set<Role> roles) {
        List<Member> list = new ArrayList<>();
        Matcher m = VCARD_BLOCK.matcher(html);
        while (m.find()) {
            String block = m.group(1);
            Matcher linkM = PERSON_LINK.matcher(block);
            if (!linkM.find()) continue;
            String pageUrl = absolute(linkM.group(1));
            String slug = linkM.group(3);

            String rawName = null;
            String party = null;
            Matcher nameM = NAME_PARTY.matcher(block);
            if (nameM.find()) {
                rawName = Html.text(nameM.group(1));
                party = Html.text(nameM.group(2));
            } else {
                Matcher only = NAME_ONLY.matcher(block);
                if (only.find()) rawName = Html.text(only.group(1));
            }
            if (rawName == null || rawName.isBlank()) rawName = nameFromSlug(slug);

            Matcher stateM = STATE.matcher(block);
            String state = stateM.find() ? Html.text(stateM.group(1)) : null;

            List<String> functions = new ArrayList<>();
            Matcher funcM = FUNCTION.matcher(block);
            while (funcM.find()) {
                String f = Html.text(funcM.group(1));
                if (!f.isEmpty()) functions.add(f);
            }

            String photo = null;
            String photoCredit = null;
            Matcher photoM = PHOTO.matcher(block);
            if (photoM.find()) {
                photo = photoM.group(1);
                Matcher creditM = PHOTO_TITLE.matcher(block);
                if (creditM.find()) photoCredit = "Foto: " + Html.text(creditM.group(1));
            }

            String[] tn = splitTitle(rawName);
            list.add(new Member(slug, linkM.group(2), tn[0], tn[1], party, state, functions, EnumSet.copyOf(roles),
                    pageUrl, photo, photoCredit, null, null, "current", null,
                    sourceUrl == null ? List.of() : List.of(sourceUrl)));
        }
        return list;
    }

    /** Ständiger Beirat : les 16 Bevollmächtigte (blocs rack-teaser : lien, nom en h3, parti, Land). */
    static List<Member> extractBeirat(String html, String sourceUrl) {
        return extractTeasers(html, sourceUrl, Role.BEIRAT);
    }

    /**
     * Blocs rack-teaser (Ständiger Beirat, pages laender/xx-node.html) : lien vers la fiche, nom en h3, parti en
     * {@code <p class="organization-name">} ou en {@code <p>} simple, Land en {@code <p class="bundesland">}.
     */
    static List<Member> extractTeasers(String html, String sourceUrl, Role role) {
        List<Member> list = new ArrayList<>();
        Matcher m = RACK_TEASER.matcher(html);
        while (m.find()) {
            String block = m.group(1);
            Matcher linkM = PERSON_LINK.matcher(block);
            if (!linkM.find()) continue;
            Matcher nameM = FUNCTION.matcher(block);
            String rawName = nameM.find() ? Html.text(nameM.group(1)) : nameFromSlug(linkM.group(3));
            Matcher partyM = TEASER_PARTY.matcher(block);
            Matcher plainP = TEASER_PLAIN_P.matcher(block);
            String party = partyM.find() ? Html.text(partyM.group(1)) : plainP.find() ? Html.text(plainP.group(1)) : null;
            Matcher stateM = STATE.matcher(block);
            String[] tn = splitTitle(rawName);
            list.add(new Member(linkM.group(3), linkM.group(2), tn[0], tn[1], party,
                    stateM.find() ? Html.text(stateM.group(1)) : null,
                    List.of(), EnumSet.of(role), absolute(linkM.group(1)), null, null, null, null, "current",
                    null, List.of(sourceUrl)));
        }
        return list;
    }

    /** Fiche individuelle : en-tête text-box (nom | parti, fonctions en h2), « Zur Person » (CV, date). */
    static Member parseDetailPage(String url, String html) {
        String slug = extractSlug(url);
        if (slug == null || slug.isBlank()) return null;

        Matcher header = DETAIL_HEADER.matcher(html);
        if (!header.find()) return null;
        String[] nameParty = Html.text(header.group(1)).split("\\s*\\|\\s*", 2);
        String rawName = nameParty[0].isBlank() ? nameFromSlug(slug) : nameParty[0];
        String party = nameParty.length > 1 && !nameParty[1].isBlank() ? nameParty[1] : null;
        List<String> functions = new ArrayList<>();
        Matcher h2 = H2.matcher(header.group(2));
        while (h2.find()) {
            String f = Html.text(h2.group(1));
            if (!f.isEmpty()) functions.add(f);
        }

        String cvSummary = null;
        String startDate = null;
        Matcher personM = ZUR_PERSON_BLOCK.matcher(html);
        if (personM.find()) {
            cvSummary = Html.text(personM.group(1));
            Matcher seitM = SEIT_DATE.matcher(cvSummary);
            if (seitM.find()) startDate = parseIsoDate(seitM.group(1));
        }

        String[] tn = splitTitle(rawName);
        String landCode = extractLandCode(url);
        return new Member(slug, landCode != null ? landCode : "de", tn[0], tn[1], party, null, functions,
                EnumSet.noneOf(Role.class), url, null, null, startDate, null, "current", cvSummary, List.of(url));
    }

    static Map<String, String> parseDepartures(String html) {
        Map<String, String> departures = new LinkedHashMap<>();
        Matcher pMatcher = Pattern.compile("(?si)<p>(.*?)</p>").matcher(html);
        while (pMatcher.find()) {
            String pText = pMatcher.group(1);
            String lower = pText.toLowerCase(Locale.ROOT);
            if (!lower.contains("ausgeschieden") && !lower.contains("endeten")) continue;

            Matcher dateM = Pattern.compile("(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4})")
                    .matcher(pText);
            String date = dateM.find() ? parseIsoDate(dateM.group(1)) : null;
            if (date == null) continue;

            Matcher nameM = Pattern.compile("<strong>\\s*([^<]+?)\\s*</strong>").matcher(pText);
            while (nameM.find()) {
                String clean = splitTitle(Html.text(nameM.group(1)))[1];
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
        if (s.matches("^\\d{1,2}\\.\\d{2}\\.\\d{4}$")) {
            String[] p = s.split("\\.");
            return String.format("%s-%02d-%02d", p[2], Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }
        if (s.matches("^\\d{4}$")) return s + "-01-01";
        Matcher m = Pattern.compile("(\\d{1,2})\\.\\s*([a-zäöü]+)\\s*(\\d{4})").matcher(s);
        if (m.find() && GERMAN_MONTHS.containsKey(m.group(2))) {
            return String.format("%s-%s-%02d", m.group(3), GERMAN_MONTHS.get(m.group(2)), Integer.parseInt(m.group(1)));
        }
        return null;
    }

    // --- Publication ---------------------------------------------------------------------------------------------

    private static void write(Member m, FtmDatasetBuilder dataset, Map<String, String> shaByUrl, String extractedAt) {
        boolean isMember = m.roles().contains(Role.MEMBER);
        boolean isMp = isMinisterPresident(m.functions());
        // Un membre du Bundesrat est membre de son gouvernement de Land (art. 51 GG) ; un Bevollmächtigter qui
        // n'y siège pas ne l'est que si sa fonction le dit (ministre, sénateur).
        boolean landGov = isMember && m.landCode() != null && !"de".equals(m.landCode())
                || m.functions().stream().anyMatch(f -> MINISTER_FN.matcher(f).find() && !BEIRAT_FN.matcher(f).matches());

        FtmEntity person = dataset.newEntity("Person", m.slug());
        person.addValue("name", m.name());
        person.addValue("title", m.title());
        String[] fl = splitFirstLast(m.name());
        person.addValue("firstName", fl[0]);
        person.addValue("lastName", fl[1]);
        if (m.party() != null) person.addValue("political", m.party());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        if (isMember) person.addValue("position", "Mitglied des Bundesrates");
        for (String f : m.functions()) person.addValue("position", f);
        person.addValue("sourceUrl", m.pageUrl());
        if (m.cvSummary() != null) person.addValue("summary", m.cvSummary());

        // Marqueurs Story 2 : classe, niveau, statut, preuve de chaque page.
        if (m.roles().contains(Role.PRESIDENT)) person.addValue("notes", "pep_class: A1 (Präsident des Bundesrates)");
        if (m.roles().contains(Role.VICE_PRESIDENT)) person.addValue("notes", "pep_class: B2 (Vizepräsident des Bundesrates)");
        if (isMember) person.addValue("notes", "pep_class: B2 (Mitglied des Bundesrates)");
        if (m.roles().contains(Role.BEIRAT)) person.addValue("notes", "pep_class: B2 (Ständiger Beirat des Bundesrates)");
        if (landGov) person.addValue("notes", "pep_class: A3 (Landesregierung)");
        if (isMember || m.roles().contains(Role.BEIRAT) || m.roles().contains(Role.PRESIDENT)) {
            person.addValue("notes", "level: FEDERAL");
        }
        if (landGov) person.addValue("notes", "level: LAENDER");
        if ("cooling_off".equals(m.status())) {
            person.addValue("notes", "status: COOLING (ausgeschieden am " + m.endDate() + ", 12 Monate)");
        }
        for (String src : m.sources()) {
            String sha = shaByUrl.get(src);
            person.addValue("notes", "source: " + src + " ; extracted_at: " + extractedAt
                    + (sha == null ? "" : " ; sha256: " + sha));
        }
        dataset.addEntity(person);

        if (m.photo() != null) {
            dataset.addMedia(person.getId(), m.photo(), m.pageUrl(),
                    m.photoCredit() != null ? m.photoCredit() : "© Bundesrat");
        }

        String description = m.functions().isEmpty() ? null : String.join(" ; ", m.functions());
        if (isMember) {
            occupancy(dataset, person, position(dataset, "bundesrat-member", null, "Mitglied des Bundesrates",
                    "Bundesrat", "gov.legislative"), m, "bundesrat", description);
        }
        if (m.roles().contains(Role.PRESIDENT)) {
            occupancy(dataset, person, position(dataset, "bundesrat-president", null, "Präsident des Bundesrates",
                    "Bundesrat", "gov.legislative"), m, "bundesrat-president", "Präsident des Bundesrates");
        }
        if (m.roles().contains(Role.VICE_PRESIDENT)) {
            String vice = m.functions().stream().filter(f -> VICE_FN.matcher(f).find()).findFirst()
                    .orElse("Vizepräsident des Bundesrates");
            occupancy(dataset, person, position(dataset, "bundesrat-vice-president", null,
                    "Vizepräsident des Bundesrates", "Bundesrat", "gov.legislative"), m, "bundesrat-vice-president", vice);
        }
        if (m.roles().contains(Role.BEIRAT)) {
            occupancy(dataset, person, position(dataset, "bundesrat-staendiger-beirat", null,
                    "Mitglied des Ständigen Beirats des Bundesrates (Bevollmächtigte der Länder beim Bund)",
                    "Bundesrat", "gov.national"), m, "staendiger-beirat", description);
        }
        if (landGov) {
            String org = m.state() != null ? "Landesregierung " + m.state() : "Landesregierung";
            FtmEntity posLand = isMp
                    ? position(dataset, "land-minister-president", m.landCode(),
                            "Regierungschef eines Landes (Ministerpräsident, Regierender oder Erster Bürgermeister, "
                                    + "Präsident des Senats)", org, "gov.executive")
                    : position(dataset, "land-minister", m.landCode(),
                            "Mitglied einer Landesregierung (Landesminister, Senator)", org, "gov.executive");
            occupancy(dataset, person, posLand, m, "regional-exec", description);
        }
    }

    private static FtmEntity position(FtmDatasetBuilder dataset, String key, String landCode, String name, String org,
                                      String topic) {
        FtmEntity pos = landCode == null ? dataset.newEntity("Position", key) : dataset.newEntity("Position", key, landCode);
        pos.addValue("name", name);
        pos.addValue("country", "de");
        pos.addValue("organization", org);
        pos.addValue("topics", landCode == null ? "gov.national" : "gov.regional");
        pos.addValue("topics", topic);
        dataset.addEntity(pos);
        return pos;
    }

    private static void occupancy(FtmDatasetBuilder dataset, FtmEntity person, FtmEntity post, Member m, String key,
                                  String description) {
        FtmEntity occ = dataset.newEntity("Occupancy", m.slug(), key);
        occ.addValue("holder", person.getId());
        occ.addValue("post", post.getId());
        occ.addValue("status", m.status());
        occ.addValue("description", description);
        if (m.party() != null) occ.addValue("politicalGroup", m.party());
        occ.addValue("sourceUrl", m.pageUrl());
        occ.addValue("startDate", m.startDate());
        occ.addValue("endDate", m.endDate());
        dataset.addEntity(occ);
    }

    // --- Outils --------------------------------------------------------------------------------------------------

    static boolean isMinisterPresident(String function) {
        if (function == null) return false;
        String f = function.toLowerCase(Locale.ROOT);
        // Les adjoints (« Stellvertreter des Ministerpräsidenten », « Zweite Bürgermeisterin », « Bürgermeister des
        // Landes Berlin ») ne sont pas chefs de gouvernement : seuls le Ministerpräsident, le Regierende
        // Bürgermeister (Berlin), le Präsident des Senats (Brême) et le Erste Bürgermeister (Hambourg) le sont.
        if (f.contains("stellvertret") || f.contains("stellv.")) return false;
        return f.matches(".*\\bministerpräsident(in)?\\b.*") || f.contains("regierende bürgermeister")
                || f.contains("regierender bürgermeister") || f.contains("präsident des senats")
                || f.contains("präsidentin des senats") || f.contains("erster bürgermeister")
                || f.contains("erste bürgermeisterin");
    }

    static boolean isMinisterPresident(List<String> functions) {
        return functions.stream().anyMatch(DePepBundesratParser::isMinisterPresident);
    }

    /** Sépare les titres académiques du nom et retire la mention « a. D. » : [titre ou null, nom]. */
    static String[] splitTitle(String raw) {
        String name = RETIRED.matcher(raw == null ? "" : raw).replaceAll("").replaceAll("\\s+", " ").trim();
        Matcher tm = TITLE.matcher(name);
        if (tm.find() && tm.end() > 0 && tm.end() < name.length()) {
            return new String[] {tm.group(0).trim(), name.substring(tm.end()).trim()};
        }
        return new String[] {null, name};
    }

    /** Prénom(s) et nom : le dernier mot est le nom de famille, particules (« von der ») comprises. */
    static String[] splitFirstLast(String name) {
        String[] words = name.split("\\s+");
        if (words.length < 2) return new String[] {null, name};
        int last = words.length - 1;
        while (last > 1 && words[last - 1].matches("von|van|de|der|zu|du|di|da|le")) last--;
        return new String[] {String.join(" ", java.util.Arrays.copyOfRange(words, 0, last)),
                String.join(" ", java.util.Arrays.copyOfRange(words, last, words.length))};
    }

    /** « gernbauer-karolina » devient « Karolina Gernbauer » (le site écrit nom-prénom dans l'URL). */
    static String nameFromSlug(String slug) {
        String[] p = slug.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - 1; i >= 0; i--) {
            if (p[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p[i].charAt(0))).append(p[i].substring(1));
        }
        return sb.toString();
    }

    private static String absolute(String url) {
        return url.startsWith("http") ? url : SITE + url;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
