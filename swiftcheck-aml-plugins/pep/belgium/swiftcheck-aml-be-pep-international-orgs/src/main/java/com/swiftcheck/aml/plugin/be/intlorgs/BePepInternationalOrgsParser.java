package com.swiftcheck.aml.plugin.be.intlorgs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser pour les dirigeants des organisations internationales établies en Belgique :
 * <ul>
 *   <li>Organisation du Traité de l'Atlantique Nord ({@code nato.int}, Bruxelles)</li>
 *   <li>Union Benelux ({@code benelux.int}, Bruxelles)</li>
 *   <li>Organisation mondiale des douanes – OMD / WCO ({@code wcoomd.org}, Bruxelles)</li>
 *   <li>SHAPE ({@code shape.nato.int}, Mons), Eurocontrol ({@code eurocontrol.int}, Bruxelles),
 *       OEACP / OACPS ({@code oacps.org}, Bruxelles)</li>
 * </ul>
 *
 * <p>Catégorie légale (Annexe IV de la loi belge du 18 septembre 2017) :
 * <b>BE-h</b> – <i>Dirigeant ou membre du conseil d'une organisation internationale établie en Belgique</i>.
 */
@Extension
public class BePepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepInternationalOrgsParser.class);

    static final String ID_PREFIX = "be-pep-international-orgs";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String CONFIG_RESOURCE = "/international-orgs.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern NATO_NAV_LINK_PATTERN = Pattern.compile(
            "<a[^>]+aria-label=\"([^\"]+)\"[^>]+href=\"(/en/about-us/organization/who-we-are/[^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NATO_CARD_PATTERN = Pattern.compile(
            "<a[^>]+class=\"[^\"]*c17-distributive-hub-card[^\"]*\"[^>]+href=\"(/en/about-us/organization/who-we-are/(?:nato-secretaries-general|nato-deputy-secretaries-general|chairs-of-the-military-committee|director-generals-of-ims)/[^\"]+)\"[\\s\\S]*?"
            + "<h2[^>]*class=\"[^\"]*c17-card-title[^\"]*\"[^>]*>([\\s\\S]*?)</h2>",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NATO_RANK_PREFIX_PATTERN = Pattern.compile(
            "^(?:Admiral|General|Lieutenant\\s+General|Air\\s+Chief\\s+Marshal|Air\\s+Marshal|Vice\\s+Admiral)\\s+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BENELUX_ROW_PATTERN = Pattern.compile(
            "<tr[^>]*>([\\s\\S]*?)</tr>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BENELUX_TITULAIRE_PATTERN = Pattern.compile(
            "Titulaire\\s*:\\s*<strong>([^<]+)</strong>",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern BENELUX_ROLE_PATTERN = Pattern.compile(
            "(Secr(?:é|&#233;|&eacute;)taire\\s+g(?:é|&#233;|&eacute;)n(?:é|&#233;|&eacute;)rale?(?:\\s+adjointe?)?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern WCO_BLOCK_PATTERN = Pattern.compile(
            "<h1[^>]*>\\s*(Secr(?:é|&#233;|&eacute;)taire\\s+g(?:é|&#233;|&eacute;)n(?:é|&#233;|&eacute;)ral|Secretary\\s+General)\\s*</h1>[\\s\\S]{0,600}?<h2[^>]*>([^<]+)</h2>",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrgConfig(
            String code,
            String acronym,
            String name,
            String headquarters,
            String url,
            String status
    ) {
        public boolean isActive() {
            return "active".equalsIgnoreCase(status);
        }
    }

    public record LeaderRecord(
            String fullName,
            String firstName,
            String lastName,
            String position,
            String profileUrl,
            String orgCode,
            String orgAcronym,
            String orgName,
            String headquarters
    ) {}

    public List<OrgConfig> loadConfigs() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(CONFIG_RESOURCE)) {
            if (is == null) {
                throw new IOException("Ressource introuvable : " + CONFIG_RESOURCE);
            }
            return MAPPER.readValue(is, new TypeReference<List<OrgConfig>>() {});
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        List<OrgConfig> configs = loadConfigs();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (OrgConfig org : configs) {
            if (!org.isActive()) {
                continue;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(org.url()))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - organisations internationales)")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "fr-BE,fr;q=0.9,en;q=0.8")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    Files.writeString(ctx.rawDir().resolve(org.code() + ".html"), resp.body(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("[be_pep_international_orgs] Page indisponible pour {} ({}) : {}",
                        org.acronym(), org.url(), e.getMessage());
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<String> active = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<LeaderRecord> leaders = convert(ctx.rawDir(), dataset, active, pending, failed);
        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder(leaders.size() + " dirigeants d'organisations internationales extraits");
        if (!active.isEmpty()) {
            msg.append(" (").append(String.join(", ", active)).append(")");
        }
        msg.append(".");
        if (!pending.isEmpty()) {
            msg.append(" En attente (anti-bot) : ").append(String.join(", ", pending)).append(".");
        }
        if (!failed.isEmpty()) {
            msg.append(" En échec : ").append(String.join(", ", failed)).append(".");
        }
        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    List<LeaderRecord> convert(
            Path rawDir,
            FtmDatasetBuilder dataset,
            List<String> active,
            List<String> pending,
            List<String> failed
    ) throws IOException {
        List<OrgConfig> configs = loadConfigs();
        List<LeaderRecord> all = new ArrayList<>();

        for (OrgConfig org : configs) {
            if (!org.isActive()) {
                pending.add(org.acronym());
                continue;
            }
            Path htmlFile = rawDir.resolve(org.code() + ".html");
            if (!Files.isRegularFile(htmlFile)) {
                failed.add(org.acronym());
                continue;
            }
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            List<LeaderRecord> orgLeaders = parseOrganisationHtml(org, html);
            if (orgLeaders.isEmpty()) {
                failed.add(org.acronym());
            } else {
                active.add(org.acronym() + " : " + orgLeaders.size());
                all.addAll(orgLeaders);
            }
        }

        if (all.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'organisation internationale extrait depuis " + rawDir);
        }

        for (LeaderRecord rec : all) {
            writeRecord(rec, dataset);
        }
        return all;
    }

    public List<LeaderRecord> parseOrganisationHtml(OrgConfig org, String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        return switch (org.code()) {
            case "nato" -> parseNatoHtml(org, html);
            case "benelux" -> parseBeneluxHtml(org, html);
            case "wco" -> parseWcoHtml(org, html);
            default -> List.of();
        };
    }

    List<LeaderRecord> parseNatoHtml(OrgConfig org, String html) {
        Map<String, String> navLabelsByHref = new LinkedHashMap<>();
        Matcher navMatcher = NATO_NAV_LINK_PATTERN.matcher(html);
        while (navMatcher.find()) {
            navLabelsByHref.putIfAbsent(navMatcher.group(2).trim(), cleanText(navMatcher.group(1)));
        }

        Map<String, LeaderRecord> byKey = new LinkedHashMap<>();
        Matcher m = NATO_CARD_PATTERN.matcher(html);

        while (m.find()) {
            String href = cleanText(m.group(1));
            String roleTitle = cleanText(m.group(2));

            String rawLabel = navLabelsByHref.get(href);
            String fullName = null;
            if (rawLabel != null && !rawLabel.isBlank()) {
                fullName = NATO_RANK_PREFIX_PATTERN.matcher(rawLabel).replaceFirst("").trim();
            } else if (roleTitle.startsWith("Secretary General ")) {
                fullName = roleTitle.substring("Secretary General ".length()).trim();
            }

            if (fullName == null || fullName.isBlank()) {
                continue;
            }

            if (roleTitle.endsWith(fullName)) {
                roleTitle = roleTitle.substring(0, roleTitle.length() - fullName.length()).trim();
            }
            if (!roleTitle.toUpperCase(Locale.ROOT).startsWith("NATO")) {
                roleTitle = "NATO " + roleTitle;
            }

            String profileUrl = href.startsWith("http") ? href : "https://www.nato.int" + href;
            String position = roleTitle + " – " + org.name();
            String[] fl = splitFirstLast(fullName);
            byKey.putIfAbsent(fullName.toLowerCase(Locale.ROOT), new LeaderRecord(
                    fullName, fl[0], fl[1], position, profileUrl,
                    org.code(), org.acronym(), org.name(), org.headquarters()
            ));
        }

        return new ArrayList<>(byKey.values());
    }

    List<LeaderRecord> parseBeneluxHtml(OrgConfig org, String html) {
        Map<String, LeaderRecord> byKey = new LinkedHashMap<>();
        Matcher rm = BENELUX_ROW_PATTERN.matcher(html);

        while (rm.find()) {
            String rowHtml = rm.group(1);
            Matcher tm = BENELUX_TITULAIRE_PATTERN.matcher(rowHtml);
            if (!tm.find()) {
                continue;
            }
            String fullName = cleanText(tm.group(1));
            if (fullName.isBlank() || fullName.length() < 4) {
                continue;
            }

            String role = "Secrétaire général / Secrétaire générale (Collège des Secrétaires généraux)";
            Matcher roleMatcher = BENELUX_ROLE_PATTERN.matcher(rowHtml);
            if (roleMatcher.find()) {
                role = cleanText(roleMatcher.group(1));
            }

            String position = role + " – " + org.name();
            String[] fl = splitFirstLast(fullName);
            byKey.putIfAbsent(fullName.toLowerCase(Locale.ROOT), new LeaderRecord(
                    fullName, fl[0], fl[1], position, org.url(),
                    org.code(), org.acronym(), org.name(), org.headquarters()
            ));
        }

        return new ArrayList<>(byKey.values());
    }

    List<LeaderRecord> parseWcoHtml(OrgConfig org, String html) {
        Map<String, LeaderRecord> byKey = new LinkedHashMap<>();
        Matcher m = WCO_BLOCK_PATTERN.matcher(html);

        while (m.find()) {
            String role = cleanText(m.group(1));
            String fullName = cleanText(m.group(2));
            if (fullName.isBlank()) {
                continue;
            }
            String position = role + " de l'" + org.name();
            String[] fl = splitFirstLast(fullName);
            byKey.putIfAbsent(fullName.toLowerCase(Locale.ROOT), new LeaderRecord(
                    fullName, fl[0], fl[1], position, org.url(),
                    org.code(), org.acronym(), org.name(), org.headquarters()
            ));
        }

        return new ArrayList<>(byKey.values());
    }

    public static void writeRecord(LeaderRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", rec.orgCode(), rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", rec.position());
        person.addValue("sourceUrl", rec.profileUrl());
        person.addValue("notes", "Organisation internationale : " + rec.orgName()
                + " — Siège : " + rec.headquarters() + " (Belgique)");
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", rec.orgCode(), rec.position());
        position.addValue("name", rec.position());
        position.addValue("country", COUNTRY);
        position.addValue("organization", rec.orgName());
        position.addValue("topics", "gov.igo");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.orgCode(), rec.fullName(), rec.position());
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("sourceUrl", rec.profileUrl());
        dataset.addEntity(occ);
    }

    static String[] splitFirstLast(String fullName) {
        String cleaned = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        int space = cleaned.indexOf(' ');
        if (space < 0) {
            return new String[]{"", cleaned};
        }
        return new String[]{cleaned.substring(0, space).trim(), cleaned.substring(space + 1).trim()};
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&rsquo;", "'")
                .replace("&#233;", "é")
                .replace("&eacute;", "é")
                .replace("&Ouml;", "Ö")
                .replace("&ouml;", "ö")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
