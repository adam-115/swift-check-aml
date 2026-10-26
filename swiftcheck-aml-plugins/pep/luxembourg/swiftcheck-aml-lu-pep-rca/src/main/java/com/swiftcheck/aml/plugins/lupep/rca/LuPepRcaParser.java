package com.swiftcheck.aml.plugins.lupep.rca;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
 * <b>lu_pep_rca</b> – Membres de la famille des PEP luxembourgeois, d'après Wikidata.
 *
 * <p>Ce plugin couvre la catégorie <b>LU-RCA</b>, fonction <b>Membre de la famille d'une PEP</b> (directive (UE)
 * 2015/849, art. 3, point 10) : conjoint, enfants, parents, et aussi frères et sœurs (inclus par le règlement (UE)
 * 2024/1624 pour les PEP de premier rang). Les proches associés (point 11) ne sont pas couverts : Wikidata ne les
 * décrit pas.
 *
 * <p>Source PLUGIN_MANAGED : le plugin interroge lui-même le point SPARQL ({@link WikidataRcaDownloader}) et
 * dépose les réponses dans {@code rawDir} ; la conversion ne lit ensuite que ces fichiers. Paramètres :
 * <pre>{@code
 * {"endpoint": "https://query.wikidata.org/sparql", "yearsAfterEnd": "5", "country": "Q32",
 *  "extraPositions": "Q29639349", "excludedPositions": "Q13102207 Q60853309"}
 * }</pre>
 * {@code extraPositions} : fonctions retenues en plus de celles dont la juridiction est le pays (ex. bourgmestre) ;
 * {@code excludedPositions} : fonctions écartées (ex. conseiller communal, médiateur), hors liste PEP.
 *
 * <p>Pour chaque lien, trois entités FollowTheMoney : la PEP ({@code Person}, {@code role.pep}), le proche
 * ({@code Person}, {@code role.rca}) et le lien {@code Family}. Les identifiants reprennent le QID Wikidata ;
 * un proche lui-même PEP reçoit les deux catégories. Seules les PEP qui ont au moins un proche sont écrites.
 */
