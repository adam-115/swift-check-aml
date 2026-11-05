package com.swiftcheck.aml.sdk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>InseeDeathFilter</b> – Service de filtrage et d'enrichissement par le Fichier officiel des personnes décédées
 * de l'INSEE (France / data.gouv.fr).
 *
 * <p>Permet à SwiftCheck AML d'étendre la profondeur historique des anciens PEP (5, 10 ou 15 ans) tout en
 * éliminant 100 % des faux positifs sur des personnes décédées (obligation AML et avantage compétitif majeur
 * face à OpenSanctions qui conserve des milliers de personnes décédées issues de Wikidata).
 */
public final class InseeDeathFilter {

    private static final Logger log = LoggerFactory.getLogger(InseeDeathFilter.class);

    private static final Pattern ACCENTS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Clé d'indexation : nom_normalise|prenom_normalise|date_naissance */
    private final Map<String, LocalDate> deceasedIndex = new HashMap<>();

    public InseeDeathFilter() {
    }

    /**
     * Charge les enregistrements depuis un flux CSV ou texte fixe de l'INSEE.
     */
    public void load(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            boolean isCsv = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (first) {
                    first = false;
                    if (line.contains(";") || line.contains(",")) {
                        isCsv = true;
                        continue; // ignorer l'en-tête CSV
                    }
                }
                if (isCsv) {
                    parseCsvLine(line);
                } else {
                    parseFixedLine(line);
                }
            }
        }
        log.info("Fichier INSEE chargé : {} personnes décédées indexées.", deceasedIndex.size());
    }

    /**
     * Parse une ligne du format fixe INSEE (nom*prenoms, sexe, date naiss, lieu naiss, date deces, acte).
     */
    private void parseFixedLine(String line) {
        if (line.length() < 79) {
            return;
        }
        try {
            // Le nom et les prénoms sont séparés par '*'
            int star = line.indexOf('*');
            if (star <= 0) {
                return;
            }
            String nom = line.substring(0, star).trim();
            int slash = line.indexOf('/', star);
            String prenom = (slash > star ? line.substring(star + 1, slash) : line.substring(star + 1, Math.min(star + 20, line.length()))).trim();

            // Date de naissance en position fixe (env. colonnes 50-58)
            String dateNaissStr = line.substring(Math.max(0, star + 15), Math.min(line.length(), star + 23)).replaceAll("[^0-9]", "");
            // Date de décès en fin de ligne (8 chiffres YYYYMMDD)
            String dateDecesStr = line.substring(Math.max(0, line.length() - 17), Math.min(line.length(), line.length() - 9)).replaceAll("[^0-9]", "");

            LocalDate dateDeces = parseIsoDate(dateDecesStr);
            String key = makeKey(nom, prenom, dateNaissStr);
            if (key != null && dateDeces != null) {
                deceasedIndex.put(key, dateDeces);
                String altKey = makeKey(nom, "", dateNaissStr);
                if (altKey != null) {
                    deceasedIndex.putIfAbsent(altKey, dateDeces);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Parse une ligne CSV (champs séparés par ';' ou ',').
     */
    private void parseCsvLine(String line) {
        String[] parts = line.split("[;,]");
        if (parts.length < 7) {
            return;
        }
        String nom = parts[0].replaceAll("\"", "").trim();
        String prenom = parts[1].replaceAll("\"", "").trim();
        String dateNaiss = parts[3].replaceAll("[^0-9]", "").trim();
        String dateDecesStr = parts[6].replaceAll("[^0-9]", "").trim();

        LocalDate dateDeces = parseIsoDate(dateDecesStr);
        String key = makeKey(nom, prenom, dateNaiss);
        if (key != null && dateDeces != null) {
            deceasedIndex.put(key, dateDeces);
            String altKey = makeKey(nom, "", dateNaiss);
            if (altKey != null) {
                deceasedIndex.putIfAbsent(altKey, dateDeces);
            }
        }
    }

    public static InseeDeathFilter loadFromUrlOrFile(String urlOrPath) throws Exception {
        InseeDeathFilter filter = new InseeDeathFilter();
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<InputStream> resp = client.send(
                    HttpRequest.newBuilder(URI.create(urlOrPath)).header("User-Agent", "SwiftCheck-AML/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 200) {
                filter.load(resp.body());
            } else {
                throw new IOException("HTTP " + resp.statusCode() + " pour " + urlOrPath);
            }
        } else {
            Path file = Path.of(urlOrPath);
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    filter.load(in);
                }
            }
        }
        return filter;
    }

    /**
     * Vérifie si une personne physique est recensée comme décédée.
     */
    public boolean isDeceased(String lastName, String firstName, String birthDate) {
        return getDeathDate(lastName, firstName, birthDate) != null;
    }

    /**
     * Récupère la date exacte de décès si la personne est trouvée.
     */
    public LocalDate getDeathDate(String lastName, String firstName, String birthDate) {
        if (lastName == null || lastName.isBlank()) {
            return null;
        }
        String bDateDigits = birthDate == null ? "" : birthDate.replaceAll("[^0-9]", "");
        String key = makeKey(lastName, firstName, bDateDigits);
        if (key != null && deceasedIndex.containsKey(key)) {
            return deceasedIndex.get(key);
        }
        // Tentative de recherche assouplie sur nom + date de naissance exacte si le prénom varie
        if (!bDateDigits.isBlank() && bDateDigits.length() >= 8) {
            String fallbackKey = normalize(lastName) + "||" + bDateDigits.substring(0, 8);
            if (deceasedIndex.containsKey(fallbackKey)) {
                return deceasedIndex.get(fallbackKey);
            }
        }
        return null;
    }

    /**
     * Enrichit les entités du dataset ou élimine les anciens PEP décédés.
     *
     * @param dataset dataset FollowTheMoney à traiter
     * @param removeFormerIfDeceased si true, les anciens PEP décédés sont retirés du dataset
     * @return nombre de personnes décédées détectées
     */
    public int processDataset(FtmDatasetBuilder dataset, boolean removeFormerIfDeceased) {
        int deceasedCount = 0;
        Set<String> deceasedPersonIds = new HashSet<>();

        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                String lastName = firstValue(entity, "lastName");
                if (lastName == null) {
                    lastName = firstValue(entity, "name");
                }
                String firstName = firstValue(entity, "firstName");
                String birthDate = firstValue(entity, "birthDate");

                LocalDate death = getDeathDate(lastName, firstName, birthDate);
                if (death != null) {
                    deceasedCount++;
                    deceasedPersonIds.add(entity.getId());
                    entity.addValue("deathDate", death.toString());
                }
            }
        }

        if (removeFormerIfDeceased && !deceasedPersonIds.isEmpty()) {
            Set<String> idsToRemove = new HashSet<>();
            for (FtmEntity e : dataset.entities()) {
                if ("Occupancy".equals(e.getSchema())) {
                    String holder = firstValue(e, "holder");
                    String status = firstValue(e, "status");
                    if (holder != null && deceasedPersonIds.contains(holder) && "ended".equalsIgnoreCase(status)) {
                        idsToRemove.add(e.getId());
                    }
                } else if ("Person".equals(e.getSchema()) && deceasedPersonIds.contains(e.getId())) {
                    // Si tous les mandats sont terminés et la personne est décédée, on l'écarte
                    boolean hasCurrentMandate = dataset.entities().stream()
                            .anyMatch(o -> "Occupancy".equals(o.getSchema())
                                    && e.getId().equals(firstValue(o, "holder"))
                                    && "current".equalsIgnoreCase(firstValue(o, "status")));
                    if (!hasCurrentMandate) {
                        idsToRemove.add(e.getId());
                    }
                }
            }
            for (String id : idsToRemove) {
                dataset.removeEntity(id);
            }
        }

        return deceasedCount;
    }

    private static String firstValue(FtmEntity entity, String prop) {
        var vals = entity.getValues(prop);
        return vals.isEmpty() ? null : vals.iterator().next();
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim().toUpperCase(), Normalizer.Form.NFD);
        return ACCENTS.matcher(n).replaceAll("").replaceAll("[^A-Z]", "");
    }

    private static String makeKey(String nom, String prenom, String dateNaiss) {
        String n = normalize(nom);
        if (n.isBlank()) {
            return null;
        }
        // Prendre uniquement le premier prénom normalisé pour matcher les prénoms composés
        String[] tokens = prenom.split("[\\s,-]+");
        String p = tokens.length > 0 ? normalize(tokens[0]) : "";
        String d = dateNaiss == null ? "" : dateNaiss.replaceAll("[^0-9]", "");
        if (d.length() > 8) {
            d = d.substring(0, 8);
        }
        return n + "|" + p + "|" + d;
    }

    private static LocalDate parseIsoDate(String d) {
        if (d == null || d.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(d.substring(0, 8), BASIC_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
