package com.swiftcheck.aml.plugins.depep.parties;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * <b>de_pep_parties</b> – Membres des organes dirigeants des partis politiques représentés au Bundestag.
 * <p>
 * Catégorie <b>DE-c</b> (§ 1 (12) c) GwG ; liste JO C/2023/724).
 * Source : Unterlagensammlung der Bundeswahlleiterin (§ 6 (3) PartG) et déclarations officielles des partis.
 */
@Extension
public class DePepPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepPartiesParser.class);

    static final String ID_PREFIX = "de-pep-parties";
    static final String TOPIC = "role.pep";
    static final String CONFIG_RESOURCE = "/parties.json";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern LINE_ROLE_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:[-*•]\\s*)?((?:Bundes)?vorsitzende[rn]?|Stellvertretende[rn]?\\s+(?:Bundes)?vorsitzende[rn]?|Generalsekretär(?:in)?|Schatzmeister(?:in)?|Bundesgeschäftsführer(?:in)?|Beisitzer(?:in)?|Mitglied des (?:Bundes|Partei)vorstands)\\s*[:\\-]\\s*(.+)$");

    private static final Pattern HTML_ROW_PATTERN = Pattern.compile(
            "(?si)<(?:tr|li|div)[^>]*>\\s*(?:<(?:td|span|strong|p)[^>]*>\\s*([^<]+?)\\s*</(?:td|span|strong|p)>)?\\s*<(?:td|span|p|a)[^>]*>\\s*([^<]+?)\\s*</(?:td|span|p|a)>\\s*</(?:tr|li|div)>");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PartyConfig(String key, String name, String shortName, String boardName, boolean bundestag) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(List<PartyConfig> parties) {
    }

    record BoardMember(String partyKey, String partyName, String partyShortName, String role,
                       String fullName, String title, String cleanName, LocalDate docDate) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<BoardMember> allMembers = new ArrayList<>();

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String content = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            PartyConfig defaultParty = config.parties().get(0);
            allMembers.addAll(parseContent(content, defaultParty));
        }

        if (ctx.rawDir() != null && Files.isDirectory(ctx.rawDir())) {
            for (PartyConfig p : config.parties()) {
                Path partyFile = ctx.rawDir().resolve(p.key() + ".txt");
                if (!Files.isRegularFile(partyFile)) {
                    partyFile = ctx.rawDir().resolve(p.key() + ".html");
                }
                if (partyFile.equals(ctx.inputFile())) {
                    continue;
                }
                if (Files.isRegularFile(partyFile)) {
                    String content = Files.readString(partyFile, StandardCharsets.UTF_8);
                    allMembers.addAll(parseContent(content, p));
                }
            }
        }

        if (allMembers.isEmpty()) {
            throw new IllegalStateException("Aucun membre de direction de parti politique extrait.");
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(allMembers, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());

        return dataset.toConversionResult().withMessage(
                allMembers.size() + " membres des organes dirigeants des partis politiques extraits.");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Config config = loadConfig(ctx);
        List<BoardMember> members = new ArrayList<>();
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            members.addAll(parseContent(Files.readString(ctx.inputFile(), StandardCharsets.UTF_8), config.parties().get(0)));
        }
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);
        List<SourceRecord> records = new ArrayList<>();
        for (BoardMember m : members) {
            records.add(new SourceRecord(
                    ids.buildEntityId(m.partyKey(), m.cleanName()),
                    "Person",
                    m.fullName(),
                    m.partyShortName() + " – " + m.role()
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
        try (InputStream in = DePepPartiesParser.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource " + CONFIG_RESOURCE + " introuvable dans le JAR.");
            }
            return mapper.readValue(in, Config.class);
        }
    }

    static List<BoardMember> parseContent(String content, PartyConfig party) {
        List<BoardMember> list = new ArrayList<>();
        // Détection format ligne à ligne (texte / compte-rendu Bundeswahlleiterin)
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher m = LINE_ROLE_PATTERN.matcher(trimmed);
            if (m.find()) {
                String role = m.group(1).trim();
                String namesStr = m.group(2).trim();
                for (String singleName : namesStr.split("[,;]|\\bund\\b")) {
                    String candidate = singleName.trim();
                    if (!candidate.isEmpty()) {
                        addMember(list, party, role, candidate);
                    }
                }
            }
        }

        // Si rien n'a été trouvé via les lignes, essayer format HTML (table ou listes)
        if (list.isEmpty()) {
            Matcher m = HTML_ROW_PATTERN.matcher(content);
            while (m.find()) {
                String col1 = m.group(1) != null ? m.group(1).trim() : null;
                String col2 = m.group(2) != null ? m.group(2).trim() : null;
                if (col1 != null && col2 != null && !col1.equalsIgnoreCase(col2)) {
                    if (isRole(col1)) {
                        addMember(list, party, col1, col2);
                    } else if (isRole(col2)) {
                        addMember(list, party, col2, col1);
                    }
                }
            }
        }

        return list;
    }

    private static boolean isRole(String text) {
        String lower = text.toLowerCase();
        return lower.contains("vorsitz") || lower.contains("sekretär") || lower.contains("schatzmeister")
                || lower.contains("geschäftsführer") || lower.contains("beisitzer") || lower.contains("mitglied");
    }

    private static void addMember(List<BoardMember> list, PartyConfig party, String role, String rawName) {
        if (rawName.equalsIgnoreCase("Name") || rawName.equalsIgnoreCase("Funktion") || rawName.contains("Partei")) {
            return;
        }
        String title = null;
        String cleanName = rawName;
        Matcher tm = ACADEMIC_TITLE.matcher(rawName);
        if (tm.find()) {
            title = tm.group(0).trim();
            cleanName = rawName.substring(tm.end()).trim();
        }
        list.add(new BoardMember(party.key(), party.name(), party.shortName(), role, rawName, title, cleanName, null));
    }

    private void convert(List<BoardMember> members, FtmDatasetBuilder dataset) {
        for (BoardMember m : members) {
            String posKey = m.partyKey() + "-vorstand";

            FtmEntity position = dataset.newEntity("Position", posKey);
            position.addValue("name", "Mitglied des Bundesvorstands (" + m.partyShortName() + ")");
            position.addValue("country", "de");
            position.addValue("organization", m.partyName());
            position.addValue("topics", "gov.national");
            position.addValue("topics", "pol.party");
            dataset.addEntity(position);

            FtmEntity person = dataset.newEntity("Person", m.partyKey(), m.cleanName());
            person.addValue("name", m.cleanName());
            person.addValue("title", m.title());
            String[] parts = m.cleanName().split("\\s+", 2);
            person.addValue("firstName", parts[0]);
            if (parts.length > 1) {
                person.addValue("lastName", parts[1]);
            }
            person.addValue("country", "de");
            person.addValue("political", m.partyShortName());
            person.addValue("position", m.role() + " (" + m.partyShortName() + ")");
            dataset.addEntity(person);

            FtmEntity occ = dataset.newEntity("Occupancy", m.partyKey(), m.cleanName(), "vorstand");
            occ.addValue("holder", person.getId());
            occ.addValue("post", position.getId());
            occ.addValue("status", "current");
            occ.addValue("description", m.role());
            dataset.addEntity(occ);
        }
    }
}

