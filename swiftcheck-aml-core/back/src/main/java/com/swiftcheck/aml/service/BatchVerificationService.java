package com.swiftcheck.aml.service;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.dto.AmlMatchDTO;
import com.swiftcheck.aml.dto.AmlVerificationRequestDTO;
import com.swiftcheck.aml.dto.AmlVerificationResponseDTO;
import com.swiftcheck.aml.dto.batch.BatchEntityDTO;
import com.swiftcheck.aml.dto.batch.BatchParseResultDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRequestDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationResultDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRowDTO;
import com.swiftcheck.aml.dto.batch.BatchVerificationRowDTO.Status;

import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

/**
 * Vérification par lot : lecture d'un fichier CSV (une entité par ligne), criblage de chaque entité
 * avec {@link AmlVerificationService#verifyClient} puis rapport de synthèse en PDF.
 */
@Service
@Slf4j
public class BatchVerificationService {

    /** Identifiant des alertes pays ajoutées par {@code checkFatfCountryCompliance} : ce ne sont pas des personnes listées. */
    private static final String FATF_GEO_PREFIX = "fatf-geo-";

    /** Noms de colonnes acceptés (normalisés : minuscules, sans accents ni ponctuation) → champ. */
    private static final Map<String, String> COLUMN_ALIASES = new HashMap<>();

    static {
        alias("lastName", "nom", "lastname", "surname", "nomdefamille", "familyname", "nomdenaissance");
        alias("firstName", "prenom", "prenoms", "firstname", "givenname", "forename");
        alias("name", "nomcomplet", "fullname", "name", "raisonsociale", "denomination", "entite", "entity");
        alias("nationality", "nationalite", "nationality", "citizenship", "citoyennete");
        alias("birthDate", "datedenaissance", "datenaissance", "naissance", "birthdate", "dateofbirth", "dob", "nele", "nea");
        alias("birthPlace", "lieudenaissance", "lieunaissance", "birthplace", "placeofbirth");
        alias("country", "pays", "country", "paysderesidence", "residence", "juridiction", "jurisdiction");
        alias("schema", "type", "schema", "typeentite", "typedentite", "entitytype");
        alias("reference", "reference", "ref", "id", "identifiant", "idclient", "clientid", "numeroclient", "customerid");
    }

    private static void alias(String field, String... names) {
        for (String n : names) COLUMN_ALIASES.put(n, field);
    }