@Extension
public class LuPepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepRcaParser.class);

    static final String ID_PREFIX = "lu-pep-rca";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q32";
    static final String DEFAULT_EXTRA_POSITIONS = "Q29639349";             // bourgmestre (Luxembourg)
    static final String DEFAULT_EXCLUDED_POSITIONS = "Q13102207 Q60853309"; // conseiller communal, médiateur
    static final int DEFAULT_YEARS_AFTER_END = 5;

    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";

    /** Lien Wikidata → libellé du lien {@code Family.relationship}, du point de vue de la PEP. */
    private static final Map<String, String> RELATIONSHIPS = Map.of(
            "spouse", "conjoint",
            "child", "enfant",
            "father", "père",
            "mother", "mère",
            "sibling", "frère ou sœur");

    private static final String MALE = "Q6581097";
    private static final String FEMALE = "Q6581072";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        WikidataRcaDownloader.Query query = new WikidataRcaDownloader.Query(
                p.getOrDefault("country", DEFAULT_COUNTRY).trim(),
                LocalDate.now().minusYears(yearsAfterEnd(p)),
                list(p.getOrDefault("extraPositions", DEFAULT_EXTRA_POSITIONS)),
                list(p.getOrDefault("excludedPositions", DEFAULT_EXCLUDED_POSITIONS)));
        new WikidataRcaDownloader().download(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), query, ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        ParserResult result = dataset.toConversionResult();
        return new ParserResult(result.entityCount(), result.rejectedCount(), result.conflictCount(),
                result.rejectedByProperty(), report.message());
    }

    /** Test du parser : chaque proche gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema()) && entity.getValues("topics").contains(TOPIC_RCA)) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int peps, int pepsWithFamily, int relatives, int links, int unnamed) {

        String message() {
            String m = String.format("%d PEP luxembourgeois dans Wikidata, dont %d avec des proches connus : "
                    + "%d proches, %d liens familiaux.", peps, pepsWithFamily, relatives, links);
            return unnamed == 0 ? m : m + String.format(" %d proches sans nom dans Wikidata écartés.", unnamed);
        }
    }

    /**
     * Lit {@code peps.json} et {@code relatives.json} dans {@code rawDir} et écrit les entités. Séparé de
     * {@link #parse} pour être testé sans réseau.
     */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws Exception {
        JsonNode peps = json.readTree(Files.readString(rawDir.resolve(WikidataRcaDownloader.PEPS_FILE)));
        JsonNode relatives = json.readTree(Files.readString(rawDir.resolve(WikidataRcaDownloader.RELATIVES_FILE)));

        // PEP : QID → nom et fonctions
        Map<String, JsonNode> pepById = new HashMap<>();
        for (JsonNode b : peps.path("results").path("bindings")) {
            pepById.put(qid(text(b, "pep")), b);
        }

        Set<String> pepsWritten = new HashSet<>();
        Set<String> relativesWritten = new HashSet<>();
        // Une ligne par date de naissance : un proche qui en a deux dans Wikidata apparaît deux fois
        Set<String> links = new HashSet<>();
        int unnamed = 0;
        for (JsonNode b : relatives.path("results").path("bindings")) {
            String pepQid = qid(text(b, "pep"));
            String relativeQid = qid(text(b, "relative"));
            String relationship = RELATIONSHIPS.get(text(b, "relationship"));
            String relativeName = text(b, "relativeLabel");
            JsonNode pep = pepById.get(pepQid);
            if (pep == null || relationship == null || relativeQid.equals(pepQid)) {
                continue;
            }
            // Sans libellé, le service renvoie le QID : un nom inutilisable pour le criblage
            if (relativeName.isBlank() || relativeName.equals(relativeQid)) {
                unnamed++;
                continue;
            }

            FtmEntity pepPerson = person(dataset, pepQid, text(pep, "pepLabel"), TOPIC_PEP);
            for (String position : text(pep, "positions").split("\\s*\\|\\s*")) {
                if (!position.isBlank() && !position.matches("Q\\d+")) {
                    pepPerson.addValue("position", position);
                }
            }
            dataset.addEntity(pepPerson);
            pepsWritten.add(pepQid);

            FtmEntity relative = person(dataset, relativeQid, relativeName, TOPIC_RCA);
            String birthDate = birthDate(text(b, "relativeBirth"), text(b, "birthPrecision"));
            if (birthDate != null) {
                relative.addValue("birthDate", birthDate);
            }
            String gender = qid(text(b, "relativeGender"));
            if (MALE.equals(gender)) {
                relative.addValue("gender", "male");
            } else if (FEMALE.equals(gender)) {
                relative.addValue("gender", "female");
            }
            relative.addValue("summary", relationship.substring(0, 1).toUpperCase() + relationship.substring(1)
                    + " de " + text(pep, "pepLabel"));
            dataset.addEntity(relative);
            relativesWritten.add(relativeQid);

            FtmEntity family = dataset.newEntity("Family", pepQid, text(b, "relationship"), relativeQid);
            family.addValue("person", pepPerson.getId());
            family.addValue("relative", relative.getId());
            family.addValue("relationship", relationship);
            family.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + pepQid);
            dataset.addEntity(family);
            links.add(family.getId());
        }

        Report report = new Report(pepById.size(), pepsWritten.size(), relativesWritten.size(), links.size(), unnamed);
        log.info(report.message());
        return report;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String qid, String name, String topic) {
        FtmEntity person = dataset.newEntity("Person", qid);
        person.addValue("name", name);
        person.addValue("wikidataId", qid);
        person.addValue("topics", topic);
        person.addValue("sourceUrl", "https://www.wikidata.org/wiki/" + qid);
        return person;
    }

    /**
     * Date de naissance selon sa précision Wikidata : 11 = jour, 10 = mois, 9 = année. Une année seule est
     * stockée « 1926-01-01 » : on n'écrit que « 1926 ». Null si la précision est moindre (décennie…).
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

    private static String text(JsonNode binding, String column) {
        return binding.path(column).path("value").asText("");
    }

    private static String qid(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    static List<String> list(String value) {
        return value == null ? List.of()
                : Arrays.stream(value.split("[\\s,;]+")).filter(s -> !s.isBlank()).toList();
    }

    private static int yearsAfterEnd(Map<String, String> parameters) {
        try {
            return Integer.parseInt(parameters.getOrDefault("yearsAfterEnd", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_YEARS_AFTER_END;
        }
    }
}
