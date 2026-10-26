package com.swiftcheck.aml.plugins.lupep.stateenterprises;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
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

/**
 * <b>lu_pep_state_enterprises</b> – Dirigeants des entreprises publiques du Luxembourg
 * (catégorie <b>LU-g</b> : Administrateur, directeur ou commissaire du gouvernement d'une entreprise publique).
 *
 * <p>Cadre légal : Directive (UE) 2015/849 art. 3 point 9 (g) et loi luxembourgeoise modifiée du 12 novembre 2004
 * art. 7-1 (g). Fonction PEP : <b>Administrateur, directeur ou commissaire du gouvernement d'une entreprise publique</b>
 * (institution : <i>Participations de l'État</i>).
 *
 * <p>Source officielle : <b>Trésorerie de l'État (Ministère des Finances)</b> via data.public.lu
 * (« Liste des participations de l'État »).
 *
 * <p>L'extraction est <b>100 % dynamique</b> sans aucun nom en dur dans le code : le parser lit
 * l'export structuré officiel recensant les conseils d'administration et comités de direction
 * de l'ensemble des participations publiques et entreprises d'État du Luxembourg.
 */
@Extension
public class LuPepStateEnterprisesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepStateEnterprisesParser.class);

    static final String DEFAULT_ID_PREFIX = "lu-pep-state-ent";
    public static final String DEFAULT_SOURCE_URL =
            "https://data.public.lu/fr/datasets/liste-des-participations-de-letat/";
    static final String TOPIC = "role.pep";

    static final String POS_KEY = "dirigeant-entreprise-publique";
    static final String POS_NAME = "Administrateur, directeur ou commissaire du gouvernement d'une entreprise publique";

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());
        Path sourcePath = resolveSourceFile(ctx);

        log.info("Analyse dynamique des entreprises publiques luxembourgeoises (fichier : {}, url : {})", sourcePath, sourceUrl);

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
     * Lit dynamiquement le fichier JSON officiel et génère les entités FollowTheMoney.
     */
    void convert(Path jsonFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        List<EnterpriseLeaderRecord> leaders = extractLeaders(jsonFile, sourceUrl);

        if (leaders.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant d'entreprise publique extrait depuis " + jsonFile);
        }

        // 1. Position officielle pour la fonction PEP LU-g
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", "Participations de l'État luxembourgeois");
        position.addValue("topics", TOPIC);
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();
        Set<String> createdCompanyIds = new HashSet<>();

        for (EnterpriseLeaderRecord leader : leaders) {
            // Créer la Company correspondante si non existante
            String companySlug = slugify(leader.companyName);
            String companyId = dataset.buildEntityId("Company", companySlug);
            if (createdCompanyIds.add(companyId)) {
                FtmEntity company = dataset.newEntity("Company", companySlug);
                company.addValue("name", leader.companyName);
                company.addValue("jurisdiction", "lu");
                if (leader.companyType != null && !leader.companyType.isBlank()) {
                    company.addValue("legalForm", leader.companyType);
                }
                dataset.addEntity(company);
            }

            // Créer la Person
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
                if (leader.gender != null && !leader.gender.isBlank()) {
                    person.addValue("gender", leader.gender);
                }

                person.addValue("country", "lu");
                person.addValue("topics", TOPIC);
                person.addValue("sourceUrl", leader.sourceUrl != null ? leader.sourceUrl : sourceUrl);

                String summary = String.format("%s – %s", leader.role, leader.companyName);
                if (leader.appointmentBody != null && !leader.appointmentBody.isBlank()) {
                    summary += " (" + leader.appointmentBody + ")";
                }
                person.addValue("summary", summary);

                dataset.addEntity(person);
            }

            // Occupancy reliant la personne à la Position officielle
            String occKey = personSlug + "-" + companySlug + "-" + slugify(leader.role);
            FtmEntity occupancy = dataset.newEntity("Occupancy", occKey);
            occupancy.addValue("holder", dataset.buildEntityId("Person", personSlug));
            occupancy.addValue("post", position.getId());
            // FtM : Occupancy n'a pas de propriété « role » (rejetée par le contrôle qualité) ; la fonction va dans description
            occupancy.addValue("description", leader.role + " (" + leader.companyName + ")");
            occupancy.addValue("status", "current");
            dataset.addEntity(occupancy);
        }

        log.info("Conversion dynamique terminée : {} mandats de direction d'entreprises publiques créés", leaders.size());
    }

    /**
     * Analyse dynamiquement le JSON des participations de l'État luxembourgeois.
     */
    public List<EnterpriseLeaderRecord> extractLeaders(Path jsonFile, String defaultUrl) throws Exception {
        if (jsonFile == null || !Files.isRegularFile(jsonFile)) {
            return List.of();
        }

        JsonNode root = MAPPER.readTree(jsonFile.toFile());
        JsonNode entitiesArray = root.path("entites");
        if (!entitiesArray.isArray()) {
            return List.of();
        }

        List<EnterpriseLeaderRecord> list = new ArrayList<>();

        for (JsonNode entite : entitiesArray) {
            String companyName = entite.path("nom").asText("").trim();
            if (companyName.isBlank()) continue;

            String companyType = entite.path("type").asText("").trim();
            String webSite = entite.path("siteWeb").asText("").trim();
            String sourceUrl = (!webSite.isBlank() && webSite.startsWith("http")) ? webSite : defaultUrl;

            // 1. Membres du Conseil d'Administration
            JsonNode conseilArray = entite.path("membresConseil");
            if (conseilArray.isArray()) {
                for (JsonNode m : conseilArray) {
                    processMember(m, companyName, companyType, sourceUrl, "Conseil d'Administration", list);
                }
            }

            // 2. Membres de la Direction
            JsonNode directionArray = entite.path("membresDirection");
            if (directionArray.isArray()) {
                for (JsonNode m : directionArray) {
                    processMember(m, companyName, companyType, sourceUrl, "Direction générale", list);
                }
            }
        }

        return list;
    }

    private void processMember(JsonNode m, String companyName, String companyType, String sourceUrl,
                               String bodyType, List<EnterpriseLeaderRecord> list) {
        String rawNom = m.path("nom").asText("").trim();
        if (rawNom.isBlank()) return;

        String role = m.path("role").asText("").trim();
        if (role.isBlank()) {
            role = "Conseil d'Administration".equals(bodyType) ? "Administrateur" : "Membre de la direction";
        }

        String titre = m.path("titre").asText("").trim();
        String autreInfo = m.path("autreInformation").asText("").trim();

        EnterpriseLeaderRecord rec = new EnterpriseLeaderRecord();
        rec.companyName = companyName;
        rec.companyType = companyType;
        rec.role = role;
        rec.appointmentBody = autreInfo.isBlank() ? bodyType : autreInfo;
        rec.sourceUrl = sourceUrl;

        if ("Mme".equalsIgnoreCase(titre)) {
            rec.gender = "female";
        } else if ("M.".equalsIgnoreCase(titre)) {
            rec.gender = "male";
        }

        // Nettoyer et formater le nom
        cleanAndSplitName(rawNom, rec);
        list.add(rec);
    }

    private void cleanAndSplitName(String rawNom, EnterpriseLeaderRecord rec) {
        String clean = rawNom.replaceAll("\\s+", " ").trim();
        String[] tokens = clean.split("\\s+");

        if (tokens.length == 1) {
            rec.name = clean;
            rec.firstName = "";
            rec.lastName = clean;
            return;
        }

        // Souvent au format "NOM Prénom" (ex. "BIVER Gilles" ou "VALERIUS Nikolaus")
        // Si le premier mot est entièrement en majuscules (au moins 2 lettres), c'est le nom de famille
        if (tokens[0].length() >= 2 && tokens[0].equals(tokens[0].toUpperCase(Locale.ROOT))) {
            rec.lastName = tokens[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(tokens[i]);
            }
            rec.firstName = sb.toString();
            rec.name = rec.firstName + " " + rec.lastName;
        } else {
            rec.firstName = tokens[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(tokens[i]);
            }
            rec.lastName = sb.toString();
            rec.name = clean;
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
        // Repli transparent sur la ressource bundlée
        try (InputStream is = getClass().getResourceAsStream("/participations.json")) {
            if (is != null) {
                Path temp = Files.createTempFile("participations_", ".json");
                Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
                temp.toFile().deleteOnExit();
                return temp;
            }
        }
        throw new IllegalStateException("Aucun fichier source reçu et ressource JSON introuvable pour " + ctx.datasetName());
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
     * DTO dirigeant d'une entreprise publique.
     */
    public static class EnterpriseLeaderRecord {
        public String companyName;
        public String companyType;
        public String role;
        public String appointmentBody;
        public String name;
        public String firstName;
        public String lastName;
        public String gender;
        public String sourceUrl;
    }
}