    /** Résolution stricte : « 31/02/1990 » est refusée au lieu de devenir le 28 février. */
    private static final List<DateTimeFormatter> DATE_FORMATS = java.util.stream.Stream.of(
            "d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "uuuu-M-d", "uuuu/M/d")
            .map(p -> DateTimeFormatter.ofPattern(p).withResolverStyle(java.time.format.ResolverStyle.STRICT))
            .toList();

    /** Noms de pays (français et anglais, normalisés) → code ISO à deux lettres en minuscules. */
    private static final Map<String, String> COUNTRY_NAMES = new HashMap<>();

    static {
        for (String code : Locale.getISOCountries()) {
            Locale loc = new Locale("", code);
            COUNTRY_NAMES.put(normalize(loc.getDisplayCountry(Locale.FRENCH)), code.toLowerCase());
            COUNTRY_NAMES.put(normalize(loc.getDisplayCountry(Locale.ENGLISH)), code.toLowerCase());
            try {
                COUNTRY_NAMES.put(normalize(loc.getISO3Country()), code.toLowerCase());
            } catch (Exception ignored) {
                // Pas de code à trois lettres pour ce pays.
            }
        }
        // Gentilés les plus courants, souvent saisis comme nationalité.
        Map<String, String> demonyms = Map.ofEntries(
                Map.entry("francais", "fr"), Map.entry("francaise", "fr"), Map.entry("french", "fr"),
                Map.entry("luxembourgeois", "lu"), Map.entry("luxembourgeoise", "lu"), Map.entry("luxembourgish", "lu"),
                Map.entry("allemand", "de"), Map.entry("allemande", "de"), Map.entry("german", "de"),
                Map.entry("belge", "be"), Map.entry("belgian", "be"),
                Map.entry("italien", "it"), Map.entry("italienne", "it"), Map.entry("italian", "it"),
                Map.entry("espagnol", "es"), Map.entry("espagnole", "es"), Map.entry("spanish", "es"),
                Map.entry("portugais", "pt"), Map.entry("portugaise", "pt"), Map.entry("portuguese", "pt"),
                Map.entry("suisse", "ch"), Map.entry("swiss", "ch"),
                Map.entry("britannique", "gb"), Map.entry("british", "gb"), Map.entry("anglais", "gb"),
                Map.entry("americain", "us"), Map.entry("americaine", "us"), Map.entry("american", "us"),
                Map.entry("russe", "ru"), Map.entry("russian", "ru"),
                Map.entry("marocain", "ma"), Map.entry("marocaine", "ma"), Map.entry("moroccan", "ma"),
                Map.entry("algerien", "dz"), Map.entry("algerienne", "dz"), Map.entry("algerian", "dz"),
                Map.entry("tunisien", "tn"), Map.entry("tunisienne", "tn"), Map.entry("tunisian", "tn"),
                Map.entry("iranien", "ir"), Map.entry("iranienne", "ir"), Map.entry("iranian", "ir"),
                Map.entry("chinois", "cn"), Map.entry("chinoise", "cn"), Map.entry("chinese", "cn"),
                Map.entry("ukrainien", "ua"), Map.entry("ukrainienne", "ua"), Map.entry("ukrainian", "ua"),
                Map.entry("neerlandais", "nl"), Map.entry("neerlandaise", "nl"), Map.entry("dutch", "nl"),
                Map.entry("libanais", "lb"), Map.entry("libanaise", "lb"), Map.entry("lebanese", "lb"));
        COUNTRY_NAMES.putAll(demonyms);
        COUNTRY_NAMES.put("uk", "gb");
        COUNTRY_NAMES.put("usa", "us");
    }

    private final AmlVerificationService verificationService;

    @Value("${BATCH_VERIFICATION_MAX_ROWS:1000}")
    private int maxRows = 1000;

    @Value("${BATCH_VERIFICATION_THREADS:4}")
    private int threads = 4;

    public BatchVerificationService(AmlVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    // ------------------------------------------------------------------ lecture du CSV

    public BatchParseResultDTO parseCsv(String fileName, byte[] content) {
        BatchParseResultDTO result = new BatchParseResultDTO();
        result.setFileName(fileName);

        String text = decode(content);
        List<String> lines = splitLines(text);
        // Lignes vides en tête ignorées.
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide.");
        }

        char separator = detectSeparator(lines.get(0));
        result.setSeparator(separator == '\t' ? "tabulation" : String.valueOf(separator));

        List<String> header = parseLine(lines.get(0), separator);
        Map<Integer, String> columnFields = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String raw = header.get(i).trim();
            String field = COLUMN_ALIASES.get(normalize(raw));
            if (field != null && !columnFields.containsValue(field)) {
                columnFields.put(i, field);
                result.getRecognizedColumns().add(raw + " → " + field);
            } else if (!raw.isEmpty()) {
                result.getIgnoredColumns().add(raw);
            }
        }
        boolean hasName = columnFields.containsValue("name") || columnFields.containsValue("lastName")
                || columnFields.containsValue("firstName");
        if (!hasName) {
            throw new IllegalArgumentException("Aucune colonne de nom reconnue dans l'en-tête (attendu : « nom », « prenom » "
                    + "ou « nom_complet »). En-tête lu : " + String.join(" | ", header));
        }

        for (int l = 1; l < lines.size(); l++) {
            String line = lines.get(l);
            if (line.isBlank()) continue;
            if (result.getEntities().size() >= maxRows) {
                result.getWarnings().add("Le fichier dépasse la limite de " + maxRows
                        + " lignes : les lignes suivantes (à partir de la ligne " + (l + 1) + ") sont ignorées.");
                break;
            }
            List<String> cells = parseLine(line, separator);
            if (cells.stream().allMatch(String::isBlank)) continue;

            Map<String, String> values = new HashMap<>();
            columnFields.forEach((idx, field) -> {
                if (idx < cells.size()) {
                    String v = cells.get(idx).trim();
                    if (!v.isEmpty()) values.put(field, v);
                }
            });
            BatchEntityDTO entity = toEntity(l + 1, values);
            result.getEntities().add(entity);
            if (entity.isValid()) result.setValidCount(result.getValidCount() + 1);
            else result.setInvalidCount(result.getInvalidCount() + 1);
        }

        if (result.getEntities().isEmpty()) {
            throw new IllegalArgumentException("Le fichier ne contient aucune ligne de données après l'en-tête.");
        }
        return result;
    }

