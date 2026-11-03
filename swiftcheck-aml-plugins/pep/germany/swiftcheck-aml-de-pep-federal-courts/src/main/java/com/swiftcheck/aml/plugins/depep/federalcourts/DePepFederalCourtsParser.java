package com.swiftcheck.aml.plugins.depep.federalcourts;

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
 * <b>de_pep_federal_courts</b> – Juges des 6 cours fédérales suprêmes d'Allemagne.
 * <p>
 * Catégorie <b>DE-d</b> (§ 1 (12) d) GwG ; liste JO C/2023/724).
 * Couvre les juges de la Cour constitutionnelle fédérale (BVerfG) et des 5 cours fédérales suprêmes (BGH, BVerwG, BAG, BSG, BFH).
 */
@Extension
public class DePepFederalCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepFederalCourtsParser.class);

    static final String ID_PREFIX = "de-pep-federal-courts";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/federal-courts.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article|tr)[^>]*class=\"[^\"]*(?:teaser|card|member|person|richter|judge|senat|entry)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|member|person|richter|judge|senat|entry)|<li[^>]*class=\"[^\"]*(?:teaser|card|member|person|richter)|</(?:body|ul|main|table)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<(?:h[2-4]|td|strong|b)[^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</(?:h[2-4]|td|strong|b)>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span|td)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|amt)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span|td)>");

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(?si)<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtConfig(String key, String name, String acronym, String city, String positionName, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<CourtConfig> courts) {
    }

    record Judge(String courtKey, String courtName, String courtAcronym, String positionName,
                 String fullName, String title, String cleanName, String role, String photoUrl, String profileUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Judge> allJudges = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            CourtConfig defaultCourt = config.courts().stream()
                    .filter(c -> "bverfg".equalsIgnoreCase(c.key()))
                    .findFirst()
                    .orElse(config.courts().get(0));
            allJudges.addAll(extractJudges(html, defaultCourt));
        }

        if (ctx.rawDir() != null && Files.isDirectory(ctx.rawDir())) {
            for (CourtConfig c : config.courts()) {
                Path courtFile = ctx.rawDir().resolve(c.key() + ".html");
                if (courtFile.equals(ctx.inputFile())) {
                    continue;
                }
                if (Files.isRegularFile(courtFile)) {
                    String html = Files.readString(courtFile, StandardCharsets.UTF_8);
                    allJudges.addAll(extractJudges(html, c));
                }
            }
        }

        if (allJudges.isEmpty()) {
            throw new IllegalStateException("Aucun juge fédéral extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(allJudges, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allJudges.size() + " juges des cours fédérales suprêmes extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<Judge> judges = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            judges.addAll(extractJudges(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config.courts().get(0)));
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (Judge j : judges) {
            records.add(new SourceRecord(
                    ids.buildEntityId(j.courtKey(), j.cleanName()),
                    "Person",
                    j.fullName(),
                    j.courtAcronym() + " – " + j.role()
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
        try (InputStream in = DePepFederalCourtsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<Judge> extractJudges(String html, CourtConfig court) {
        List<Judge> judges = new ArrayList<>();
        Matcher cardMatcher = CARD_PATTERN.matcher(html);
        while (cardMatcher.find()) {
            String card = cardMatcher.group(1);
            Matcher headMatcher = HEADING_PATTERN.matcher(card);
            if (!headMatcher.find()) {
                continue;
            }
            String profileUrl = headMatcher.group(1);
            String rawName = headMatcher.group(2).trim();
            if (rawName.equalsIgnoreCase("Senate") || rawName.equalsIgnoreCase("Senat")
                    || rawName.equalsIgnoreCase("Organisation")
                    || rawName.contains("Gericht") && !rawName.contains(" ")) {
                continue;
            }

            String title = null;
            String cleanName = rawName;
            Matcher tm = ACADEMIC_TITLE.matcher(rawName);
            if (tm.find()) {
                title = tm.group(0).trim();
                cleanName = rawName.substring(tm.end()).trim();
            }

            String role = null;
            Matcher rm = ROLE_PATTERN.matcher(card);
            if (rm.find()) {
                role = rm.group(1).trim();
            }
            if (role == null) {
                if (card.contains("Präsidentin")) {
                    role = "Präsidentin des " + court.name();
                } else if (card.contains("Präsident") && !card.contains("Vizepräsident")) {
                    role = "Präsident des " + court.name();
                } else if (card.contains("Vizepräsidentin") || card.contains("Vizepräsident")) {
                    role = "Vizepräsident/in des " + court.name();
                } else {
                    role = court.positionName();
                }
            }

            String photoUrl = null;
            Matcher im = IMG_PATTERN.matcher(card);
            if (im.find()) {
                photoUrl = im.group(1);
            }

            judges.add(new Judge(court.key(), court.name(), court.acronym(), court.positionName(),
                    rawName, title, cleanName, role, photoUrl, profileUrl));
        }

        return judges;
    }

    private void convert(List<Judge> judges, FtmDatasetBuilder dataset) {
        for (Judge j : judges) {
            String posKey = j.courtKey() + "-judge";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", j.positionName());
            position.addValue("country", "de");
            position.addValue("organization", j.courtName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.judicial");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", j.courtKey(), j.cleanName());
            person.addValue("name", j.cleanName());
            person.addValue("title", j.title());
            String[] parts = j.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", j.role());
            if (j.photoUrl() != null) {
                person.addValue("imageUrl", j.photoUrl());
            }
            if (j.profileUrl() != null) {
                person.addValue("sourceUrl", j.profileUrl());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", j.courtKey(), j.cleanName(), "judge");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", j.role());
            dataset.addEntity(occ);
        }
    }
}

