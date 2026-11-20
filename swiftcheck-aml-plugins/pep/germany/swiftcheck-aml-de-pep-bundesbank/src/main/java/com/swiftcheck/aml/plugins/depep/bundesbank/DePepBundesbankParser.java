package com.swiftcheck.aml.plugins.depep.bundesbank;

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
import java.util.HexFormat;
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
 * <b>de_pep_bundesbank</b> – Directoire, dirigeants et Hauptverwaltungen de la Deutsche Bundesbank
 * (classe D1, § 1 Abs. 12 GwG).
 * <p>
 * Les pages d'accueil (hubs) font foi : {@code /organisation/vorstand} et {@code /organisation/coo-und-bereiche}
 * donnent la liste des personnes ; les 9 Hauptverwaltungen (présidents et Beiräte) sont découvertes par les liens de
 * la navigation du site ({@code /hauptverwaltungen-filialen/<code>/praesident|praesidentin|beirat}). Les codes de
 * région ne sont pas figés : le site emploie {@code bbb}, {@code h} et {@code sth}, pas {@code bb}, {@code he} et
 * {@code st}.
 * <p>
 * Classification (Story 2) : les membres du Vorstand sont des PEP D1 actifs ; le COO, les chefs de Zentralbereich ou
 * de Generaldirektorat, les présidents des Hauptverwaltungen et les membres des Beiräte portent
 * {@code pep_candidate: true}. Ces marqueurs, le niveau ({@code level: FEDERAL}), l'empreinte SHA-256 de la page
 * source et la date d'extraction vont dans {@code notes}, car le schéma FtM n'a pas de propriété pour eux.
 * <p>
 * Paramètres : {@code hubs} (URLs séparées par des virgules, par défaut les deux hubs), {@code extraUrls} (fiches
 * individuelles en plus), {@code hauptverwaltungen} ({@code true} par défaut), {@code delayMs} (pause entre deux
 * pages, 150 ms par défaut).
 */