    private BatchEntityDTO toEntity(int lineNumber, Map<String, String> v) {
        BatchEntityDTO e = new BatchEntityDTO();
        e.setLineNumber(lineNumber);
        e.setReference(v.get("reference"));
        e.setSchema(normalizeSchema(v.get("schema"), e));
        e.setFirstName(v.get("firstName"));
        e.setLastName(v.get("lastName"));

        String name = v.get("name");
        if (name == null) {
            name = String.join(" ", nonNull(v.get("firstName"), v.get("lastName"))).trim();
        }
        e.setName(name.isEmpty() ? null : name);
        if (e.getName() == null) {
            e.setValid(false);
            e.getWarnings().add("Nom absent : la ligne ne peut pas être vérifiée.");
        }

        e.setNationality(normalizeCountry(v.get("nationality"), "nationalité", e));
        e.setCountry(normalizeCountry(v.get("country"), "pays", e));
        e.setBirthPlace(v.get("birthPlace"));
        if (v.get("birthDate") != null) {
            String iso = normalizeDate(v.get("birthDate"));
            if (iso == null) {
                e.getWarnings().add("Date de naissance illisible (« " + v.get("birthDate") + " ») : ignorée.");
            } else {
                e.setBirthDate(iso);
            }
        }
        return e;
    }

