package com.swiftcheck.aml.plugins.lupep.politicalparties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.WikidataImages;

/**
 * <b>lu_pep_political_parties</b> – Membres des organes dirigeants des partis politiques luxembourgeois (LU-c).
 * <p>
 * Catégorie <b>LU-c</b> (loi modifiée du 12 novembre 2004, art. 7-1 ; liste JO C/2023/724).
 * Source principale live : Wikidata SPARQL (pays Q32 - Luxembourg).
 * Source de secours : baseline officielle intégrée ({@value #BASELINE_RESOURCE}).
 */
@Extension
public class LuPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "lu-pep-parties";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q32";
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    private static final Map<String, String> ROLES = Map.of(
            "chair", "Président(e) du parti",
            "secretaryGeneral", "Secrétaire général(e)",
            "director", "Direction du parti"
    );

    private static final String PREFERRED_RANK = "http://wikiba.se/ontology#PreferredRank";
    private static final String MALE = "Q6581097";
    private static final String FEMALE = "Q6581072";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "^(?:(?:Prof\\.?\\s+Dr\\.?|Prof\\.?|Dr\\.?|h\\.c\\.)\\s+)+");

    private static final Pattern LINE_ROLE_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:[-*•]\\s*)?((?:Bundes)?vorsitzende[rn]?|Stellvertretende[rn]?\\s+(?:Bundes)?vorsitzende[rn]?|Generalsekretär(?:in)?|Schatzmeister(?:in)?|Bundesgeschäftsführer(?:in)?|Beisitzer(?:in)?|Mitglied des (?:Bundes|Partei)vorstands)\\s*[:\\-]\\s*(.+)$");

    private final ObjectMapper json = new ObjectMapper();

    record Statement(String id, String partyQid, String partyLabel, String role, boolean preferred, String start,
                     String end, String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    record Selection(List<Statement> current, List<Statement> former, int superseded, int endedLongAgo) {
    }

    record Report(int parties, int persons, int current, int former, int superseded, int endedLongAgo, int unnamed) {
        String message() {
            String m = String.format("%d partis politiques luxembourgeois dans Wikidata : %d dirigeants, "
                            + "%d mandats en cours, %d terminés depuis moins de 12 mois. Écartés : %d mandats "
                            + "remplacés, %d terminés depuis plus longtemps.",
                    parties, persons, current, former, superseded, endedLongAgo);
            return unnamed == 0 ? m : m + String.format(" %d dirigeants sans nom écartés.", unnamed);
        }
    }

    record BaselineMember(String party, String role, String fullName, String title, String cleanName) {
    }

    /** Téléchargement des dirigeants, remplaçable dans les tests. */
    @FunctionalInterface
    interface Downloader {
        void download(String endpoint, String countryQid, Path rawDir) throws Exception;
    }

    private final Downloader downloader;

    public LuPepPoliticalPartiesParser() {
        this(new WikidataPartiesDownloader()::download);
    }

    LuPepPoliticalPartiesParser(Downloader downloader) {
        this.downloader = downloader;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        // 1. Si un fichier d'entrée textuel est directement fourni
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String content = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            List<BaselineMember> members = parseBaselineContent(content);
            convertBaseline(members, dataset);
            dataset.writeNdjsonFile(ctx.outputFile());
            return dataset.toConversionResult().withMessage(members.size() + " dirigeants de partis politiques extraits depuis le fichier fourni.");
        }

        // 2. Tenter l'interrogation live Wikidata SPARQL
        Path leadersFile = ctx.rawDir() != null ? ctx.rawDir().resolve(WikidataPartiesDownloader.LEADERS_FILE) : null;
        boolean liveSuccess = false;
        Report report = null;

        if (leadersFile != null) {
            try {
                {
                    downloader.download(
                            p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(),
                            p.getOrDefault("country", DEFAULT_COUNTRY).trim(),
                            ctx.rawDir()
                    );
                }
                if (Files.isRegularFile(leadersFile) && Files.size(leadersFile) > 50) {
                    LocalDate since = LocalDate.now().minusMonths(monthsAfterEnd(p));
                    report = convertWikidata(ctx.rawDir(), since, dataset);
                    liveSuccess = true;
                }
            } catch (Exception e) {
                log.warn("Impossible de télécharger ou parser les dirigeants depuis Wikidata live ({})", e.getMessage());
            }
        }

        if (!liveSuccess || dataset.entities().isEmpty()) {
            throw new IllegalStateException("Aucun membre de direction de parti politique extrait depuis Wikidata live.");
        }

        // Complément photos Wikimedia (non bloquant)
        String photos = "";
        try {
            photos = WikidataImages.addPhotos(dataset, WikidataImages.QID, WikidataImages.personIdSuffixes(dataset),
                    WikidataImages.httpFetcher(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), WikidataImages.DEFAULT_USER_AGENT),
                    ctx.rawDir().resolve(WikidataImages.PHOTOS_FILE));
        } catch (Exception e) {
            log.debug("Photos Wikidata ignorées : {}", e.getMessage());
        }

        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage((report != null ? report.message() : "") + " " + photos.trim());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Path leadersFile = ctx.rawDir() != null ? ctx.rawDir().resolve(WikidataPartiesDownloader.LEADERS_FILE) : null;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        if (leadersFile != null && Files.isRegularFile(leadersFile)) {
            convertWikidata(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset);
            List<SourceRecord> records = new ArrayList<>();
            for (FtmEntity entity : dataset.entities()) {
                if ("Person".equals(entity.getSchema())) {
                    records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                            String.join(", ", entity.getValues("sourceUrl"))));
                }
            }
            return records;
        }

        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            String text = Files.readString(ctx.inputFile(), StandardCharsets.UTF_8);
            List<BaselineMember> members = parseBaselineContent(text);
            List<SourceRecord> records = new ArrayList<>();
            for (BaselineMember m : members) {
                records.add(new SourceRecord(
                        dataset.buildEntityId(m.party(), m.cleanName()),
                        "Person",
                        m.fullName(),
                        m.party() + " – " + m.role()
                ));
            }
            return records;
        }

        return List.of();
    }

    Report convertWikidata(Path rawDir, LocalDate since, FtmDatasetBuilder dataset) throws Exception {
        JsonNode response = json.readTree(Files.readString(rawDir.resolve(WikidataPartiesDownloader.LEADERS_FILE)));
        List<Statement> statements = new ArrayList<>();
        int unnamed = 0;
        for (Statement s : statements(response)) {
            if (s.personLabel().isBlank() || s.personLabel().equals(s.personQid())) {
                unnamed++;
            } else {
                statements.add(s);
            }
        }
        Selection selection = select(statements, since);
        if (selection.current().isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de parti en fonction dans la réponse de Wikidata.");
        }

        Set<String> parties = new LinkedHashSet<>();
        Set<String> persons = new LinkedHashSet<>();
        for (Statement s : selection.current()) {
            writeWikidataEntity(dataset, s, true);
            parties.add(s.partyQid());
            persons.add(s.personQid());
        }
        for (Statement s : selection.former()) {
            writeWikidataEntity(dataset, s, false);
            parties.add(s.partyQid());
            persons.add(s.personQid());
        }
        return new Report(parties.size(), persons.size(), selection.current().size(),
                selection.former().size(), selection.superseded(), selection.endedLongAgo(), unnamed);
    }

    static List<Statement> statements(JsonNode response) {
        Map<String, Statement> byId = new LinkedHashMap<>();
        for (JsonNode b : response.path("results").path("bindings")) {
            String id = text(b, "statement");
            Statement s = byId.get(id);
            if (s == null) {
                s = new Statement(id, qid(text(b, "party")), text(b, "partyLabel"), text(b, "role"),
                        PREFERRED_RANK.equals(text(b, "rank")), date(text(b, "start")), date(text(b, "end")),
                        qid(text(b, "person")), text(b, "personLabel"), new LinkedHashSet<>(),
                        qid(text(b, "gender")));
                byId.put(id, s);
            }
            String birth = birthDate(text(b, "birth"), text(b, "birthPrecision"));
            if (birth != null) {
                s.birthDates().add(birth);
            }
        }
        return new ArrayList<>(byId.values());
    }

    static Selection select(List<Statement> statements, LocalDate since) {
        Map<String, List<Statement>> groups = new LinkedHashMap<>();
        for (Statement s : statements) {
            groups.computeIfAbsent(s.partyQid() + "|" + s.role(), k -> new ArrayList<>()).add(s);
        }
        List<Statement> current = new ArrayList<>();
        List<Statement> former = new ArrayList<>();
        int superseded = 0;
        int endedLongAgo = 0;
        String sinceText = since.toString();
        for (List<Statement> group : groups.values()) {
            boolean hasPreferred = group.stream().anyMatch(Statement::preferred);
            for (Statement s : group) {
                if (s.end() != null) {
                    if (s.end().compareTo(sinceText) >= 0) {
                        former.add(s);
                    } else {
                        endedLongAgo++;
                    }
                } else if (hasPreferred ? !s.preferred() : startedBefore(s, group)) {
                    superseded++;
                } else {
                    current.add(s);
                }
            }
        }
        return new Selection(current, former, superseded, endedLongAgo);
    }

    private static boolean startedBefore(Statement s, List<Statement> group) {
        return s.start() != null && group.stream()
                .anyMatch(o -> o != s && o.start() != null && o.start().compareTo(s.start()) > 0);
    }

    private static void writeWikidataEntity(FtmDatasetBuilder dataset, Statement s, boolean isCurrent) {
        String function = ROLES.getOrDefault(s.role(), "Parteileitung") + " – " + s.partyLabel();

        FtmEntity person = dataset.newEntity("Person", s.personQid());
        person.addValue("name", s.personLabel());
        person.addValue("wikidataId", s.personQid());
        s.birthDates().forEach(d -> person.addValue("birthDate", d));
        if (MALE.equals(s.gender())) {
            person.addValue("gender", "male");
        } else if (FEMALE.equals(s.gender())) {
            person.addValue("gender", "female");
        }
        person.addValue("country", "lu");
        person.addValue("political", s.partyLabel());
        person.addValue("position", isCurrent ? function : "Ancien : " + function);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.partyQid(), s.role());
        position.addValue("name", function);
        position.addValue("country", "lu");
        position.addValue("organization", s.partyLabel());
        position.addValue("wikidataId", s.partyQid());
        position.addValue("topics", "gov.national");
        position.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.partyQid());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", s.personQid(), s.partyQid(), s.role(),
                s.start() == null ? "" : s.start());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", s.start());
        occupancy.addValue("endDate", s.end());
        occupancy.addValue("status", isCurrent ? "current" : "ended");
        occupancy.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.partyQid());
        dataset.addEntity(occupancy);
    }

    static void convertBaseline(List<BaselineMember> members, FtmDatasetBuilder dataset) {
        for (BaselineMember m : members) {
            String personId = dataset.buildEntityId("person", m.party(), m.cleanName());
            FtmEntity person = dataset.newEntity("Person", personId);
            person.addValue("name", m.cleanName());
            if (m.title() != null) {
                person.addValue("title", m.title());
            }
            person.addValue("country", "lu");
            person.addValue("political", m.party());
            person.addValue("position", m.party() + " – " + m.role());
            dataset.addEntity(person);

            String posId = dataset.buildEntityId("pos", m.party(), m.role());
            FtmEntity position = dataset.newEntity("Position", posId);
            position.addValue("name", m.party() + " – " + m.role());
            position.addValue("country", "lu");
            position.addValue("organization", m.party());
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", personId, posId);
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);
        }
    }

    static List<BaselineMember> parseBaselineContent(String text) {
        List<BaselineMember> members = new ArrayList<>();
        String currentParty = "Unbekannte Partei";
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.toLowerCase().startsWith("partei:") || line.toLowerCase().startsWith("party:")) {
                currentParty = line.substring(line.indexOf(':') + 1).trim();
                continue;
            }
            Matcher matcher = LINE_ROLE_PATTERN.matcher(line);
            if (matcher.find()) {
                String role = matcher.group(1).trim();
                String namesPart = matcher.group(2).trim();
                for (String rawName : namesPart.split("[,;/]")) {
                    String clean = rawName.trim();
                    if (!clean.isEmpty()) {
                        Matcher tm = ACADEMIC_TITLE.matcher(clean);
                        String title = null;
                        String cleanName = clean;
                        if (tm.find()) {
                            title = tm.group().trim();
                            cleanName = clean.substring(tm.end()).trim();
                        }
                        members.add(new BaselineMember(currentParty, role, clean, title, cleanName));
                    }
                }
            }
        }
        return members;
    }

    static String text(JsonNode node, String field) {
        return node.path(field).path("value").asText("");
    }

    static String qid(String uri) {
        int idx = uri.lastIndexOf('/');
        return idx >= 0 ? uri.substring(idx + 1) : uri;
    }

    static String date(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : null;
    }

    static String birthDate(String value, String precision) {
        if (value == null || value.length() < 10 || value.startsWith("-")) {
            return null;
        }
        int p;
        try {
            p = Integer.parseInt(precision);
        } catch (NumberFormatException e) {
            p = 11;
        }
        return p >= 11 ? value.substring(0, 10) : p == 10 ? value.substring(0, 7) : p == 9 ? value.substring(0, 4) : null;
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String val = p.get("monthsAfterEnd");
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_MONTHS_AFTER_END;
    }
}
