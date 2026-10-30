package com.swiftcheck.aml.plugins.frpep.politicalparties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

/**
 * <b>fr_pep_political_parties</b> – Dirigeants des partis politiques français, d'après Wikidata.
 *
 * <p>Ce plugin couvre la catégorie <b>FR-c</b>, fonction <b>Président ou membre de l'organe exécutif d'un parti
 * politique</b> (R. 561-18 du CMF, partis au sens de la loi n° 88-27 du 11 mars 1988). Aucune source officielle ne
 * publie les dirigeants des partis : la CNCCFP ne donne que les mandataires financiers. Wikidata décrit le président
 * (P488), le secrétaire général (P3975) ou le dirigeant (P1037) d'environ 120 partis français ; la couverture est
 * donc <b>partielle</b> (les bureaux et comités exécutifs n'y sont pas).
 *
 * <p>Source PLUGIN_MANAGED : le plugin interroge lui-même le point SPARQL ({@link WikidataPartiesDownloader}) et
 * dépose la réponse dans {@code rawDir} ; la conversion ne lit ensuite que ce fichier. Paramètres :
 * <pre>{@code
 * {"endpoint": "https://query.wikidata.org/sparql", "country": "Q142", "monthsAfterEnd": "12"}
 * }</pre>
 *
 * <p>Wikidata garde l'historique des dirigeants, souvent sans date de fin : le choix des dirigeants actuels est fait
 * par {@link #select}. Pour chaque dirigeant gardé : la personne ({@code Person}, {@code role.pep}), la fonction
 * ({@code Position}, une par parti et par rôle) et le mandat ({@code Occupancy}).
 */
@Extension
public class FrPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepPoliticalPartiesParser.class);

    static final String ID_PREFIX = "fr-pep-parties";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q142";
    /** Un dirigeant sorti reste PEP un an (R. 561-18), comme les parlementaires sortis. */
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
        Report report = convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque dirigeant gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now().minusMonths(monthsAfterEnd(p)), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Une déclaration Wikidata « X dirige le parti Y » (rôle, rang, dates), avec ce qu'on sait de X. */
    record Statement(String id, String partyQid, String partyLabel, String role, boolean preferred, String start,
            String end, String personQid, String personLabel, Set<String> birthDates, String gender) {
    }

    /** Déclarations gardées et écartées par {@link #select}. */
    record Selection(List<Statement> current, List<Statement> former, int superseded, int endedLongAgo) {
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int parties, int persons, int current, int former, int superseded, int endedLongAgo, int unnamed) {

        String message() {
            String m = String.format("%d partis français avec des dirigeants dans Wikidata : %d dirigeants, "
                    + "%d mandats en cours, %d terminés depuis moins de la durée retenue. Écartés : %d mandats "
                    + "remplacés par un plus récent, %d terminés depuis plus longtemps.",
                    parties, persons, current, former, superseded, endedLongAgo);
            return unnamed == 0 ? m : m + String.format(" %d dirigeants sans nom dans Wikidata écartés.", unnamed);
        }
    }

    /**
     * Lit {@code leaders.json} dans {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé
     * sans réseau. {@code since} : un mandat terminé avant cette date n'est plus repris.
     */
    Report convert(Path rawDir, LocalDate since, FtmDatasetBuilder dataset) throws Exception {
        JsonNode response = json.readTree(Files.readString(rawDir.resolve(WikidataPartiesDownloader.LEADERS_FILE)));
        List<Statement> statements = new ArrayList<>();
        int unnamed = 0;
        for (Statement s : statements(response)) {
            // Sans libellé, le service renvoie le QID : un nom inutilisable pour le criblage
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
        Report report = new Report(parties.size(), persons.size(), selection.current().size(),
                selection.former().size(), selection.superseded(), selection.endedLongAgo(), unnamed);
        log.info(report.message());
        return report;
    }

    /**
     * Lignes SPARQL → déclarations. Une déclaration peut tenir sur plusieurs lignes (deux dates de naissance) :
     * elles sont réunies, dans l'ordre de la réponse.
     */
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

    /**
     * Choisit les mandats à publier, parti par parti et rôle par rôle. Wikidata garde les anciens dirigeants, souvent
     * sans date de fin (ex. Renaissance : Christophe Castaner, Stanislas Guerini, Stéphane Séjourné et Gabriel Attal,
     * tous « président » sans fin). Règles :
     * <ol>
     *   <li>mandat terminé : gardé comme ancien s'il a pris fin depuis {@code since}, écarté sinon ;</li>
     *   <li>mandat sans fin, quand le rôle a une déclaration au rang « préféré » : seule celle-ci est en cours
     *       (convention Wikidata pour la valeur actuelle) ; les autres sont remplacées ;</li>
     *   <li>mandat sans fin, sans rang « préféré » : remplacé si un autre mandat du même rôle a commencé après lui ;
     *       gardé sinon, y compris sans date de début (mieux vaut une alerte de trop qu'un dirigeant manqué).</li>
     * </ol>
     */
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

    /** Un autre mandat du groupe a commencé après {@code s} (les deux dates étant connues). */
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
        person.addValue("country", "fr");
        person.addValue("political", s.partyLabel());
        person.addValue("position", isCurrent ? function : "Ancien : " + function);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + s.personQid());
        dataset.addEntity(person);

        // Une fonction par parti et par rôle, commune à ses titulaires successifs : le dataset la fusionne (même ID)
        FtmEntity position = dataset.newEntity("Position", s.partyQid(), s.role());
        position.addValue("name", function);
        position.addValue("country", "fr");
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

    /**
     * Date de naissance selon sa précision Wikidata : 11 = jour, 10 = mois, 9 = année (comme {@code fr_pep_rca}).
     * Null si la précision est moindre (décennie…).
     */
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

    /** « 2022-11-05T00:00:00Z » → « 2022-11-05 » ; null si absente. */
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