    private static List<String> nonNull(String... values) {
        List<String> out = new ArrayList<>();
        for (String s : values) if (s != null && !s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String normalizeSchema(String raw, BatchEntityDTO e) {
        if (raw == null) return "Person";
        switch (normalize(raw)) {
            case "person": case "personne": case "pp": case "personnephysique": case "individu": case "individual":
                return "Person";
            case "organization": case "organisation": case "pm": case "personnemorale": case "association":
                return "Organization";
            case "company": case "societe": case "entreprise":
                return "Company";
            case "legalentity": return "LegalEntity";
            case "vessel": case "navire": return "Vessel";
            case "airplane": case "avion": case "aeronef": return "Airplane";
            default:
                e.getWarnings().add("Type d'entité inconnu (« " + raw + " ») : traité comme une personne.");
                return "Person";
        }
    }

    private static String normalizeCountry(String raw, String label, BatchEntityDTO e) {
        if (raw == null) return null;
        String n = normalize(raw);
        if (n.length() == 2) return n;
        String code = COUNTRY_NAMES.get(n);
        if (code != null) return code;
        e.getWarnings().add("Pays non reconnu pour la " + label + " (« " + raw + " ») : transmis tel quel.");
        return raw.trim();
    }

    /** Date → aaaa-mm-jj ; une année seule (ou aaaa-mm) est gardée telle quelle. {@code null} si illisible. */
    static String normalizeDate(String raw) {
        String s = raw.trim();
        // Horodatage Excel « 1970-01-31 00:00:00 » ou ISO avec heure.
        if (s.length() > 10 && s.matches("\\d{4}-\\d{2}-\\d{2}[T ].*")) s = s.substring(0, 10);
        if (s.matches("\\d{4}")) return s;
        if (s.matches("\\d{4}-\\d{2}")) return s;
        if (s.matches("\\d{1,2}/\\d{4}")) {
            String[] p = s.split("/");
            return p[1] + "-" + (p[0].length() == 1 ? "0" + p[0] : p[0]);
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate d = LocalDate.parse(s, f);
                if (d.getYear() < 1850 || d.isAfter(LocalDate.now())) return null;
                return d.toString();
            } catch (DateTimeParseException ignored) {
                // Format suivant.
            }
        }
        return null;
    }

    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]", "");
    }

    /** UTF-8 (avec ou sans BOM) ; à défaut Windows-1252, l'encodage d'Excel en français. */
    private static String decode(byte[] content) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(content, Charset.forName("windows-1252"));
        }
    }

    /** Découpe en lignes en respectant les retours à la ligne entre guillemets. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            if (!inQuotes && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                lines.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    private static char detectSeparator(String headerLine) {
        char best = ';';
        long bestCount = -1;
        for (char c : new char[] { ';', ',', '\t', '|' }) {
            long count = headerLine.chars().filter(ch -> ch == c).count();
            if (count > bestCount) {
                best = c;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<String> parseLine(String line, char sep) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == sep) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    // ------------------------------------------------------------------ vérification

    public BatchVerificationResultDTO verify(BatchVerificationRequestDTO request) {
        List<BatchEntityDTO> entities = request.getEntities() == null ? List.of() : request.getEntities();
        if (entities.isEmpty()) {
            throw new IllegalArgumentException("Aucune entité à vérifier.");
        }
        if (entities.size() > maxRows) {
            throw new IllegalArgumentException("Trop d'entités : " + entities.size() + " (limite " + maxRows + ").");
        }
        String dataset = request.getDataset() == null || request.getDataset().isBlank() ? "default" : request.getDataset();

        BatchVerificationResultDTO result = new BatchVerificationResultDTO();
        result.setFileName(request.getFileName());
        result.setDataset(dataset);
        result.setStartedAt(Instant.now().toString());
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, entities.size())));
        try {
            List<Future<BatchVerificationRowDTO>> futures = new ArrayList<>();
            for (BatchEntityDTO e : entities) {
                futures.add(pool.submit(() -> verifyOne(e, dataset)));
            }
            for (int i = 0; i < futures.size(); i++) {
                BatchVerificationRowDTO row;
                try {
                    row = futures.get(i).get();
                } catch (Exception ex) {
                    row = errorRow(entities.get(i), ex.getMessage());
                }
                result.getRows().add(row);
            }
        } finally {
            pool.shutdownNow();
        }

        for (BatchVerificationRowDTO row : result.getRows()) {
            switch (row.getStatus()) {
                case HIT -> result.setHits(result.getHits() + 1);
                case COUNTRY_ALERT -> result.setCountryAlerts(result.getCountryAlerts() + 1);
                case CLEAN -> result.setClean(result.getClean() + 1);
                case INVALID -> result.setInvalid(result.getInvalid() + 1);
                case ERROR -> result.setErrors(result.getErrors() + 1);
            }
        }
        result.setTotal(result.getRows().size());
        result.setDurationMs(System.currentTimeMillis() - start);
        log.info("Vérification par lot « {} » : {} entités, {} correspondances, {} alertes pays, {} erreurs, {} ms",
                result.getFileName(), result.getTotal(), result.getHits(), result.getCountryAlerts(),
                result.getErrors(), result.getDurationMs());
        return result;
    }

    private BatchVerificationRowDTO verifyOne(BatchEntityDTO e, String dataset) {
        BatchVerificationRowDTO row = new BatchVerificationRowDTO();
        row.setEntity(e);
        if (!e.isValid() || e.getName() == null || e.getName().isBlank()) {
            row.setStatus(Status.INVALID);
            row.setErrorMessage(e.getWarnings().isEmpty() ? "Ligne invalide" : String.join(" ", e.getWarnings()));
            return row;
        }
        try {
            AmlVerificationRequestDTO req = new AmlVerificationRequestDTO();
            req.setDataset(dataset);
            String schema = e.getSchema() == null ? "Person" : e.getSchema();
            req.setSchema(schema);
            req.setProperties(toProperties(e, schema));

            AmlVerificationResponseDTO resp = verificationService.verifyClient(req);
            List<AmlMatchDTO> matches = resp.getMatches() == null ? new ArrayList<>() : resp.getMatches();
            row.setMatches(matches);
            row.setMatchesCount(matches.size());

            List<AmlMatchDTO> listed = matches.stream()
                    .filter(m -> m.getId() == null || !m.getId().startsWith(FATF_GEO_PREFIX)).toList();
            if (!listed.isEmpty()) {
                row.setStatus(Status.HIT);
                AmlMatchDTO top = listed.stream()
                        .max(Comparator.comparing(m -> m.getScore() == null ? 0.0 : m.getScore())).get();
                row.setTopScore(top.getScore());
                row.setTopMatchCaption(top.getCaption());
            } else if (!matches.isEmpty()) {
                row.setStatus(Status.COUNTRY_ALERT);
            } else {
                row.setStatus(Status.CLEAN);
            }
            row.setHighestRisk(highestRisk(matches));
        } catch (Exception ex) {
            log.warn("Vérification par lot, ligne {} ({}) en échec : {}", e.getLineNumber(), e.getName(), ex.getMessage());
            return errorRow(e, ex.getMessage());
        }
        return row;
    }

    private static Map<String, List<String>> toProperties(BatchEntityDTO e, String schema) {
        Map<String, List<String>> props = new LinkedHashMap<>();
        props.put("name", List.of(e.getName()));
        boolean person = "Person".equals(schema);
        if (person) {
            if (e.getFirstName() != null) props.put("firstName", List.of(e.getFirstName()));
            if (e.getLastName() != null) props.put("lastName", List.of(e.getLastName()));
            if (e.getBirthDate() != null) props.put("birthDate", List.of(e.getBirthDate()));
            if (e.getNationality() != null) props.put("nationality", List.of(e.getNationality()));
            if (e.getBirthPlace() != null) props.put("birthPlace", List.of(e.getBirthPlace()));
            if (e.getCountry() != null) props.put("country", List.of(e.getCountry()));
        } else {
            // Une organisation n'a pas de nationalité : la colonne sert alors de pays.
            String country = e.getCountry() != null ? e.getCountry() : e.getNationality();
            if (country != null) props.put("country", List.of(country));
        }
        return props;
    }

    private static BatchVerificationRowDTO errorRow(BatchEntityDTO e, String message) {
        BatchVerificationRowDTO row = new BatchVerificationRowDTO();
        row.setEntity(e);
        row.setStatus(Status.ERROR);
        row.setErrorMessage(message == null ? "Erreur inconnue" : message);
        return row;
    }

    private static final List<String> RISK_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private static String highestRisk(List<AmlMatchDTO> matches) {
        String best = null;
        for (AmlMatchDTO m : matches) {
            String r = m.getRiskCategory();
            if (r == null) continue;
            if (best == null || RISK_ORDER.indexOf(r.toUpperCase()) > RISK_ORDER.indexOf(best)) best = r.toUpperCase();
        }
        return best;
    }

    // ------------------------------------------------------------------ rapport PDF

    public byte[] generateReport(BatchVerificationResultDTO result) {
        try (InputStream template = getClass().getResourceAsStream("/reports/batch_report.jrxml")) {
            if (template == null) throw new IllegalStateException("Le gabarit du rapport de lot est introuvable");
            JasperReport report = JasperCompileManager.compileReport(template);

            Map<String, Object> params = new HashMap<>();
            params.put("fileName", result.getFileName() == null ? "-" : result.getFileName());
            params.put("dataset", result.getDataset() == null ? "default" : result.getDataset());
            params.put("startedAt", formatInstant(result.getStartedAt()));
            params.put("duration", formatDuration(result.getDurationMs()));
            params.put("total", result.getTotal());
            params.put("hits", result.getHits());
            params.put("countryAlerts", result.getCountryAlerts());
            params.put("clean", result.getClean());
            params.put("invalid", result.getInvalid());
            params.put("errors", result.getErrors());
            params.put("logoPath", getClass().getResourceAsStream("/static/images/logo.png"));

            // Correspondances d'abord, puis alertes pays, erreurs, lignes invalides, et enfin les lignes sans résultat.
            List<BatchVerificationRowDTO> rows = new ArrayList<>(result.getRows());
            rows.sort(Comparator.comparingInt((BatchVerificationRowDTO r) -> statusRank(r.getStatus()))
                    .thenComparing(r -> r.getEntity() == null ? 0 : r.getEntity().getLineNumber()));
            rows.forEach(this::prepareForReport);

            JasperPrint print = JasperFillManager.fillReport(report, params, new JRBeanCollectionDataSource(rows));
            return JasperExportManager.exportReportToPdf(print);
        } catch (Exception e) {
            log.error("Erreur lors de la génération du rapport de lot", e);
            throw new RuntimeException("Erreur de génération du rapport : " + e.getMessage(), e);
        }
    }

    private static int statusRank(Status s) {
        if (s == null) return 9;
        return switch (s) {
            case HIT -> 0;
            case COUNTRY_ALERT -> 1;
            case ERROR -> 2;
            case INVALID -> 3;
            case CLEAN -> 4;
        };
    }

    private void prepareForReport(BatchVerificationRowDTO row) {
        BatchEntityDTO e = row.getEntity() == null ? new BatchEntityDTO() : row.getEntity();
        row.setLineLabel(String.valueOf(e.getLineNumber()));
        row.setEntityLabel(e.getName() == null ? "(sans nom)" : e.getName());

        List<String> details = new ArrayList<>();
        if (e.getReference() != null) details.add("Réf. " + esc(e.getReference()));
        if (e.getSchema() != null && !"Person".equals(e.getSchema())) details.add(esc(e.getSchema()));
        if (e.getBirthDate() != null) details.add("Né(e) le " + esc(formatIsoDate(e.getBirthDate())));
        if (e.getNationality() != null) details.add("Nat. " + esc(e.getNationality().toUpperCase()));
        if (e.getCountry() != null) details.add("Pays " + esc(e.getCountry().toUpperCase()));
        row.setEntityDetails(String.join(" · ", details));

        Status s = row.getStatus() == null ? Status.ERROR : row.getStatus();
        switch (s) {
            case HIT -> { row.setStatusLabel("CORRESPONDANCE"); row.setStatusColor("#DC2626"); }
            case COUNTRY_ALERT -> { row.setStatusLabel("ALERTE PAYS"); row.setStatusColor("#D97706"); }
            case CLEAN -> { row.setStatusLabel("AUCUN RÉSULTAT"); row.setStatusColor("#059669"); }
            case INVALID -> { row.setStatusLabel("NON VÉRIFIÉE"); row.setStatusColor("#64748B"); }
            case ERROR -> { row.setStatusLabel("ERREUR"); row.setStatusColor("#7C3AED"); }
        }

        StringBuilder html = new StringBuilder();
        if (s == Status.ERROR || s == Status.INVALID) {
            html.append("<font color='#64748B'>").append(esc(row.getErrorMessage())).append("</font>");
        } else if (row.getMatches() != null) {
            int shown = 0;
            for (AmlMatchDTO m : row.getMatches()) {
                if (shown++ >= 5) {
                    html.append("<font color='#64748B'>… et ").append(row.getMatches().size() - 5)
                            .append(" autre(s) correspondance(s)</font><br>");
                    break;
                }
                html.append("<b>").append(esc(m.getCaption())).append("</b>");
                if (m.getScore() != null) {
                    html.append(" <font color='#DC2626'><b>").append(Math.round(m.getScore() * 100)).append(" %</b></font>");
                }
                if (m.getPepStatusLabel() != null) {
                    html.append(" — <font color='#1D4ED8'>").append(esc(m.getPepStatusLabel())).append("</font>");
                }
                if (m.getDatasets() != null && !m.getDatasets().isEmpty()) {
                    html.append("<br><font color='#64748B'>Listes : ").append(esc(String.join(", ", m.getDatasets()))).append("</font>");
                }
                html.append("<br>");
            }
        }
        // Pour une ligne non vérifiée, le message d'erreur reprend déjà les avertissements.
        if (!e.getWarnings().isEmpty() && s != Status.INVALID) {
            html.append("<font color='#D97706'>").append(esc(String.join(" ", e.getWarnings()))).append("</font>");
        }
        row.setMatchesHtml(html.toString());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static String formatIsoDate(String iso) {
        if (iso != null && iso.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
        }
        return iso;
    }

    private static String formatInstant(String iso) {
        try {
            return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(Instant.parse(iso));
        } catch (Exception e) {
            return iso == null ? "-" : iso;
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }

    /** Modèle de fichier proposé au téléchargement. */
    public String templateCsv() {
        return "﻿reference;type;nom;prenom;nationalite;date_naissance;lieu_naissance;pays\r\n"
                + "CLI-001;personne;Poutine;Vladimir;RU;07/10/1952;Leningrad;RU\r\n"
                + "CLI-002;personne;Dupont;Marie;FR;15/03/1980;Paris;FR\r\n"
                + "CLI-003;societe;Rosneft;;;;;RU\r\n";
    }
}