@Extension
public class DePepBundesbankParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepBundesbankParser.class);

    static final String ID_PREFIX = "de-pep-bundesbank";
    static final String TOPIC = "role.pep";
    static final String SITE = "https://www.bundesbank.de";
    static final String VORSTAND_HUB = SITE + "/de/bundesbank/organisation/vorstand";
    static final String BEREICHE_HUB = SITE + "/de/bundesbank/organisation/coo-und-bereiche";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Allemagne - Bundesbank)";
    static final String PEP_CLASS = "D1";
    static final String LEVEL = "FEDERAL";

    /** Rôle d'une personne à la Bundesbank : il décide de la position publiée et du marqueur pep_candidate. */
    enum Group {
        VORSTAND(false), BEREICH(true), HV_PRESIDENT(true), BEIRAT(true);

        final boolean candidate;

        Group(boolean candidate) {
            this.candidate = candidate;
        }
    }

    /** Une fonction tenue par une personne, avec la page qui l'atteste. */
    record Holding(Group group, String regionCode, String positionName, String role, String detail, String sourceUrl,
                   String sha256, String startDate) {
    }

    /** Une personne et toutes ses fonctions (le président d'une Hauptverwaltung préside aussi son Beirat). */
    static final class Member {
        final String fullName;
        final String title;
        final String cleanName;
        String birthYear;
        String birthPlace;
        String cvSummary;
        final List<Holding> holdings = new ArrayList<>();

        Member(String fullName) {
            this.fullName = fullName;
            String[] split = splitTitle(fullName);
            this.title = split[0];
            this.cleanName = split[1];
        }

        String key() {
            return cleanName.toLowerCase(Locale.ROOT);
        }

        Holding mainHolding() {
            return holdings.stream().min((a, b) -> Integer.compare(a.group().ordinal(), b.group().ordinal())).orElseThrow();
        }
    }

    /** Une page reçue : son texte HTML et l'empreinte SHA-256 des octets bruts. */
    record Page(String url, String html, String sha256) {
    }

    record CrawlResult(Map<String, Member> members, List<String> brokenSources, List<String> structureWarnings) {
    }

    // --- Motifs de lecture -------------------------------------------------------------------------------------

    private static final Pattern MAIN = Pattern.compile("(?si)<main[^>]*>(.*?)</main>");
    private static final Pattern HEADLINE = Pattern.compile(
            "(?si)<h1[^>]*class=\"[^\"]*main__headline[^\"]*\"[^>]*>(.*?)(?:<small[^>]*>(.*?)</small>)?\\s*</h1>");
    private static final Pattern HUB_LINK = Pattern.compile(
            "(?si)<a\\s[^>]*href=\"(/de/bundesbank/organisation/(vorstand|coo-und-bereiche)/[a-z0-9-]+)\"[^>]*>(.*?)</a>");
    private static final Pattern HV_LINK = Pattern.compile(
            "href=\"(/de/bundesbank/hauptverwaltungen-filialen/([a-z]+)/(praesident|praesidentin|beirat))\"");
    private static final Pattern RICHTEXT_ITEM = Pattern.compile("(?si)<h2[^>]*>(.*?)</h2>|<li[^>]*>(.*?)</li>");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?|Dr\\.?(?:\\s?-?Ing\\.)?|h\\.\\s?c\\.|Dipl\\.-[A-Za-zä]+\\.?)\\s+)+");
    private static final Pattern SEIT_DATE = Pattern.compile(
            "(?i)\\bseit(?:\\s+dem)?\\s+(\\d{1,2}\\.\\s*[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}\\.\\d{2}\\.\\d{4}|[A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{4})");
    private static final Pattern BORN = Pattern.compile("(?i)Geboren\\s+(?:am\\s+[^<]*?)?(\\d{4})(?:\\s+in\\s+([^<]+?))?\\s*<");
    private static final Pattern META_DESCRIPTION = Pattern.compile(
            "(?i)<meta\\s+name=[\"']description[\"']\\s+content=[\"']([^\"']+)[\"']");
    /** Un nom de personne : 2 à 6 mots qui commencent par une majuscule (particules admises), sans chiffre. */
    private static final Pattern PERSON_NAME = Pattern.compile(
            "^(?:[A-ZÄÖÜ][\\p{L}'.-]*|von|van|de|der|zu|du|di|da|le)(?:\\s+(?:[A-ZÄÖÜ][\\p{L}'.-]*|von|van|de|der|zu|du|di|da|le)){1,5}$");

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12"));

    /** Dossier des réponses brutes de l'exécution en cours : le core les signe et les archive (preuve des données). */
    private java.nio.file.Path rawDir;
    private long delayMs = 150;

    // --- Point d'entrée ------------------------------------------------------------------------------------------

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, p);

        if (crawl.members().isEmpty()) {
            throw new IllegalStateException("Aucun membre trouvé pour la Deutsche Bundesbank."
                    + (crawl.brokenSources().isEmpty() ? "" : " Sources en échec : " + crawl.brokenSources()));
        }

        String extractedAt = LocalDate.now(ZoneOffset.UTC).toString();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(new ArrayList<>(crawl.members().values()), dataset, extractedAt);
        dataset.writeNdjsonFile(ctx.outputFile());

        Map<Group, Integer> counts = new LinkedHashMap<>();
        for (Member m : crawl.members().values()) {
            for (Holding h : m.holdings) counts.merge(h.group(), 1, Integer::sum);
        }
        StringBuilder msg = new StringBuilder();
        msg.append(crawl.members().size()).append(" personnes de la Deutsche Bundesbank extraites (Vorstand ")
           .append(counts.getOrDefault(Group.VORSTAND, 0)).append(", COO et Bereiche ")
           .append(counts.getOrDefault(Group.BEREICH, 0)).append(", présidents de Hauptverwaltung ")
           .append(counts.getOrDefault(Group.HV_PRESIDENT, 0)).append(", Beiräte ")
           .append(counts.getOrDefault(Group.BEIRAT, 0)).append(").");
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
            records.add(new SourceRecord(ids.buildEntityId(m.cleanName), "Person", m.fullName, m.mainHolding().role()));
        }
        return records;
    }

    // --- Collecte ------------------------------------------------------------------------------------------------

    CrawlResult crawl(ParserContext ctx, Map<String, String> parameters) {
        this.rawDir = ctx.rawDir();
        this.delayMs = parseLong(parameters.get("delayMs"), 150);
        Map<String, Member> members = new LinkedHashMap<>();
        List<String> broken = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Fichier d'entrée local (tests ou instantané) : une page hub, lue sans aller chercher les fiches.
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            try {
                String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
                Page page = new Page(VORSTAND_HUB, html, sha256(html.getBytes(StandardCharsets.UTF_8)));
                List<HubEntry> entries = extractHubEntries(html);
                if (entries.isEmpty()) warnings.add("0 personne dans le fichier d'entrée");
                for (HubEntry e : entries) {
                    add(members, e.fullName(), new Holding(e.group(), null, null, e.role(), null,
                            SITE + e.path(), page.sha256(), null));
                }
            } catch (Exception e) {
                broken.add("inputFile: " + e.getMessage());
            }
            return new CrawlResult(members, broken, warnings);
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        // 1. Hubs : la liste des personnes et, dans la navigation, les liens des Hauptverwaltungen.
        Map<String, HubEntry> profiles = new LinkedHashMap<>();
        Map<String, String> hvPages = new LinkedHashMap<>();   // chemin -> code de région
        for (String hub : splitList(parameters.get("hubs"), List.of(VORSTAND_HUB, BEREICHE_HUB))) {
            Page page = fetch(client, hub, broken);
            if (page == null) continue;
            List<HubEntry> entries = extractHubEntries(mainContent(page.html()));
            if (entries.isEmpty()) {
                warnings.add("0 personne sur le hub " + hub);
            }
            for (HubEntry e : entries) profiles.putIfAbsent(e.path(), e);
            Matcher hv = HV_LINK.matcher(page.html());
            while (hv.find()) hvPages.putIfAbsent(hv.group(1), hv.group(2));
        }

        // 2. Fiches individuelles du Vorstand et des Bereiche (nom, rôle d'origine, date de prise de fonction).
        for (HubEntry e : profiles.values()) {
            String url = SITE + e.path();
            Page page = fetch(client, url, broken);
            Profile profile = page == null ? null : parseProfile(page.html());
            if (profile == null) {
                if (page != null) warnings.add("Structure modifiée sur la fiche " + url);
                // La fiche manque : le hub suffit à publier la personne (nom et rôle officiels).
                add(members, e.fullName(), new Holding(e.group(), null, null, e.role(), null, url,
                        page == null ? null : page.sha256(), null));
                continue;
            }
            Member m = add(members, profile.fullName(), new Holding(e.group(), null, null,
                    firstNonBlank(profile.role(), e.role()), null, url, page.sha256(), profile.startDate()));
            m.birthYear = profile.birthYear();
            m.birthPlace = profile.birthPlace();
            m.cvSummary = profile.cvSummary();
        }

        // 3. Hauptverwaltungen : présidents puis Beiräte.
        boolean withHv = !"false".equalsIgnoreCase(parameters.getOrDefault("hauptverwaltungen", "true"));
        if (withHv) {
            if (hvPages.isEmpty()) {
                warnings.add("aucun lien de Hauptverwaltung trouvé dans la navigation");
            }
            List<Map.Entry<String, String>> ordered = new ArrayList<>(hvPages.entrySet());
            ordered.sort((a, b) -> Boolean.compare(a.getKey().endsWith("/beirat"), b.getKey().endsWith("/beirat")));
            for (Map.Entry<String, String> hv : ordered) {
                String url = SITE + hv.getKey();
                String region = hv.getValue();
                Page page = fetch(client, url, broken);
                if (page == null) continue;
                if (url.endsWith("/beirat")) {
                    List<BeiratMember> list = parseBeirat(page.html());
                    if (list.isEmpty()) {
                        warnings.add("0 membre de Beirat sur " + url);
                    }
                    String body = beiratBody(page.html());
                    for (BeiratMember b : list) {
                        // Le secteur représenté (titre h2 de la page) précède la fonction : « Versicherungswirtschaft :
                        // Dortmund » se comprend, « Dortmund » seul non.
                        String detail = b.sector() == null ? b.detail()
                                : b.detail() == null ? b.sector() : b.sector() + " : " + b.detail();
                        add(members, b.fullName(), new Holding(Group.BEIRAT, region, body, body, detail, url,
                                page.sha256(), null));
                    }
                } else {
                    Profile profile = parseProfile(page.html());
                    if (profile == null) {
                        warnings.add("Structure modifiée sur la fiche " + url);
                        continue;
                    }
                    Member m = add(members, profile.fullName(), new Holding(Group.HV_PRESIDENT, region, profile.role(),
                            profile.role(), null, url, page.sha256(), profile.startDate()));
                    if (m.cvSummary == null) m.cvSummary = profile.cvSummary();
                }
            }
        }

        // 4. Fiches ajoutées à la main (paramètre extraUrls).
        for (String url : splitList(parameters.get("extraUrls"), List.of())) {
            Page page = fetch(client, url, broken);
            Profile profile = page == null ? null : parseProfile(page.html());
            if (profile == null) {
                if (page != null) warnings.add("Structure modifiée sur la fiche " + url);
                continue;
            }
            Group group = url.contains("/vorstand/") ? Group.VORSTAND : Group.BEREICH;
            add(members, profile.fullName(), new Holding(group, null, null, profile.role(), null, url, page.sha256(),
                    profile.startDate()));
        }

        return new CrawlResult(members, broken, warnings);
    }

    /** Ajoute une fonction à la personne de ce nom (créée au besoin) ; la même page n'est comptée qu'une fois. */
    private static Member add(Map<String, Member> members, String fullName, Holding holding) {
        Member probe = new Member(fullName);
        Member m = members.computeIfAbsent(probe.key(), k -> probe);
        boolean known = m.holdings.stream().anyMatch(h -> h.group() == holding.group()
                && java.util.Objects.equals(h.sourceUrl(), holding.sourceUrl()));
        if (!known) m.holdings.add(holding);
        return m;
    }

    // --- Lecture des pages ---------------------------------------------------------------------------------------

    /** Une entrée de hub : chemin de la fiche, nom affiché et rôle affiché sous le nom. */
    record HubEntry(String path, Group group, String fullName, String role) {
    }

    static List<HubEntry> extractHubEntries(String html) {
        Map<String, HubEntry> entries = new LinkedHashMap<>();
        Matcher m = HUB_LINK.matcher(html);
        while (m.find()) {
            String path = m.group(1);
            Group group = "vorstand".equals(m.group(2)) ? Group.VORSTAND : Group.BEREICH;
            List<String> parts = new ArrayList<>();
            for (String chunk : m.group(3).split("<[^>]+>")) {
                String t = text(chunk).replaceAll(",$", "").trim();
                if (!t.isEmpty()) parts.add(t);
            }
            // Le lien peut commencer par le crédit de la photo (« © … ») : le nom est le premier morceau qui en a l'air.
            int at = -1;
            for (int i = 0; i < parts.size() && at < 0; i++) {
                if (looksLikeName(splitTitle(parts.get(i))[1])) at = i;
            }
            if (at < 0) continue;
            String role = parts.size() > at + 1 ? String.join(", ", parts.subList(at + 1, parts.size())) : null;
            HubEntry entry = new HubEntry(path, group, parts.get(at), role);
            HubEntry previous = entries.get(path);
            if (previous == null || previous.role() == null && role != null) entries.put(path, entry);
        }
        return new ArrayList<>(entries.values());
    }

    /** Ce qu'une fiche individuelle apprend sur la personne. */
    record Profile(String fullName, String role, String startDate, String birthYear, String birthPlace,
                   String cvSummary) {
    }

    static Profile parseProfile(String html) {
        String main = mainContent(html);
        Matcher h = HEADLINE.matcher(main);
        if (!h.find()) return null;
        String fullName = text(h.group(1));
        if (fullName.isEmpty() || !looksLikeName(splitTitle(fullName)[1])) return null;
        String role = h.group(2) == null ? null : text(h.group(2));

        String startDate = null;
        Matcher seit = SEIT_DATE.matcher(text(main));
        if (seit.find()) startDate = parseIsoDate(seit.group(1));

        String birthYear = null;
        String birthPlace = null;
        Matcher born = BORN.matcher(main);
        if (born.find()) {
            birthYear = born.group(1);
            birthPlace = born.group(2) == null ? null : text(born.group(2));
        }

        String cv = null;
        Matcher desc = META_DESCRIPTION.matcher(html);
        if (desc.find()) cv = text(desc.group(1));
        return new Profile(fullName, role, startDate, birthYear, birthPlace, cv);
    }

    /** Un membre de Beirat : son nom et ses fonctions professionnelles, dans leur libellé d'origine. */
    record BeiratMember(String fullName, String detail, String sector) {
    }

    static List<BeiratMember> parseBeirat(String html) {
        List<BeiratMember> list = new ArrayList<>();
        String main = mainContent(html);
        String sector = null;
        Matcher m = RICHTEXT_ITEM.matcher(main);
        while (m.find()) {
            if (m.group(1) != null) {
                sector = text(m.group(1));
                continue;
            }
            String[] lines = BR.split(m.group(2));
            if (lines.length < 2) continue;
            String name = text(lines[0]);
            if (!looksLikeName(splitTitle(name)[1])) continue;
            List<String> functions = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String t = text(lines[i]);
                if (!t.isEmpty()) functions.add(t);
            }
            list.add(new BeiratMember(name, functions.isEmpty() ? null : String.join(", ", functions), sector));
        }
        return list;
    }

    /** « Beirat der Hauptverwaltung in Bayern » devient « Mitglied des Beirats der Hauptverwaltung in Bayern ». */
    static String beiratBody(String html) {
        Matcher h = HEADLINE.matcher(mainContent(html));
        String headline = h.find() ? text(h.group(1)) : "Beirat einer Hauptverwaltung";
        return headline.startsWith("Beirat ") ? "Mitglied des Beirats " + headline.substring("Beirat ".length())
                : "Mitglied des Beirats (" + headline + ")";
    }

    static String mainContent(String html) {
        Matcher m = MAIN.matcher(html);
        return m.find() ? m.group(1) : html;
    }

    // --- Publication ---------------------------------------------------------------------------------------------

    private void convert(List<Member> members, FtmDatasetBuilder dataset, String extractedAt) {
        Map<String, FtmEntity> positions = new LinkedHashMap<>();

        for (Member m : members) {
            Holding main = m.mainHolding();
            FtmEntity person = dataset.newEntity("Person", m.cleanName);
            person.addValue("name", m.cleanName);
            person.addValue("title", m.title);
            String[] parts = splitFirstLast(m.cleanName);
            person.addValue("firstName", parts[0]);
            person.addValue("lastName", parts[1]);
            person.addValue("country", "de");
            person.addValue("birthDate", m.birthYear);
            person.addValue("birthPlace", m.birthPlace);
            for (Holding h : m.holdings) person.addValue("position", h.role());
            person.addValue("sourceUrl", main.sourceUrl());
            if (m.cvSummary != null) person.addValue("summary", m.cvSummary);

            boolean candidate = m.holdings.stream().allMatch(h -> h.group().candidate);
            person.addValue("notes", "pep_class: " + PEP_CLASS);
            person.addValue("notes", "level: " + LEVEL);
            if (candidate) {
                person.addValue("notes", "pep_candidate: true (" + candidateReason(main.group()) + ")");
            }
            for (Holding h : m.holdings) {
                person.addValue("notes", "source: " + h.sourceUrl() + " ; extracted_at: " + extractedAt
                        + (h.sha256() == null ? "" : " ; sha256: " + h.sha256()));
                if (h.detail() != null) person.addValue("notes", h.positionName() + " — " + h.detail());
            }
            dataset.addEntity(person);

            for (Holding h : m.holdings) {
                FtmEntity post = positions.computeIfAbsent(positionKey(h), k -> {
                    FtmEntity pos = dataset.newEntity("Position", k);
                    pos.addValue("name", positionName(h));
                    pos.addValue("country", "de");
                    pos.addValue("organization", "Deutsche Bundesbank");
                    pos.addValue("topics", "gov.national");
                    pos.addValue("topics", "gov.financial");
                    dataset.addEntity(pos);
                    return pos;
                });
                FtmEntity occ = dataset.newEntity("Occupancy", m.cleanName, occupancyKey(h));
                occ.addValue("holder", person.getId());
                occ.addValue("post", post.getId());
                occ.addValue("status", "current");
                occ.addValue("description", h.detail() != null ? h.role() + " — " + h.detail() : h.role());
                occ.addValue("startDate", h.startDate());
                occ.addValue("sourceUrl", h.sourceUrl());
                dataset.addEntity(occ);
            }
        }
    }

    private static String candidateReason(Group g) {
        return switch (g) {
            case BEREICH -> "COO ou chef de Zentralbereich / Generaldirektorat";
            case HV_PRESIDENT -> "président d'une Hauptverwaltung";
            case BEIRAT -> "membre du Beirat d'une Hauptverwaltung";
            case VORSTAND -> "membre du Vorstand";
        };
    }

    /** Clé de position : les identifiants du Vorstand restent ceux des versions précédentes. */
    private static String positionKey(Holding h) {
        return switch (h.group()) {
            case VORSTAND -> "board-member";
            case BEREICH -> "bereichsleitung";
            case HV_PRESIDENT -> "hv-president-" + h.regionCode();
            case BEIRAT -> "hv-beirat-" + h.regionCode();
        };
    }

    private static String occupancyKey(Holding h) {
        return switch (h.group()) {
            case VORSTAND -> "vorstand";
            case BEREICH -> "bereich";
            case HV_PRESIDENT -> "hv-president-" + h.regionCode();
            case BEIRAT -> "hv-beirat-" + h.regionCode();
        };
    }

    private static String positionName(Holding h) {
        return switch (h.group()) {
            case VORSTAND -> "Mitglied des Vorstands der Deutschen Bundesbank";
            case BEREICH -> "Leitung eines Zentralbereichs oder Generaldirektorats der Deutschen Bundesbank";
            case HV_PRESIDENT, BEIRAT -> h.positionName();
        };
    }

    // --- Réseau --------------------------------------------------------------------------------------------------

    /** Une erreur réseau a droit à une seconde tentative ; une page en 404 est journalisée en SOURCE_BROKEN. */
    private Page fetch(HttpClient client, String rawUrl, List<String> broken) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Page page = fetchSafe(client, rawUrl);
                if (page == null) broken.add(rawUrl);
                if (delayMs > 0) Thread.sleep(delayMs);
                return page;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broken.add(rawUrl);
                return null;
            } catch (Exception e) {
                if (attempt == 2) {
                    log.warn("SOURCE_BROKEN sur {}: {}", rawUrl, e.getMessage());
                    broken.add(rawUrl);
                    return null;
                }
                log.info("Nouvel essai pour {} après : {}", rawUrl, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    broken.add(rawUrl);
                    return null;
                }
            }
        }
        return null;
    }

    private Page fetchSafe(HttpClient client, String rawUrl) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            log.warn("URL malformée (SOURCE_BROKEN) : {}", rawUrl);
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        if (resp.statusCode() == 404 || resp.statusCode() == 410) {
            log.warn("HTTP {} pour {} (SOURCE_BROKEN)", resp.statusCode(), rawUrl);
            return null;
        }
        if (resp.statusCode() >= 400 || com.swiftcheck.aml.sdk.StealthBrowserFetcher.isChallenge(resp)) {
            // Depuis un serveur de centre de données (VPS), les sites fédéraux renvoient vers un défi anti-robot
            // (/.enodia/challenge) : le service navigateur (crawler-stealth) le passe et rapporte la page.
            log.warn("HTTP {} pour {} ({}) : essai par le service navigateur", resp.statusCode(), rawUrl, resp.uri());
            try {
                body = com.swiftcheck.aml.sdk.StealthBrowserFetcher.downloadBytes(rawUrl);
            } catch (IOException e) {
                log.warn("SOURCE_BROKEN {} : {}", rawUrl, e.getMessage());
                return null;
            }
        }
        // Chaque page reçue est déposée dans rawDir ; sans fichier dans rawDir, l'exécution est bloquée.
        if (rawDir != null) {
            Files.createDirectories(rawDir);
            Files.write(rawDir.resolve(rawFileName(uri)), body);
        }
        return new Page(rawUrl, new String(body, StandardCharsets.UTF_8), sha256(body));
    }

    /** Nom du fichier brut d'une page : chemin et requête de l'URL (150 caractères au plus). */
    static String rawFileName(URI uri) {
        String path = (uri.getPath() == null ? "" : uri.getPath()) + (uri.getQuery() == null ? "" : "-" + uri.getQuery());
        String name = path.replaceAll("\\.html$", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 150) {
            name = name.substring(0, 140) + "-" + Integer.toHexString(path.hashCode());
        }
        return (name.isEmpty() ? "page" : name) + ".html";
    }

    // --- Outils --------------------------------------------------------------------------------------------------

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Texte d'un fragment HTML : balises retirées, entités courantes décodées, césures et espaces nettoyées. */
    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("<[^>]+>", " ")
                .replace("&shy;", "").replace("­", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&ndash;", "–").replace("&#8211;", "–");
        Matcher num = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuilder out = new StringBuilder();
        while (num.find()) {
            num.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(num.group(1)))));
        }
        num.appendTail(out);
        return out.toString().replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ")
                .replaceAll(" ([,;:)])", "$1").replaceAll("\\( ", "(").replaceAll(",(\\s*,)+", ",")
                .replaceAll("[,\\s]+$", "").trim();
    }

    /** Sépare les titres académiques (« Prof. Dr. ») du nom : [titre ou null, nom sans titre]. */
    static String[] splitTitle(String fullName) {
        String name = fullName == null ? "" : fullName.replaceAll("\\s+", " ").trim();
        Matcher tm = ACADEMIC_TITLE.matcher(name);
        if (tm.find() && tm.end() < name.length()) {
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

    static boolean looksLikeName(String name) {
        return name != null && PERSON_NAME.matcher(name).matches();
    }

    static String parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.GERMAN);
        Matcher dayMonthYear = Pattern.compile("^(\\d{1,2})\\.\\s*([a-zäöü]+)\\s*(\\d{4})$").matcher(s);
        if (dayMonthYear.find() && GERMAN_MONTHS.containsKey(dayMonthYear.group(2))) {
            return String.format("%s-%s-%02d", dayMonthYear.group(3), GERMAN_MONTHS.get(dayMonthYear.group(2)),
                    Integer.parseInt(dayMonthYear.group(1)));
        }
        Matcher monthYear = Pattern.compile("^([a-zäöü]+)\\s*(\\d{4})$").matcher(s);
        if (monthYear.find() && GERMAN_MONTHS.containsKey(monthYear.group(1))) {
            return monthYear.group(2) + "-" + GERMAN_MONTHS.get(monthYear.group(1)) + "-01";
        }
        if (s.matches("^\\d{1,2}\\.\\d{2}\\.\\d{4}$")) {
            String[] p = s.split("\\.");
            return String.format("%s-%02d-%02d", p[2], Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }
        if (s.matches("^\\d{4}$")) return s + "-01-01";
        return null;
    }

    static List<String> splitList(String value, List<String> fallback) {
        if (value == null || value.isBlank()) return fallback;
        List<String> list = new ArrayList<>();
        for (String part : value.replaceAll("[\\[\\]\"]", "").split("[\\r\\n,]+")) {
            if (!part.isBlank()) list.add(part.trim());
        }
        return list.isEmpty() ? fallback : list;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
