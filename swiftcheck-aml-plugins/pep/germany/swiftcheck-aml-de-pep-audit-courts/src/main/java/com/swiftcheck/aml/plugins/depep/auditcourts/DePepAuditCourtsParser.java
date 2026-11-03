package com.swiftcheck.aml.plugins.depep.auditcourts;

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
 * <b>de_pep_audit_courts</b> – Cour des comptes fédérale (Bundesrechnungshof) et cours des comptes des 16 Länder.
 * <p>
 * Catégorie <b>DE-e</b> (§ 1 (12) e) GwG ; liste JO C/2023/724).
 * Couvre les présidents, vice-présidents et membres du collège (Kollegium) / directoire.
 */
@Extension
public class DePepAuditCourtsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepAuditCourtsParser.class);

    static final String ID_PREFIX = "de-pep-audit-courts";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/audit-courts.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?si)<(?:div|li|article|tr)[^>]*class=\"[^\"]*(?:teaser|card|member|person|kolleg|leitung|entry)[^\"]*\"[^>]*>(.*?)(?=<div[^>]*class=\"[^\"]*(?:teaser|card|member|person|kolleg|leitung)|<li[^>]*class=\"[^\"]*(?:teaser|card|member|person)|</(?:body|ul|main|table)>|$)");

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?si)<(?:h[2-4]|td|strong|b)[^>]*>\\s*(?:<a[^>]*href=\"([^\"]*)\"[^>]*>)?\\s*([^<]+?)\\s*(?:</a>)?\\s*</(?:h[2-4]|td|strong|b)>");

    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?si)<(?:p|div|span|td)[^>]*class=\"[^\"]*(?:role|text|subline|subtitle|function|position|amt)[^\"]*\"[^>]*>\\s*([^<]+?)\\s*</(?:p|div|span|td)>");

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "(?si)<img[^>]*\\ssrc=\"([^\"]+)\"[^>]*>");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourtConfig(String key, String name, String jurisdiction, String state, String url,
                              String cardPattern, String headingPattern, String rolePattern) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<CourtConfig> courts) {
    }

    record CourtMember(String courtKey, String courtName, String jurisdiction, String state,
                       String fullName, String title, String cleanName, String role, String photoUrl, String profileUrl) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<CourtMember> allMembers = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String html = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            // Par défaut associe au Bundesrechnungshof si brh dans le nom, sinon première cour
            CourtConfig defaultCourt = config.courts().stream()
                    .filter(c -> "brh".equalsIgnoreCase(c.key()))
                    .findFirst()
                    .orElse(config.courts().get(0));
            allMembers.addAll(extractMembers(html, defaultCourt));
        }

        // Si rawDir contient des fichiers par cour (.html)
        if (ctx.rawDir() != null && Files.isDirectory(ctx.rawDir())) {
            for (CourtConfig c : config.courts()) {
                Path courtFile = ctx.rawDir().resolve(c.key() + ".html");
                if (courtFile.equals(ctx.inputFile())) {
                    continue;
                }
                if (Files.isRegularFile(courtFile)) {
                    String html = Files.readString(courtFile, StandardCharsets.UTF_8);
                    allMembers.addAll(extractMembers(html, c));
                }
            }
        }

        if (allMembers.isEmpty()) {
            throw new IllegalStateException("Aucun membre de cour des comptes extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(allMembers, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allMembers.size() + " dirigeants de cours des comptes extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<CourtMember> members = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            members.addAll(extractMembers(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config.courts().get(0)));
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (CourtMember m : members) {
            records.add(new SourceRecord(
                    ids.buildEntityId(m.courtKey(), m.cleanName()),
                    "Person",
                    m.fullName(),
                    m.courtName() + " – " + m.role()
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
        try (InputStream in = DePepAuditCourtsParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<CourtMember> extractMembers(String html, CourtConfig court) {
        List<CourtMember> members = new ArrayList<>();
        Pattern cardPat = court.cardPattern() != null ? Pattern.compile(court.cardPattern()) : CARD_PATTERN;
        Pattern headPat = court.headingPattern() != null ? Pattern.compile(court.headingPattern()) : HEADING_PATTERN;
        Pattern rolePat = court.rolePattern() != null ? Pattern.compile(court.rolePattern()) : ROLE_PATTERN;

        Matcher cardMatcher = cardPat.matcher(html);
        while (cardMatcher.find()) {
            String card = cardMatcher.group(1);
            Matcher headMatcher = headPat.matcher(card);
            if (!headMatcher.find()) {
                continue;
            }
            String profileUrl = headMatcher.group(1);
            String rawName = headMatcher.group(2).trim();
            if (rawName.equalsIgnoreCase("Kollegium") || rawName.equalsIgnoreCase("Leitung")
                    || rawName.contains("Rechnungshof") && !rawName.contains(" ")) {
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
            Matcher rm = rolePat.matcher(card);
            if (rm.find()) {
                role = rm.group(1).trim();
            }
            if (role == null) {
                if (card.contains("Präsidentin")) {
                    role = "Präsidentin";
                } else if (card.contains("Präsident") && !card.contains("Vizepräsident")) {
                    role = "Präsident";
                } else if (card.contains("Vizepräsident")) {
                    role = "Vizepräsident";
                } else {
                    role = "Mitglied des Kollegiums";
                }
            }

            String photoUrl = null;
            Matcher im = IMG_PATTERN.matcher(card);
            if (im.find()) {
                photoUrl = im.group(1);
            }

            members.add(new CourtMember(court.key(), court.name(), court.jurisdiction(), court.state(),
                    rawName, title, cleanName, role, photoUrl, profileUrl));
        }

        return members;
    }

    private void convert(List<CourtMember> members, FtmDatasetBuilder dataset) {
        for (CourtMember m : members) {
            String posKey = m.courtKey() + "-member";
            String posName = ("FEDERAL".equalsIgnoreCase(m.jurisdiction())
                    ? "Mitglied des Bundesrechnungshofes"
                    : "Mitglied des Landesrechnungshofes (" + m.state() + ")");

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", posName);
            position.addValue("country", "de");
            position.addValue("organization", m.courtName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.financial");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", m.courtKey(), m.cleanName());
            person.addValue("name", m.cleanName());
            person.addValue("title", m.title());
            String[] parts = m.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("position", m.role() + " (" + m.courtName() + ")");
            if (m.photoUrl() != null) {
                person.addValue("imageUrl", m.photoUrl());
            }
            if (m.profileUrl() != null) {
                person.addValue("sourceUrl", m.profileUrl());
            }
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.courtKey(), m.cleanName(), "role");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            dataset.addEntity(occ);
        }
    }
}
