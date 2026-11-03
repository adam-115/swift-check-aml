package com.swiftcheck.aml.plugins.depep.internationalorgs;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_international_orgs</b> – Dirigeants des organisations internationales établies en Allemagne.
 * <p>
 * Catégorie <b>DE-i</b> (§ 1 (12) i) GwG ; liste JO C/2023/724).
 * Couvre l'OEB (Munich), EUMETSAT (Darmstadt), l'EMBL (Heidelberg), l'ESO (Garching), l'OCCAR (Bonn), l'ONU à Bonn (UNFCCC, UNCCD) et le TIDM/ITLOS (Hambourg).
 */
@Extension
public class DePepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepInternationalOrgsParser.class);

    static final String ID_PREFIX = "de-pep-intorgs";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/international-orgs.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article|tr)[^>]*class=\"[^\"]*(?:teaser|card|member|leader|person|director|entry)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|member|leader|person|director|entry)|<li[^>]*class=\"[^\"]*(?:teaser|card|member|leader)|</(?:body|ul|main|table)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<(?:h[2-4]|td|strong|b)[^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</(?:h[2-4]|td|strong|b)>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span|td)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|title)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span|td)>");

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(?si)<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeadershipConfig(String role, String pattern) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrgConfig(String key, String name, String city, List<LeadershipConfig> leadership, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<OrgConfig> organizations) {
    }

    record Leader(String orgKey, String orgName, String city, String role,
                  String fullName, String title, String cleanName, String photoUrl, String profileUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Leader> allLeaders = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String content = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            OrgConfig defaultOrg = config.organizations().stream()
                    .filter(o -> "epo".equalsIgnoreCase(o.key()))
                    .findFirst()
                    .orElse(config.organizations().get(0));
            allLeaders.addAll(extractLeaders(content, defaultOrg));
        }

        if (ctx.rawDir() != null && Files.isDirectory(ctx.rawDir())) {
            for (OrgConfig org : config.organizations()) {
                Path orgFile = ctx.rawDir().resolve(org.key() + ".html");
                if (!Files.isRegularFile(orgFile)) {
                    orgFile = ctx.rawDir().resolve(org.key() + ".txt");
                }
                if (orgFile.equals(ctx.inputFile())) {
                    continue;
                }
                if (Files.isRegularFile(orgFile)) {
                    String content = Files.readString(orgFile, StandardCharsets.UTF_8);
                    allLeaders.addAll(extractLeaders(content, org));
                }
            }
        }

        if (allLeaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'organisation internationale extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(allLeaders, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allLeaders.size() + " dirigeants d'organisations internationales en Allemagne extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Leader> leaders = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            leaders.addAll(extractLeaders(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config.organizations().get(0)));
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Leader l : leaders) {
            records.add(new SourceRecord(
                    ids.buildEntityId(l.orgKey(), l.cleanName()),
                    "Person",
                    l.fullName(),
                    l.orgName() + " – " + l.role()
            ));
        }
        return records;
    }

    static Config loadConfig(ParserContext ctx) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String configFile = ctx.parameters().get("configFile");
        if (configFile != null && !configFile.isBlank()) {
            Path p = Path.of(configFile);
            if (Files.isRegularFile(p)) {
                return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), Config.class);
            }
        }
        try (InputStream in = DePepInternationalOrgsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<Leader> extractLeaders(String content, OrgConfig org) {
        List<Leader> list = new ArrayList<>();

        // 1. Text pattern matching d'après les motifs configurés
        if (org.leadership() != null) {
            for (LeadershipConfig lead : org.leadership()) {
                Pattern pat = Pattern.compile("(?i)" + lead.pattern());
                for (String line : content.split("\\r?\\n")) {
                    Matcher m = pat.matcher(line.trim());
                    if (m.find()) {
                        String rawName = m.group(1).trim();
                        for (String single : rawName.split("[,;]|\\bund\\b")) {
                            String name = single.trim();
                            if (!name.isEmpty() && !name.contains("<") && !name.equalsIgnoreCase("Name")) {
                                addLeader(list, org, lead.role(), name, null, null);
                            }
                        }
                    }
                }
            }
        }

        // 2. Format HTML cards
        if (list.isEmpty()) {
            Matcher cardMatcher = CARD_PATTERN.matcher(content);
            while (cardMatcher.find()) {
                String card = cardMatcher.group(1);
                Matcher headMatcher = HEADING_PATTERN.matcher(card);
                if (!headMatcher.find()) {
                    continue;
                }
                String profileUrl = headMatcher.group(1);
                String rawName = headMatcher.group(2).trim();
                if (rawName.equalsIgnoreCase("Leadership") || rawName.equalsIgnoreCase("Management")
                        || rawName.contains("Organisation") && !rawName.contains(" ")) {
                    continue;
                }

                String role = "Dirigeant";
                Matcher rm = ROLE_PATTERN.matcher(card);
                if (rm.find()) {
                    role = rm.group(1).trim();
                }

                String photoUrl = null;
                Matcher im = IMG_PATTERN.matcher(card);
                if (im.find()) {
                    photoUrl = im.group(1);
                }

                addLeader(list, org, role, rawName, photoUrl, profileUrl);
            }
        }

        return list;
    }

    private static void addLeader(List<Leader> list, OrgConfig org, String role, String rawName, String photo, String profile) {
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new Leader(org.key(), org.name(), org.city(), role, rawName, title, cleanName, photo, profile));
    }

    private void convert(List<Leader> leaders, FtmDatasetBuilder dataset) {
        for (Leader l : leaders) {
            String posKey = l.orgKey() + "-leadership";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", l.role() + " (" + l.orgName() + ")");
            position.addValue("country", "de");
            position.addValue("organization", l.orgName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.igo");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", l.orgKey(), l.cleanName());
            person.addValue("name", l.cleanName());
            person.addValue("title", l.title());
            String[] parts = l.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", l.role() + " (" + l.orgName() + ")");
            if (l.photoUrl() != null) {
                person.addValue("imageUrl", l.photoUrl());
            }
            if (l.profileUrl() != null) {
                person.addValue("sourceUrl", l.profileUrl());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", l.orgKey(), l.cleanName(), "leadership");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", l.role() + " (" + l.city() + ")");
            dataset.addEntity(occ);
        }
    }
}

