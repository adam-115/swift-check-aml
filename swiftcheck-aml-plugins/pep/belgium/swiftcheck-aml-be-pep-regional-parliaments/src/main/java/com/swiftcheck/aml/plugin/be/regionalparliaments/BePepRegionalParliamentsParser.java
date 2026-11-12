package com.swiftcheck.aml.plugin.be.regionalparliaments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Parser pour les membres et présidents des parlements des entités fédérées belges
 * non couverts par des API dédiées au Lot 1 :
 * <ul>
 *   <li>Parlement de la Fédération Wallonie-Bruxelles ({@code pfwb.be})</li>
 *   <li>Parlement de la Communauté germanophone ({@code pdg.be})</li>
 *   <li>Parlement de la Région de Bruxelles-Capitale ({@code parlement.brussels})</li>
 * </ul>
 *
 * <p>Catégorie légale (Annexe IV de la loi belge du 18 septembre 2017) :
 * <b>BE-b</b> – <i>Membre d'un parlement régional ou communautaire en Belgique</i>.
 */
@Extension
public class BePepRegionalParliamentsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepRegionalParliamentsParser.class);

    static final String ID_PREFIX = "be-pep-regional-parliaments";
    static final String TOPIC = "role.pep";
    static final String COUNTRY = "be";
    static final String CONFIG_RESOURCE = "/regional-parliaments.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PFWB_ITEM_PATTERN = Pattern.compile(
            "<li class=\"o-people-list__item\"[^>]*>([\\s\\S]*?)</li>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PFWB_HREF_PATTERN = Pattern.compile(
            "href=\"(/les-deputes/[^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PFWB_IMG_PATTERN = Pattern.compile(
            "<div class=\"m-card-people__(?:photo|image)\"[^>]*>[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PFWB_NAME_PATTERN = Pattern.compile(
            "<span class=\"a-title[^\"]*\"[^>]*>([\\s\\S]*?)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PFWB_ROLE_PATTERN = Pattern.compile(
            "<span class=\"m-card-people__role\"[^>]*>([\\s\\S]*?)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PFWB_PARTY_PATTERN = Pattern.compile(
            "<img[^>]+alt=\"([^\"]+)\"[^>]+class=\"m-card-people__logo\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PDG_ITEM_PATTERN = Pattern.compile(
            "<li class=\"list-item contact-item[^\"]*\">([\\s\\S]*?)</li>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDG_HREF_PATTERN = Pattern.compile(
            "<a[^>]+class=\"contact-link\"[^>]+href=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDG_IMG_PATTERN = Pattern.compile(
            "<div class=\"img-contact\">[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDG_NAME_PATTERN = Pattern.compile(
            "<span class=\"name\">([^<]+)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDG_POS_PATTERN = Pattern.compile(
            "<span class=\"position\">([^<]+)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PDG_PARTY_PATTERN = Pattern.compile(
            "data-fraktion=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BRUXELLES_TR_PATTERN = Pattern.compile(
            "<tr>\\s*<td>(?:<span[^>]*>)?<a[^>]+href=\"([^\"]*)\"[^>]*>([^<]+)</a>(?:</span>)?</td>\\s*<td>([^<]*)</td>(?:\\s*<td>([^<]*)</td>)?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BRUXELLES_CARD_PATTERN = Pattern.compile(
            "<article[^>]*class=\"[^\"]*depute[^\"]*\"[\\s\\S]*?<a[^>]+href=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?(?:<span[^>]*class=\"[^\"]*parti[^\"]*\"[^>]*>([^<]+)</span>)?",
            Pattern.CASE_INSENSITIVE
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParliamentConfig(
            String code,
            String parliamentName,
            String jurisdiction,
            String membersUrl,
            String bureauUrl
    ) {}

    public record DeputyRecord(
            String fullName,
            String firstName,
            String lastName,
            String position,
            String party,
            String profileUrl,
            String photoUrl,
            String parliamentCode,
            String parliamentName,
            String jurisdiction
    ) {}

    public List<ParliamentConfig> loadConfigs() throws IOException {
        try (InputStream is = getClass().getResourceAsStream(CONFIG_RESOURCE)) {
            if (is == null) {
                throw new IOException("Ressource introuvable : " + CONFIG_RESOURCE);
            }
            return MAPPER.readValue(is, new TypeReference<List<ParliamentConfig>>() {});
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Files.createDirectories(ctx.rawDir());
        List<ParliamentConfig> configs = loadConfigs();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (ParliamentConfig cfg : configs) {
            try {
                downloadPage(client, cfg.membersUrl(), ctx.rawDir().resolve(cfg.code() + "-members.html"));
                if (cfg.bureauUrl() != null && !cfg.bureauUrl().isBlank()) {
                    try {
                        downloadPage(client, cfg.bureauUrl(), ctx.rawDir().resolve(cfg.code() + "-bureau.html"));
                    } catch (Exception e) {
                        log.warn("[be_pep_regional_parliaments] Bureau indisponible pour {} : {}",
                                cfg.parliamentName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[be_pep_regional_parliaments] Page indisponible pour {} ({}) : {}",
                        cfg.parliamentName(), cfg.membersUrl(), e.getMessage());
            }
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        List<String> active = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<DeputyRecord> deputies = convert(ctx.rawDir(), dataset, active, failed);
        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder(deputies.size() + " députés des entités fédérées extraits");
        if (!active.isEmpty()) {
            msg.append(" (").append(String.join(", ", active)).append(")");
        }
        msg.append(".");
        if (!failed.isEmpty()) {
            msg.append(" Parlements indisponibles : ").append(String.join(", ", failed)).append(".");
        }
        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset, new ArrayList<>(), new ArrayList<>());
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private static final String PFWB_ALGOLIA_CACHE_URL = "https://back.pfwb.be/api/algolia-cache-single-index";
    private static final String PFWB_ALGOLIA_CACHE_BODY =
            "index=deputy&query=&options={\"facetFilters\":[\"is_active:true\"],\"hitsPerPage\":200,\"page\":0,\"distinct\":true}";

    private void downloadPage(HttpClient client, String url, Path target) throws IOException, InterruptedException {
        HttpRequest req;
        if (url != null && url.contains("pfwb.be")) {
            req = HttpRequest.newBuilder(URI.create(PFWB_ALGOLIA_CACHE_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - parlements regionaux)")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json,text/plain,*/*")
                    .POST(HttpRequest.BodyPublishers.ofString(PFWB_ALGOLIA_CACHE_BODY, StandardCharsets.UTF_8))
                    .build();
        } else {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SwiftCheck-AML/1.0 (liste PEP Belgique - parlements regionaux)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fr-BE,fr;q=0.9,de;q=0.8")
                    .GET()
                    .build();
        }
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
        }
        Files.writeString(target, resp.body(), StandardCharsets.UTF_8);
    }

    List<DeputyRecord> convert(Path rawDir, FtmDatasetBuilder dataset, List<String> active, List<String> failed) throws IOException {
        List<ParliamentConfig> configs = loadConfigs();
        List<DeputyRecord> all = new ArrayList<>();

        for (ParliamentConfig cfg : configs) {
            Path membersFile = rawDir.resolve(cfg.code() + "-members.html");
            Path bureauFile = rawDir.resolve(cfg.code() + "-bureau.html");
            if (!Files.isRegularFile(membersFile)) {
                failed.add(cfg.parliamentName());
                continue;
            }
            String membersHtml = Files.readString(membersFile, StandardCharsets.UTF_8);
            String bureauHtml = Files.isRegularFile(bureauFile)
                    ? Files.readString(bureauFile, StandardCharsets.UTF_8)
                    : null;

            List<DeputyRecord> parlDeputies = parseParliamentHtml(cfg, membersHtml, bureauHtml);
            if (parlDeputies.isEmpty()) {
                failed.add(cfg.parliamentName());
            } else {
                active.add(cfg.parliamentName() + " : " + parlDeputies.size());
                all.addAll(parlDeputies);
            }
        }

        if (all.isEmpty()) {
            throw new IllegalStateException("Aucun parlement régional ou communautaire n'a pu être extrait depuis " + rawDir);
        }

        for (DeputyRecord rec : all) {
            writeRecord(rec, dataset);
        }
        return all;
    }

    public List<DeputyRecord> parseParliamentHtml(ParliamentConfig cfg, String membersHtml, String bureauHtml) {
        if (membersHtml == null || membersHtml.isBlank()) {
            return List.of();
        }
        return switch (cfg.code()) {
            case "pfwb" -> parsePfwbHtml(cfg, membersHtml);
            case "pdg" -> parsePdgHtml(cfg, membersHtml, bureauHtml);
            case "bruxelles" -> parseBruxellesHtml(cfg, membersHtml);
            default -> List.of();
        };
    }

    List<DeputyRecord> parseBruxellesHtml(ParliamentConfig cfg, String html) {
        Map<String, DeputyRecord> byKey = new LinkedHashMap<>();
        if (html == null || html.isBlank()) {
            return List.of();
        }

        // 1. Format table (standardisé / seed)
        Matcher trMatcher = BRUXELLES_TR_PATTERN.matcher(html);
        while (trMatcher.find()) {
            String href = cleanText(trMatcher.group(1));
            String fullName = cleanText(trMatcher.group(2));
            String party = cleanText(trMatcher.group(3));
            if (fullName.isEmpty() || fullName.equalsIgnoreCase("Nom")) continue;

            String[] fl = splitFirstLast(fullName);
            String profileUrl = href.isEmpty() ? cfg.membersUrl()
                    : (href.startsWith("http") ? href : "https://www.parlement.brussels" + (href.startsWith("/") ? href : "/" + href));

            String key = fullName.toLowerCase(Locale.ROOT);
            byKey.putIfAbsent(key, new DeputyRecord(
                    fullName, fl[0], fl[1],
                    "Député au " + cfg.parliamentName(),
                    party,
                    profileUrl,
                    null,
                    cfg.code(),
                    cfg.parliamentName(),
                    cfg.jurisdiction()
            ));
        }

        // 2. Si non trouvé par table, tenter le format cards / articles
        if (byKey.isEmpty()) {
            Matcher cardMatcher = BRUXELLES_CARD_PATTERN.matcher(html);
            while (cardMatcher.find()) {
                String href = cleanText(cardMatcher.group(1));
                String fullName = cleanText(cardMatcher.group(2));
                String party = cleanText(cardMatcher.group(3));
                if (fullName.isEmpty()) continue;

                String[] fl = splitFirstLast(fullName);
                String profileUrl = href.isEmpty() ? cfg.membersUrl()
                        : (href.startsWith("http") ? href : "https://www.parlement.brussels" + (href.startsWith("/") ? href : "/" + href));

                String key = fullName.toLowerCase(Locale.ROOT);
                byKey.putIfAbsent(key, new DeputyRecord(
                        fullName, fl[0], fl[1],
                        "Député au " + cfg.parliamentName(),
                        party,
                        profileUrl,
                        null,
                        cfg.code(),
                        cfg.parliamentName(),
                        cfg.jurisdiction()
                ));
            }
        }

        return new ArrayList<>(byKey.values());
    }

    List<DeputyRecord> parsePfwbHtml(ParliamentConfig cfg, String html) {
        Map<String, DeputyRecord> byKey = new LinkedHashMap<>();
        String trimmed = html == null ? "" : html.trim();

        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = MAPPER.readTree(trimmed);
                JsonNode hits = root.path("hits");
                if (hits.isArray()) {
                    for (JsonNode hit : hits) {
                        String firstName = cleanText(hit.path("firstname").asText(""));
                        String lastName = cleanText(hit.path("lastname").asText(""));
                        String fullName = (firstName + " " + lastName).trim();
                        if (fullName.isEmpty()) {
                            continue;
                        }
                        String roleText = cleanText(hit.path("function").asText(""));
                        String position = "Député au " + cfg.parliamentName();
                        if (!roleText.isEmpty() && !roleText.equalsIgnoreCase("Député") && !roleText.equalsIgnoreCase("Députée")) {
                            position = roleText + " – " + position;
                        }
                        String party = cleanText(hit.path("political_group").asText(""));
                        String href = cleanText(hit.path("url").asText(""));
                        String profileUrl = href.isEmpty() ? cfg.membersUrl()
                                : (href.startsWith("http") ? href : "https://www.pfwb.be" + href);
                        String imgSrc = cleanText(hit.path("image").path("file").path("url").asText(""));
                        String photoUrl = normalizePfwbUrl(imgSrc);

                        String key = fullName.toLowerCase(Locale.ROOT);
                        byKey.putIfAbsent(key, new DeputyRecord(
                                fullName, firstName, lastName, position, party, profileUrl, photoUrl,
                                cfg.code(), cfg.parliamentName(), cfg.jurisdiction()
                        ));
                    }
                    if (!byKey.isEmpty()) {
                        return new ArrayList<>(byKey.values());
                    }
                }
            } catch (Exception e) {
                log.warn("[be_pep_regional_parliaments] Erreur lecture JSON PFWB : {}", e.getMessage());
            }
        }

        Matcher itemMatcher = PFWB_ITEM_PATTERN.matcher(html);

        while (itemMatcher.find()) {
            String block = itemMatcher.group(1);
            String fullName = extractFirstGroup(PFWB_NAME_PATTERN, block);
            if (fullName.isEmpty()) {
                continue;
            }

            String roleText = extractFirstGroup(PFWB_ROLE_PATTERN, block);
            String position = "Député au " + cfg.parliamentName();
            if (!roleText.isEmpty() && !roleText.equalsIgnoreCase("Député") && !roleText.equalsIgnoreCase("Députée")) {
                position = roleText + " – " + position;
            }

            String party = extractFirstGroup(PFWB_PARTY_PATTERN, block);
            String href = extractFirstGroup(PFWB_HREF_PATTERN, block);
            String profileUrl = href.isEmpty() ? cfg.membersUrl() : "https://www.pfwb.be" + href;

            String imgSrc = extractFirstGroup(PFWB_IMG_PATTERN, block);
            String photoUrl = normalizePfwbUrl(imgSrc);

            String[] fl = splitFirstLast(fullName);
            String key = fullName.toLowerCase(Locale.ROOT);
            byKey.putIfAbsent(key, new DeputyRecord(
                    fullName, fl[0], fl[1], position, party, profileUrl, photoUrl,
                    cfg.code(), cfg.parliamentName(), cfg.jurisdiction()
            ));
        }
        return new ArrayList<>(byKey.values());
    }

    List<DeputyRecord> parsePdgHtml(ParliamentConfig cfg, String membersHtml, String bureauHtml) {
        Map<String, String> bureauRoles = new LinkedHashMap<>();
        if (bureauHtml != null && !bureauHtml.isBlank()) {
            Matcher bm = PDG_ITEM_PATTERN.matcher(bureauHtml);
            while (bm.find()) {
                String block = bm.group(1);
                String name = extractFirstGroup(PDG_NAME_PATTERN, block);
                String pos = extractFirstGroup(PDG_POS_PATTERN, block);
                if (!name.isEmpty() && !pos.isEmpty()) {
                    bureauRoles.put(name.toLowerCase(Locale.ROOT), translatePdgBureauRole(pos));
                }
            }
        }

        Map<String, DeputyRecord> byKey = new LinkedHashMap<>();
        Matcher mm = PDG_ITEM_PATTERN.matcher(membersHtml);
        while (mm.find()) {
            String block = mm.group(1);
            String fullName = extractFirstGroup(PDG_NAME_PATTERN, block);
            if (fullName.isEmpty()) {
                continue;
            }

            String key = fullName.toLowerCase(Locale.ROOT);
            String party = extractFirstGroup(PDG_PARTY_PATTERN, block);
            String bureauRole = bureauRoles.get(key);
            String position = "Membre du " + cfg.parliamentName();
            if (bureauRole != null && !bureauRole.isBlank()) {
                position = bureauRole + " – " + position;
            }

            String href = extractFirstGroup(PDG_HREF_PATTERN, block);
            String profileUrl = cfg.membersUrl();
            if (!href.isEmpty()) {
                profileUrl = href.startsWith("http") ? href : "https://pdg.be/" + href.replaceFirst("^/+", "");
            }

            String imgSrc = extractFirstGroup(PDG_IMG_PATTERN, block);
            String photoUrl = "";
            if (!imgSrc.isEmpty()) {
                photoUrl = imgSrc.startsWith("http") ? imgSrc : "https://pdg.be/" + imgSrc.replaceFirst("^/+", "");
            }

            String[] fl = splitFirstLast(fullName);
            byKey.putIfAbsent(key, new DeputyRecord(
                    fullName, fl[0], fl[1], position, party, profileUrl, photoUrl,
                    cfg.code(), cfg.parliamentName(), cfg.jurisdiction()
            ));
        }
        return new ArrayList<>(byKey.values());
    }

    public static void writeRecord(DeputyRecord rec, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", rec.parliamentCode(), rec.fullName());
        person.addValue("name", rec.fullName());
        person.addValue("firstName", rec.firstName());
        person.addValue("lastName", rec.lastName());
        person.addValue("country", COUNTRY);
        person.addValue("position", rec.position());
        person.addValue("sourceUrl", rec.profileUrl());
        person.addValue("notes", "Juridiction : " + rec.jurisdiction() + " (" + rec.parliamentName() + ")");

        if (rec.party() != null && !rec.party().isBlank()) {
            person.addValue("political", rec.party());
        }
        dataset.addEntity(person);

        if (rec.photoUrl() != null && !rec.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), rec.photoUrl(), rec.profileUrl(), "© " + rec.parliamentName());
        }

        FtmEntity position = dataset.newEntity("Position", rec.parliamentCode(), rec.position());
        position.addValue("name", rec.position());
        position.addValue("country", COUNTRY);
        position.addValue("organization", rec.parliamentName());
        position.addValue("topics", "gov.state");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occ = dataset.newEntity("Occupancy", rec.parliamentCode(), rec.fullName(), rec.position());
        occ.addValue("holder", person.getId());
        occ.addValue("post", position.getId());
        occ.addValue("status", "current");
        occ.addValue("sourceUrl", rec.profileUrl());
        dataset.addEntity(occ);
    }

    private static String translatePdgBureauRole(String germanRole) {
        String lower = germanRole.toLowerCase(Locale.ROOT);
        if (lower.contains("parlamentspräsidentin")) {
            return "Présidente du Parlement (Parlamentspräsidentin)";
        }
        if (lower.contains("parlamentspräsident")) {
            return "Président du Parlement (Parlamentspräsident)";
        }
        return germanRole;
    }

    private static String normalizePfwbUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.replace("https:///", "https://").replace("http:///", "http://").trim();
    }

    private static String extractFirstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? cleanText(m.group(1)) : "";
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
                .replace("&quot;", "\"")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
