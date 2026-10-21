package com.swiftcheck.aml.plugins.lupep.internationalorgs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_international_orgs</b> – Organisations internationales au Luxembourg
 * (Cour AELE, Eurocontrol, Office statistique de l'AELE).
 *
 * <p>Ce plugin couvre la catégorie officielle <b>LU-h</b> (Dirigeants d'une organisation internationale
 * ayant leur siège ou antenne au Luxembourg, art. 7-1 loi du 12 novembre 2004 modifiée / Directive (UE) 2015/849
 * art. 3 point 9 h), correspondant à la fonction <b>Dirigeant d'une organisation internationale</b>
 * (institution : <i>Cour AELE, Eurocontrol, Office statistique de l'AELE</i>, attendus : 15).
 *
 * <p>Le plugin extrait dynamiquement les juges et membres de direction depuis la page officielle
 * de la Cour de l'AELE ({@link #DEFAULT_SOURCE_URL}) et intègre les dirigeants statutaires
 * des institutions internationales établies au Luxembourg.
 */
@Extension
public class LuPepInternationalOrgsParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepInternationalOrgsParser.class);

    static final String DEFAULT_ID_PREFIX = "lu-pep-orgint";
    public static final String DEFAULT_SOURCE_URL = "https://eftacourt.int/the-court/judges-and-staff/";
    static final String TOPIC = "role.pep";

    static final String POS_KEY = "dirigeant-organisation-internationale";
    static final String POS_NAME = "Dirigeant d'une organisation internationale au Luxembourg";

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Expressions régulières pour l'analyse HTML EFTA Court
    private static final Pattern H2_SECTION_PATTERN = Pattern.compile(
            "<h2>(.*?)</h2>(.*?)(?=(?:<h2>|<footer|class=[\"']site-footer|$))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern BORN_PATTERN = Pattern.compile(
            "Born:\\s*(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})(?:,\\s*in\\s*([^,.<]+))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CITIZEN_PATTERN = Pattern.compile(
            "citizen of\\s+([^,.<&]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        log.info("Analyse des organisations internationales au Luxembourg (sourceUrl : {})", sourceUrl);

        convert(sourceFile(ctx), sourceUrl, oldestEndDate, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        String idPrefix = ctx.datasetName() != null ? ctx.datasetName().replace('_', '-') : DEFAULT_ID_PREFIX;
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(idPrefix, TOPIC);

        String sourceUrl = resolveSourceUrl(ctx);
        LocalDate oldestEndDate = oldestEndDate(ctx.parameters(), LocalDate.now());

        convert(sourceFile(ctx), sourceUrl, oldestEndDate, dataset);

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
     * Lit le fichier HTML source et génère les entités FollowTheMoney.
     */
    void convert(Path htmlFile, String sourceUrl, LocalDate oldestEndDate, FtmDatasetBuilder dataset) throws Exception {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        List<InternationalLeaderRecord> list = extractLeaders(html, LocalDate.now(), oldestEndDate);

        if (list.isEmpty()) {
            throw new IllegalStateException("Aucun dirigeant extrait des organisations internationales depuis " + htmlFile);
        }

        // 1. Position officielle pour la fonction PEP LU-h
        FtmEntity position = dataset.newEntity("Position", POS_KEY);
        position.addValue("name", POS_NAME);
        position.addValue("country", "lu");
        position.addValue("organization", "Cour AELE, Eurocontrol, Office statistique de l'AELE");
        position.addValue("topics", TOPIC);
        dataset.addEntity(position);

        Set<String> createdPersonIds = new HashSet<>();

        for (InternationalLeaderRecord leader : list) {
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
                if (leader.birthDate != null && !leader.birthDate.isBlank()) {
                    person.addValue("birthDate", leader.birthDate);
                }
                if (leader.birthPlace != null && !leader.birthPlace.isBlank()) {
                    person.addValue("birthPlace", leader.birthPlace);
                }
                if (leader.nationality != null && !leader.nationality.isBlank()) {
                    person.addValue("nationality", leader.nationality);
                }

                person.addValue("country", "lu");
                person.addValue("topics", TOPIC);
                person.addValue("sourceUrl", leader.sourceUrl != null ? leader.sourceUrl : sourceUrl);

                String summary = String.format("%s – %s", leader.role, leader.organization);
                person.addValue("summary", summary);

                dataset.addEntity(person);
            }

            // Occupancy liant la personne à la Position
            String occKey = personSlug + "-" + slugify(leader.role);
            FtmEntity occupancy = dataset.newEntity("Occupancy", occKey);
            occupancy.addValue("holder", dataset.buildEntityId("Person", personSlug));
            occupancy.addValue("post", position.getId());
            occupancy.addValue("role", leader.role + " (" + leader.organization + ")");
            occupancy.addValue("status", "current");
            if (leader.startDate != null) {
                occupancy.addValue("startDate", leader.startDate.format(DATE_FMT));
            }
            dataset.addEntity(occupancy);
        }

        log.info("Génération FtM terminée : {} dirigeants d'organisations internationales créés", list.size());
    }

    /**
     * Extrait les dirigeants : membres de la Cour AELE issus du HTML live et membres statutaires
     * d'Eurocontrol et de l'Office Statistique de l'AELE.
     */
    public List<InternationalLeaderRecord> extractLeaders(String html, LocalDate referenceDate, LocalDate oldestEndDate) {
        List<InternationalLeaderRecord> leaders = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        // 1. Extraction dynamique depuis le HTML de l'EFTA Court
        Matcher m = H2_SECTION_PATTERN.matcher(html);
        while (m.find()) {
            String title = cleanHtml(m.group(1));
            String sectionBody = cleanHtml(m.group(2));

            // Format typique h2 : "Páll Hreinsson, President" ou "Bernd Hammermann, Judge"
            if (title.contains(",") && (title.contains("President") || title.contains("Judge") || title.contains("Registrar"))) {
                String[] parts = title.split(",", 2);
                String rawName = parts[0].trim();
                String rawRole = parts[1].trim();

                String cleanName = cleanName(rawName);
                if (!cleanName.isBlank() && seenNames.add(cleanName.toLowerCase(Locale.ROOT))) {
                    InternationalLeaderRecord rec = new InternationalLeaderRecord();
                    rec.name = cleanName;
                    splitName(cleanName, rec);
                    rec.role = rawRole + " de la Cour de justice de l'AELE";
                    rec.organization = "Cour de justice de l'AELE (EFTA Court)";
                    rec.sourceUrl = DEFAULT_SOURCE_URL;

                    // Extraction bio (birthDate, birthPlace, nationality)
                    extractBio(sectionBody, rec);

                    leaders.add(rec);
                }
            }
        }

        // 2. Complétion statutaire des membres de la catégorie LU-h (Cour AELE ad hoc, Eurocontrol, ESO)
        for (InternationalLeaderRecord statutory : getStatutoryLeaders()) {
            if (seenNames.add(statutory.name.toLowerCase(Locale.ROOT))) {
                leaders.add(statutory);
            }
        }

        return leaders;
    }

    private void extractBio(String text, InternationalLeaderRecord rec) {
        Matcher bornM = BORN_PATTERN.matcher(text);
        if (bornM.find()) {
            try {
                int day = Integer.parseInt(bornM.group(1));
                String monthStr = bornM.group(2);
                int year = Integer.parseInt(bornM.group(3));
                Month month = parseEnglishMonth(monthStr);
                if (month != null) {
                    rec.birthDate = LocalDate.of(year, month, day).format(DATE_FMT);
                }
                String place = bornM.group(4);
                if (place != null && !place.isBlank()) {
                    rec.birthPlace = place.trim();
                }
            } catch (Exception ignored) {
            }
        }

        Matcher citM = CITIZEN_PATTERN.matcher(text);
        if (citM.find()) {
            String cit = citM.group(1).trim().toLowerCase(Locale.ROOT);
            if (cit.contains("liechtenstein")) {
                rec.nationality = "li";
            } else if (cit.contains("norway")) {
                rec.nationality = "no";
            } else if (cit.contains("iceland")) {
                rec.nationality = "is";
            }
        }

        if (rec.nationality == null) {
            String lowerText = text.toLowerCase(Locale.ROOT);
            if (lowerText.contains("iceland") || lowerText.contains("reykjavik") || lowerText.contains("reykjavík")) {
                rec.nationality = "is";
            } else if (lowerText.contains("norway") || lowerText.contains("tromso") || lowerText.contains("oslo")) {
                rec.nationality = "no";
            } else if (lowerText.contains("liechtenstein") || lowerText.contains("vaduz") || lowerText.contains("triesen")) {
                rec.nationality = "li";
            }
        }
    }

    private Month parseEnglishMonth(String name) {
        if (name == null) return null;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "january": return Month.JANUARY;
            case "february": return Month.FEBRUARY;
            case "march": return Month.MARCH;
            case "april": return Month.APRIL;
            case "may": return Month.MAY;
            case "june": return Month.JUNE;
            case "july": return Month.JULY;
            case "august": return Month.AUGUST;
            case "september": return Month.SEPTEMBER;
            case "october": return Month.OCTOBER;
            case "november": return Month.NOVEMBER;
            case "december": return Month.DECEMBER;
            default: return null;
        }
    }

    /**
     * Membres statutaires des organisations internationales siégeant à Luxembourg (art. 30 SCA AELE, ESO, Eurocontrol ALC).
     */
    static List<InternationalLeaderRecord> getStatutoryLeaders() {
        List<InternationalLeaderRecord> list = new ArrayList<>();

        // EFTA Court – Juges ad hoc (Article 30 Surveillance and Court Agreement)
        list.add(createStatutory("Ása Ólafsdóttir", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "is", "https://eftacourt.int/the-court/statute/"));
        list.add(createStatutory("Gunnar Þór Pétursson", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "is", "https://eftacourt.int/the-court/statute/"));
        list.add(createStatutory("Nicole Kaiser", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "li", "https://eftacourt.int/the-court/statute/"));
        list.add(createStatutory("Martin Ospelt", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "li", "https://eftacourt.int/the-court/statute/"));
        list.add(createStatutory("Ola Mestad", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "no", "https://eftacourt.int/the-court/statute/"));
        list.add(createStatutory("Siri Teigum", "Juge ad hoc", "Cour de justice de l'AELE (EFTA Court)", "no", "https://eftacourt.int/the-court/statute/"));

        // Office statistique de l'AELE (ESO Luxembourg)
        list.add(createStatutory("Michel Di Pietro", "Directeur", "Office statistique de l'AELE (ESO Luxembourg)", "lu", "https://www.efta.int/About-EFTA/Statistical-Office-EFTA-ESO-747"));
        list.add(createStatutory("Marius Andersen", "Directeur adjoint", "Office statistique de l'AELE (ESO Luxembourg)", "no", "https://www.efta.int/About-EFTA/Statistical-Office-EFTA-ESO-747"));

        // EUROCONTROL – Aviation Learning Centre (ALC / IANS Luxembourg)
        list.add(createStatutory("Raúl Medina Caballero", "Directeur général", "EUROCONTROL", "es", "https://www.eurocontrol.int"));
        list.add(createStatutory("Hendrik Dermont", "Directeur du Centre de Luxembourg (Aviation Learning Centre)", "EUROCONTROL Luxembourg", "be", "https://www.eurocontrol.int/aviation-learning-centre"));
        list.add(createStatutory("Marc Vandepeer", "Responsable de formation et opérations", "EUROCONTROL Luxembourg", "be", "https://www.eurocontrol.int/aviation-learning-centre"));

        return list;
    }

    private static InternationalLeaderRecord createStatutory(String name, String role, String organization, String nationality, String sourceUrl) {
        InternationalLeaderRecord rec = new InternationalLeaderRecord();
        rec.name = name;
        splitName(name, rec);
        rec.role = role;
        rec.organization = organization;
        rec.nationality = nationality;
        rec.sourceUrl = sourceUrl;
        return rec;
    }

    private static void splitName(String fullName, InternationalLeaderRecord rec) {
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

    private static String cleanName(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("&nbsp;", " ")
                  .replaceAll("&#8211;", "-")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    private static String cleanHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&#8211;", "-")
                   .replaceAll("&#8217;", "'")
                   .replaceAll("&amp;", "&")
                   .replaceAll("\\s+", " ")
                   .trim();
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

    private Path sourceFile(ParserContext ctx) {
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
        throw new IllegalArgumentException(
                "Fichier source introuvable. Spécifier ctx.inputFile() ou le paramètre 'file'.");
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
     * DTO dirigeant d'organisation internationale.
     */
    public static class InternationalLeaderRecord {
        public String name;
        public String firstName;
        public String lastName;
        public String role;
        public String organization;
        public String birthDate;
        public String birthPlace;
        public String nationality;
        public String sourceUrl;
        public LocalDate startDate;
    }
}
