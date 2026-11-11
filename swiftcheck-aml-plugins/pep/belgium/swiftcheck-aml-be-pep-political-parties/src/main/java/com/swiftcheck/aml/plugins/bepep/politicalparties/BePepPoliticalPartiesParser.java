package com.swiftcheck.aml.plugins.bepep.politicalparties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * <b>be_pep_political_parties</b> – Dirigeants des partis politiques belges, d'après Wikidata.
 *
 * <p>Ce plugin couvre la catégorie <b>BE-c</b>, fonction <b>Membre de la direction, du conseil politique, du comité
 * de direction, de la gestion journalière ou du secrétariat d'un parti</b> (loi du 18 septembre 2017, art. 4, 28°,
 * et Annexe IV, point 3). Wikidata décrit le président (P488), le secrétaire général (P3975) ou le directeur/dirigeant
 * (P1037, P169) des partis politiques belges (Q31) ; la couverture est partielle (les membres des conseils politiques
 * et comités de direction n'y figurent pas intégralement).
 *
 * <p>Source PLUGIN_MANAGED : le plugin interroge lui-même le point SPARQL ({@link WikidataPartiesDownloader}) et
 * dépose la réponse dans {@code rawDir} ; la conversion ne lit ensuite que ce fichier. Paramètres :
 * <pre>{@code
 * {"endpoint": "https://query.wikidata.org/sparql", "country": "Q31", "monthsAfterEnd": "12"}
 * }</pre>
 */
@Extension
public class BePepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "be-pep-political-parties";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q31";
    /** Un dirigeant sorti reste PEP pendant 12 mois (loi du 18/09/2017, art. 66, § 3). */
    static final int DEFAULT_MONTHS_AFTER_END = 12;

    /** Rôle de la requête → libellé de la fonction. */
    private static final Map<String, String> ROLES = Map.of(
            "chair", "Président ou présidente",
            "secretaryGeneral", "Secrétaire général ou générale",
            "director", "Dirigeant ou dirigeante");

    private static final String PREFERRED_RANK = "http://wikiba.se/ontology#PreferredRank";
    private static final String MALE = "Q6581097";
    private static final String FEMALE = "Q6581072";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        new WikidataPartiesDownloader().download(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(),
                p.getOrDefault("country", DEFAULT_COUNTRY).trim(), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset, p.get("partyExecutives"));
        String photos = WikidataImages.addPhotos(dataset, WikidataImages.QID, WikidataImages.personIdSuffixes(dataset),
                WikidataImages.httpFetcher(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), WikidataImages.DEFAULT_USER_AGENT),
                ctx.rawDir().resolve(WikidataImages.PHOTOS_FILE));
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage((report.message() + " " + photos).trim());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset, p.get("partyExecutives"));
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    record Statement(String id, String partyQid, String partyLabel, String role, boolean preferred, String start,
            String end, String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    record Selection(List<Statement> current, List<Statement> former, int superseded, int endedLongAgo) {
    }

    record Report(int parties, int persons, int current, int former, int superseded, int endedLongAgo, int unnamed) {

        String message() {
            String m = String.format("%d partis belges avec des dirigeants dans Wikidata : %d dirigeants, "
                    + "%d mandats en cours, %d terminés depuis moins de la durée retenue. Écartés : %d mandats "
                    + "remplacés par un plus récent, %d terminés depuis plus longtemps.",
                    parties, persons, current, former, superseded, endedLongAgo);
            return unnamed == 0 ? m : m + String.format(" %d dirigeants sans nom dans Wikidata écartés.", unnamed);
        }
    }

    static final String SEED_EXECUTIVES_RESOURCE = "/seed/bce/party_executives.json";

    Report convert(Path rawDir, LocalDate since, FtmDatasetBuilder dataset) throws Exception {
        return convert(rawDir, since, dataset, null);
    }

    Report convert(Path rawDir, LocalDate since, FtmDatasetBuilder dataset, String partyExecutivesParam) throws Exception {
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
            throw new IllegalStateException("Aucun dirigeant de parti en fonction dans la réponse de Wikidata : "
                    + "réponse vide ou format changé.");
        }

        Set<String> parties = new LinkedHashSet<>();
        Set<String> persons = new LinkedHashSet<>();
        for (Statement s : selection.current()) {
            write(dataset, s, true);
            parties.add(s.partyQid());
            persons.add(s.personQid());
        }
        for (Statement s : selection.former()) {
            write(dataset, s, false);
            parties.add(s.partyQid());
            persons.add(s.personQid());
        }

        // Intégration des organes dirigeants et bureaux politiques élargis (ASBL / BCE)
        int seedCount = loadPartyExecutives(dataset, parties, persons, partyExecutivesParam);

        Report report = new Report(parties.size(), persons.size(), selection.current().size() + seedCount,
                selection.former().size(), selection.superseded(), selection.endedLongAgo(), unnamed);
        log.info(report.message() + (seedCount > 0 ? " [" + seedCount + " membres des organes dirigeants/bureaux politiques intégrés via BCE/ASBL]" : ""));
        return report;
    }

    private int loadPartyExecutives(FtmDatasetBuilder dataset, Set<String> parties, Set<String> persons) {
        return loadPartyExecutives(dataset, parties, persons, null);
    }

    private int loadPartyExecutives(FtmDatasetBuilder dataset, Set<String> parties, Set<String> persons, String partyExecutivesParam) {
        JsonNode root;
        if (partyExecutivesParam != null && !partyExecutivesParam.isBlank()) {
            try {
                root = json.readTree(partyExecutivesParam);
            } catch (Exception e) {
                log.warn("Erreur lecture partyExecutives depuis parameters : {}", e.getMessage());
                return 0;
            }
        } else {
            try (var in = getClass().getResourceAsStream(SEED_EXECUTIVES_RESOURCE)) {
                if (in == null) {
                    return 0;
                }
                root = json.readTree(in);
            } catch (Exception e) {
                log.warn("Erreur lecture " + SEED_EXECUTIVES_RESOURCE + " : {}", e.getMessage());
                return 0;
            }
        }
        if (!root.isArray()) {
            return 0;
        }
            int added = 0;
            for (JsonNode partyNode : root) {
                String partyName = partyNode.path("party").asText();
                String partyBce = partyNode.path("partyBce").asText();
                String legalName = partyNode.path("legalName").asText(partyName);
                String partySlug = partyBce.replaceAll("[^0-9]", "");
                parties.add("bce-" + partySlug);

                FtmEntity org = dataset.newEntity("Organization", "bce", partySlug);
                org.addValue("name", legalName);
                org.addValue("country", "be");
                org.addValue("registrationNumber", partyBce);
                org.addValue("topics", "role.pep");
                dataset.addEntity(org);

                JsonNode board = partyNode.path("board");
                if (board.isArray()) {
                    for (JsonNode member : board) {
                        String name = member.path("name").asText().trim();
                        String role = member.path("role").asText().trim();
                        String basis = member.path("pepBasis").asText("Organe dirigeant");
                        if (name.isEmpty()) {
                            continue;
                        }
                        String personSlug = normaliseSlug(name);
                        persons.add("bce-" + partySlug + "-" + personSlug);

                        FtmEntity person = dataset.newEntity("Person", "bce", partySlug, personSlug);
                        person.addValue("name", name);
                        person.addValue("country", "be");
                        person.addValue("political", partyName);
                        person.addValue("position", role + " – " + partyName + " (" + basis + ")");
                        person.addValue("sourceUrl", "https://kbopub.economie.fgov.be/kbopub/toononderneming.html?ondernemingsnummer=" + partySlug);
                        dataset.addEntity(person);

                        FtmEntity occupancy = dataset.newEntity("Occupancy", "bce", partySlug, personSlug, normaliseSlug(role));
                        occupancy.addValue("holder", person.getId());
                        occupancy.addValue("post", org.getId());
                        occupancy.addValue("description", role + " – " + basis);
                        occupancy.addValue("status", "current");
                        dataset.addEntity(occupancy);
                        added++;
                    }
                }
            }
            return added;
    }

    private static String normaliseSlug(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[àáâãäå]", "a")
                .replaceAll("[èéêë]", "e")
                .replaceAll("[ìíîï]", "i")
                .replaceAll("[òóôõö]", "o")
                .replaceAll("[ùúûü]", "u")
                .replaceAll("[ç]", "c")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
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

    private static void write(FtmDatasetBuilder dataset, Statement s, boolean isCurrent) {
        String function = ROLES.getOrDefault(s.role(), "Dirigeant ou dirigeante") + " – " + s.partyLabel();

        FtmEntity person = dataset.newEntity("Person", s.personQid());
        person.addValue("name", s.personLabel());
        person.addValue("wikidataId", s.personQid());
        s.birthDates().forEach(d -> person.addValue("birthDate", d));
        if (MALE.equals(s.gender())) {
            person.addValue("gender", "male");
        } else if (FEMALE.equals(s.gender())) {
            person.addValue("gender", "female");
        }
        person.addValue("country", "be");
        person.addValue("political", s.partyLabel());
        person.addValue("position", isCurrent ? function : "Ancien : " + function);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", s.partyQid(), s.role());
        position.addValue("name", function);
        position.addValue("country", "be");
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

    private static String date(String value) {
        return value.length() < 10 ? null : value.substring(0, 10);
    }

    private static String text(JsonNode binding, String column) {
        return binding.path(column).path("value").asText("");
    }

    private static String qid(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    static int monthsAfterEnd(Map<String, String> parameters) {
        try {
            return Integer.parseInt(parameters.getOrDefault("monthsAfterEnd", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_MONTHS_AFTER_END;
        }
    }
}

