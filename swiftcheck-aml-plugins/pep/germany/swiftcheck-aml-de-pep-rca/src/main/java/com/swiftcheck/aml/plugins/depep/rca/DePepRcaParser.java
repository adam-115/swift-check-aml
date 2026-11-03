package com.swiftcheck.aml.plugins.depep.rca;

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
import com.swiftcheck.aml.sdk.WikidataImages;

/**
 * <b>de_pep_rca</b> – Membres de la famille et proches associés des PEP allemandes, d'après Wikidata.
 *
 * <p>Ce plugin couvre la catégorie <b>DE-RCA</b> au sens du GwG § 1 (13) et (14) : conjoint ou partenaire,
 * enfants et leurs conjoints, parents (sans la fratrie).
 *
 * <p>Source PLUGIN_MANAGED : le plugin interroge lui-même le point SPARQL ({@link WikidataRcaDownloader}) et
 * dépose les réponses dans {@code rawDir} ; la conversion ne lit ensuite que ces fichiers.
 */
@Extension
public class DePepRcaParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepRcaParser.class);

    static final String ID_PREFIX = "de-pep-rca";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String DEFAULT_COUNTRY = "Q183";
    static final String DEFAULT_EXTRA_POSITIONS = "";
    static final String DEFAULT_EXCLUDED_POSITIONS = "";

    /**
     * Fonctions officielles allemandes dans Wikidata :
     * Q25223: Bundespräsident, Q4970: Bundeskanzler, Q310662: Vizekanzler,
     * Q1939555: Mitglied des Deutschen Bundestages, Q19368417: Mitglied des Bundesrates,
     * Q17478052: Richter des Bundesverfassungsgerichts, Q17488092: Präsident der Deutschen Bundesbank.
     */
    static final String DEFAULT_POSITIONS = "Q25223 Q4970 Q310662 Q1939555 Q19368417 Q17478052 Q17488092";

    /** Classes de fonctions en Allemagne : Q83307 (ministre), Q19368688 (ministre-président de Land). */
    static final String DEFAULT_POSITION_CLASSES = "Q83307 Q19368688";
    static final int DEFAULT_YEARS_AFTER_END = 1;

    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";

    private static final Map<String, String> RELATIONSHIPS = Map.of(
            "spouse", "conjoint",
            "partner", "concubin ou partenaire",
            "child", "enfant",
            "childSpouse", "conjoint d'un enfant",
            "father", "père",
            "mother", "mère");

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
                list(p.getOrDefault("excludedPositions", DEFAULT_EXCLUDED_POSITIONS)),
                list(p.getOrDefault("positions", DEFAULT_POSITIONS)),
                list(p.getOrDefault("positionClasses", DEFAULT_POSITION_CLASSES)));
        new WikidataRcaDownloader().download(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), query, ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), dataset);
        String photos = WikidataImages.addPhotos(dataset, WikidataImages.QID, WikidataImages.personIdSuffixes(dataset),
                WikidataImages.httpFetcher(p.getOrDefault("endpoint", DEFAULT_ENDPOINT).trim(), WikidataImages.DEFAULT_USER_AGENT),
                ctx.rawDir().resolve(WikidataImages.PHOTOS_FILE));
        dataset.writeNdjsonFile(ctx.outputFile());
        ParserResult result = dataset.toConversionResult();
        return result.withMessage((report.message() + " " + photos).trim());
    }

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

    record Report(int peps, int pepsWithFamily, int relatives, int links, int unnamed) {

        String message() {
            String m = String.format("%d PEP allemandes dans Wikidata, dont %d avec des proches connus : "
                    + "%d proches, %d liens familiaux.", peps, pepsWithFamily, relatives, links);
            return unnamed == 0 ? m : m + String.format(" %d proches sans nom dans Wikidata écartés.", unnamed);
        }
    }

    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws Exception {
        JsonNode peps = json.readTree(Files.readString(rawDir.resolve(WikidataRcaDownloader.PEPS_FILE)));
        JsonNode relatives = json.readTree(Files.readString(rawDir.resolve(WikidataRcaDownloader.RELATIVES_FILE)));

        Map<String, JsonNode> pepById = new HashMap<>();
        for (JsonNode b : peps.path("results").path("bindings")) {
            pepById.put(qid(text(b, "pep")), b);
        }

        Set<String> pepsWritten = new HashSet<>();
        Set<String> relativesWritten = new HashSet<>();
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

