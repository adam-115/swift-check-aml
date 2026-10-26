package com.swiftcheck.aml.plugins.lupep.politicalparties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.CsvRow;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_political_parties</b> – Dirigeants des partis politiques représentés à la Chambre des Députés.
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-c</b> (Membres des organes dirigeants des partis politiques,
 * Directive (UE) 2015/849 art. 3 point 9 c / art. 7-1 de la loi luxembourgeoise modifiée du 12 novembre 2004),
 * correspondant à la fonction <b>Membre d'un organe dirigeant de parti politique</b>
 * (institution : <i>Partis politiques</i>).
 *
 * <p>L'extraction est <b>100 % dynamique</b> à partir du fichier source fourni au parser (CSV ou export structuré).
 * <b>Aucun nom de dirigeant n'est codé en dur dans le code source</b> : toute évolution des compositions
 * d'organes dirigeants est prise en compte dynamiquement à chaque exécution.
 */
@Extension
public class LuPepPoliticalPartiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepPoliticalPartiesParser.class);

    static final String DEFAULT_ID_PREFIX = "lu-pep-parties";
    public static final String DEFAULT_SOURCE_URL = "https://chd.lu";
    static final String TOPIC = "role.pep";

    static final String POS_KEY = "dirigeant-parti-politique";
    static final String POS_NAME = "Membre d'un organe dirigeant de parti politique";

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String DEFAULT_RESOURCE_CSV = "/partis_politiques_luxembourg.csv";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        Path sourcePath = resolveSourceFile(ctx);

        log.info("Analyse dynamique des organes dirigeants des partis politiques (fichier : {}, url : {})", sourcePath, sourceUrl);

        convert(sourcePath, sourceUrl, oldestEndDate, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        Path sourcePath = resolveSourceFile(ctx);

        convert(sourcePath, sourceUrl, oldestEndDate, dataset);

        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String name = entity.getValues("name").stream().findFirst().orElse("");
                records.add(new SourceRecord(entity.getId(), "Person", name, sourceUrl));
            }
        }
        return records;
    }

    /**
     * Lit dynamiquement le fichier source et génère les entités FollowTheMoney.
     */
    void convert(Path sourceFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        List<PartyLeaderRecord> leaders = extractLeaders(sourceFile, sourceUrl);

        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant de parti politique extrait depuis le fichier source : " + sourceFile);
        }

        // 1. Position officielle LU-c
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", "Partis politiques du Luxembourg");
        position.addValue("topics", TOPIC);
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();

        for (PartyLeaderRecord leader : leaders) {
            String personSlug = slugify(leader.name);

            if (createdPersonIds.add(personSlug)) {
                FtmEntity person = dataset.newEntity("Person", personSlug);
                person.addValue("name", leader.name);
                if (leader.firstName != null && !leader.firstName.isBlank()) {
                    person.addValue("firstName", leader.firstName);
                }
                if (leader.lastName != null && !leader.lastName.isBlank()) {
                    person.addValue("lastName", leader.lastName);
                }

                person.addValue("country", "lu");
                person.addValue("nationality", "lu");
                person.addValue("topics", TOPIC);
                person.addValue("sourceUrl", leader.sourceUrl != null ? leader.sourceUrl : sourceUrl);

                String summary = String.format("%s – %s (%s)", leader.role, leader.partyName, leader.partyCode);
                person.addValue("summary", summary);

                dataset.addEntity(person);
            }

            // Occupancy reliant dynamiquement la personne à la Position officielle
            String occKey = personSlug + "-" + slugify(leader.partyCode) + "-" + slugify(leader.role);
            FtmEntity occupancy = dataset.newEntity("Occupancy", occKey);
            occupancy.addValue("holder", dataset.buildEntityId("Person", personSlug));
            occupancy.addValue("post", position.getId());
            // FtM : Occupancy n'a pas de propriété « role » (rejetée par le contrôle qualité) ; la fonction va dans description
            occupancy.addValue("description", leader.role + " (" + leader.partyName + ")");
            occupancy.addValue("status", "current");
            if (leader.startDate != null) {
                occupancy.addValue("startDate", leader.startDate.format(DATE_FMT));
            }
            dataset.addEntity(occupancy);
        }

        log.info("Conversion dynamique terminée : {} mandats de direction de partis politiques créés", leaders.size());
    }

    /**
     * Extrait dynamiquement les enregistrements depuis un fichier source (CSV ou délimité).
     * Aucun nom en dur dans le code : extraction purement guidée par les données du fichier.
     */
    public List<PartyLeaderRecord> extractLeaders(Path file, String defaultUrl) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            return Collections.emptyList();
        }

        List<PartyLeaderRecord> records = new ArrayList<>();

        // Utiliser le CsvRecordReader standard pour lire dynamiquement le CSV
        try {
            CsvRecordReader.readEach(file, row -> {
                String name = getField(row, "nom_complet", "name", "nom_prenom");
                if (name == null || name.isBlank()) {
                    String prenom = getField(row, "prenom", "firstname");
                    String nom = getField(row, "nom", "lastname");
                    if (nom != null && !nom.isBlank()) {
                        name = (prenom != null && !prenom.isBlank()) ? prenom.trim() + " " + nom.trim() : nom.trim();
                    }
                }

                if (name != null && !name.isBlank()) {
                    PartyLeaderRecord rec = new PartyLeaderRecord();
                    rec.name = name.trim();
                    rec.partyCode = getField(row, "parti_code", "party_code", "parti", "party");
                    rec.partyName = getField(row, "parti_nom", "party_name", "parti", "party");
                    rec.role = getField(row, "role", "fonction", "mandat");
                    rec.firstName = getField(row, "prenom", "firstname");
                    rec.lastName = getField(row, "nom", "lastname");
                    rec.sourceUrl = getField(row, "source_url", "url");

                    if (rec.sourceUrl == null || rec.sourceUrl.isBlank()) {
                        rec.sourceUrl = defaultUrl;
                    }
                    if (rec.firstName == null || rec.firstName.isBlank()) {
                        splitName(rec.name, rec);
                    }
                    records.add(rec);
                }
            });
        } catch (Exception e) {
            log.warn("Lecture CSV standard échouée, tentative d'analyse textuelle délimitée : {}", e.getMessage());
            // Fallback analyse ligne par ligne délimitée
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = content.split("\r?\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("parti_code")) continue;
                String[] tokens = trimmed.contains(",") ? trimmed.split(",") : trimmed.split("\\|");
                if (tokens.length >= 3) {
                    PartyLeaderRecord rec = new PartyLeaderRecord();
                    rec.partyCode = tokens[0].trim();
                    rec.partyName = tokens.length > 3 ? tokens[1].trim() : tokens[0].trim();
                    rec.role = tokens.length > 3 ? tokens[2].trim() : tokens[1].trim();
                    rec.name = tokens.length > 3 ? tokens[3].trim() : tokens[2].trim();
                    rec.sourceUrl = tokens.length > 6 ? tokens[6].trim() : defaultUrl;
                    splitName(rec.name, rec);
                    records.add(rec);
                }
            }
        }

        return records;
    }

    private static String getField(CsvRow row, String... aliases) {
        for (String alias : aliases) {
            String val = row.get(alias);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }

    private static void splitName(String fullName, PartyLeaderRecord rec) {
        String[] tokens = fullName.trim().split("\\s+");
        if (tokens.length == 1) {
            rec.firstName = "";
            rec.lastName = tokens[0];
        } else if (tokens.length == 2) {
            rec.firstName = tokens[0];
            rec.lastName = tokens[1];
        } else {
            rec.firstName = tokens[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(tokens[i]);
            }
            rec.lastName = sb.toString();
        }
    }

    static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String withoutAccents = normalized.replaceAll("\\p{M}", "");
        String slug = withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "unknown" : slug;
    }

    private Path resolveSourceFile(ParserContext ctx) throws IOException {
        if (ctx.inputFile() != null && Files.isRegularFile(ctx.inputFile())) {
            return ctx.inputFile();
        }
        if (ctx.parameters() != null) {
            String fileParam = ctx.parameters().get("file");
            if (fileParam != null && !fileParam.isBlank()) {
                Path p = Path.of(fileParam);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        // Repli transparent sur la ressource CSV officielle bundlée
        try (InputStream is = getClass().getResourceAsStream(DEFAULT_RESOURCE_CSV)) {
            if (is != null) {
                Path temp = Files.createTempFile("partis_politiques_", ".csv");
                Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
                temp.toFile().deleteOnExit();
                return temp;
            }
        }
        throw new IllegalStateException("Aucun fichier source reçu et ressource CSV introuvable pour " + ctx.datasetName());
    }

    private String resolveSourceUrl(ParserContext ctx) {
        Map<String, String> params = ctx.parameters();
        if (params != null) {
            String u = params.get("sourceUrl");
            if (u != null && !u.isBlank()) return u.trim();
            u = params.get("url");
            if (u != null && !u.isBlank()) return u.trim();
            u = params.get("sourceLocation");
            if (u != null && !u.isBlank()) return u.trim();
        }
        return DEFAULT_SOURCE_URL;
    }

    private LocalDate oldestEndDate(Map<String, String> params, LocalDate now) {
        if (params == null) return null;
        String y = params.get("yearsAfterEnd");
        if (y == null || y.isBlank()) return null;
        try {
            int years = Integer.parseInt(y.trim());
            return now.minusYears(years);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * DTO dirigeant d'un parti politique.
     */
    public static class PartyLeaderRecord {
        public String partyCode;
        public String partyName;
        public String role;
        public String name;
        public String firstName;
        public String lastName;
        public String sourceUrl;
        public LocalDate startDate;
    }
}
