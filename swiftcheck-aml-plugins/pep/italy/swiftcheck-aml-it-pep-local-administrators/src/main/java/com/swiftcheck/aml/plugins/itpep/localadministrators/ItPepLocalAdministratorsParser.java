package com.swiftcheck.aml.plugins.itpep.localadministrators;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.itpep.localadministrators.AdministratorsCsv.Record;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>it_pep_local_administrators</b> – maires des communes d'au moins 15 000 habitants, présidents, assesseurs et
 * conseillers des Régions (D.Lgs. 231/2007, art. 1 c.2 lett. dd), points 1.1 et 1.2 ; liste notifiée, JO C/2023/724).
 *
 * <p>Source : l'« Anagrafe degli amministratori locali e regionali » du ministère de l'Intérieur, publiée en CSV
 * ({@code ammcom.csv} pour les communes, {@code ammreg.csv} pour les Régions), téléchargée à chaque exécution avec un
 * User-Agent générique. Le fichier ne contient que les personnes en fonction : celles qui en disparaissent restent
 * publiées pendant la durée de rétention du parser (12 mois) par le cœur.
 *
 * <p>Sont gardés : « Sindaco » des communes dont la population à la date d'élection atteint {@code minPopulation}
 * (15 000 par défaut) ou dont le code figure dans {@code capoluogoCodes} (chefs-lieux plus petits, « PPP/CCCC » :
 * codes province et commune) ; « Presidente della regione », « Assessore » (y compris non élu) et « Consigliere » (y
 * compris « candidato presidente ») de chaque Région. Pas gardés : suppléants, assesseurs et conseillers communaux.
 * Données minimisées : ni diplôme, ni profession, ni liste électorale ne sont repris.
 *
 * <p>Paramètres : {@code communesUrl}, {@code regionsUrl}, {@code minPopulation}, {@code capoluogoCodes}.
 */
@Extension
public class ItPepLocalAdministratorsParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "it-pep-local-administrators";
    static final String TOPIC = "role.pep";
    static final String COMMUNES_URL = "https://dait.interno.gov.it/documenti/ammcom.csv";
    static final String REGIONS_URL = "https://dait.interno.gov.it/documenti/ammreg.csv";
    static final String COMMUNES_FILE = "ammcom.csv";
    static final String REGIONS_FILE = "ammreg.csv";
    static final int DEFAULT_MIN_POPULATION = 15_000;
    /** Contact générique : jamais l'adresse d'un utilisateur. */
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Italie - anagrafe des administrateurs)";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int mayors, int smallCommunesSkipped, int presidents, int regionalAssessors, int regionalCouncillors,
            String communesUpdated, String regionsUpdated, int minPopulation, List<String> regionsWithoutPresident) {
        String message() {
            return String.format(Locale.ROOT,
                    "%d maires (communes d'au moins %d habitants), %d présidents de Région, %d assesseurs régionaux et "
                            + "%d conseillers régionaux. %d maires de plus petites communes écartés. Fichiers du ministère de "
                            + "l'Intérieur mis à jour le %s (communes) et le %s (Régions).",
                    mayors, minPopulation, presidents, regionalAssessors, regionalCouncillors, smallCommunesSkipped,
                    communesUpdated, regionsUpdated)
                    + (regionsWithoutPresident.isEmpty() ? "" : " Régions sans président dans le fichier : "
                    + String.join(", ", regionsWithoutPresident) + ". Valle d'Aosta et Trentino-Alto Adige (statut spécial) ne figurent pas dans le fichier des Régions.");
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun dossier de données brutes.");
        }
        Files.createDirectories(ctx.rawDir());
        download(text(p, "communesUrl", COMMUNES_URL), ctx.rawDir().resolve(COMMUNES_FILE));
        download(text(p, "regionsUrl", REGIONS_URL), ctx.rawDir().resolve(REGIONS_FILE));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir().resolve(COMMUNES_FILE), ctx.rawDir().resolve(REGIONS_FILE),
                (int) number(p, "minPopulation", DEFAULT_MIN_POPULATION), codes(p.get("capoluogoCodes")), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne publiée, avec l'ID et le nom que Yente doit connaître. */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir().resolve(COMMUNES_FILE), ctx.rawDir().resolve(REGIONS_FILE),
                (int) number(p, "minPopulation", DEFAULT_MIN_POPULATION), codes(p.get("capoluogoCodes")), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(), COMMUNES_URL));
            }
        }
        return records;
    }

    /** Lit les deux fichiers et écrit les entités. */
    Report convert(Path communes, Path regions, int minPopulation, Set<String> capoluogoCodes,
            FtmDatasetBuilder dataset) throws IOException {
        Map<String, FtmEntity> positions = new LinkedHashMap<>();
        int[] counts = new int[6];                       // maires, écartés, présidents, assesseurs, conseillers
        AdministratorsCsv.forEach(communes, r -> {
            if (!"Sindaco".equalsIgnoreCase(r.get("descrizione_carica"))) {
                return;
            }
            String code = r.get("codice_provincia") + "/" + r.get("codice_comune");
            if (population(r) < minPopulation && !capoluogoCodes.contains(code)) {
                counts[1]++;
                return;
            }
            String place = titleCase(r.get("denominazione_comune"));
            write(dataset, positions, r, "commune|" + code + "|sindaco", "Sindaco di " + place + " (Italie)",
                    "Comune di " + place, "Sindaco", List.of("gov.muni", "gov.executive"));
            counts[0]++;
        });
        Set<String> regionsSeen = new java.util.TreeSet<>();
        Set<String> regionsWithPresident = new HashSet<>();
        AdministratorsCsv.forEach(regions, r -> {
            String role = regionRole(r.get("descrizione_carica"));
            if (role == null) {
                return;
            }
            String region = titleCase(r.get("denominazione_regione"));
            regionsSeen.add(region);
            if (role.equals("president")) {
                regionsWithPresident.add(region);
            }
            String key = "region|" + r.get("codice_regione") + "|" + role;
            switch (role) {
                case "president" -> {
                    write(dataset, positions, r, key, "Président de la Région " + region + " (Italie)",
                            "Regione " + region, "Presidente della regione", List.of("gov.state", "gov.head", "gov.executive"));
                    counts[2]++;
                }
                case "assessor" -> {
                    write(dataset, positions, r, key, "Assesseur régional de " + region + " (Italie)",
                            "Regione " + region, "Assessore regionale", List.of("gov.state", "gov.executive"));
                    counts[3]++;
                }
                default -> {
                    write(dataset, positions, r, key, "Conseiller régional de " + region + " (Italie)",
                            "Consiglio regionale " + region, "Consigliere regionale", List.of("gov.state", "gov.legislative"));
                    counts[4]++;
                }
            }
        });
        if (counts[0] == 0 || counts[2] == 0) {
            throw new IllegalStateException("Aucun maire ou aucun président de Région lu : fichiers vides ou format changé.");
        }
        return new Report(counts[0], counts[1], counts[2], counts[3], counts[4], AdministratorsCsv.updatedOn(communes),
                AdministratorsCsv.updatedOn(regions), minPopulation,
                regionsSeen.stream().filter(x -> !regionsWithPresident.contains(x)).toList());
    }

    /** « Presidente della regione » → president ; « Assessore » (même non élu) → assessor ; « Consigliere » → councillor. */
    static String regionRole(String carica) {
        if (carica == null) {
            return null;
        }
        String c = carica.toLowerCase(Locale.ROOT);
        if (c.contains("supplente")) {
            return null;
        }
        if (c.startsWith("presidente della regione")) {
            return "president";
        }
        if (c.startsWith("assessore")) {
            return "assessor";
        }
        return c.startsWith("consigliere") ? "councillor" : null;
    }

    private static void write(FtmDatasetBuilder dataset, Map<String, FtmEntity> positions, Record r, String positionKey,
            String positionLabel, String organization, String role, List<String> topics) {
        String last = titleCase(r.get("cognome"));
        String first = titleCase(r.get("nome"));
        String birthDate = isoDate(r.get("data_nascita"));
        // Le fichier n'a pas d'identifiant : nom + date de naissance (+ lieu) identifient la personne
        FtmEntity person = dataset.newEntity("Person", r.get("cognome"), r.get("nome"), r.get("data_nascita"),
                r.get("luogo_nascita"));
        person.addValue("name", (first + " " + last).trim());
        person.addValue("firstName", first);
        person.addValue("lastName", last);
        person.addValue("birthDate", birthDate);
        person.addValue("birthPlace", birthPlace(r.get("luogo_nascita")));
        person.addValue("gender", "M".equalsIgnoreCase(r.get("sesso")) ? "male" : "F".equalsIgnoreCase(r.get("sesso")) ? "female" : null);
        person.addValue("country", "it");
        person.addValue("nationality", "it");
        person.addValue("position", role + " – " + organization);
        person.addValue("sourceUrl", COMMUNES_URL.replace("ammcom.csv", ""));
        dataset.addEntity(person);

        FtmEntity position = positions.computeIfAbsent(positionKey, k -> {
            FtmEntity pos = dataset.newEntity("Position", k);
            pos.addValue("name", positionLabel);
            pos.addValue("country", "it");
            pos.addValue("organization", organization);
            for (String topic : topics) {
                pos.addValue("topics", topic);
            }
            dataset.addEntity(pos);
            return pos;
        });
        String start = isoDate(r.get("data_entrata_in_carica") != null ? r.get("data_entrata_in_carica")
                : r.get("data_elezione"));
        FtmEntity occupancy = dataset.newEntity("Occupancy", r.get("cognome"), r.get("nome"), r.get("data_nascita"),
                positionKey);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("status", "current");
        occupancy.addValue("description", role);
        dataset.addEntity(occupancy);
    }

    private static int population(Record r) {
        try {
            return Integer.parseInt(r.get("popolazione_censita_alla_data_elezione"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static Set<String> codes(String spec) {
        Set<String> out = new HashSet<>();
        if (spec != null) {
            for (String s : spec.split("[,;]")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    /** « 05/03/1971 » → « 1971-03-05 ». */
    static String isoDate(String dmy) {
        if (dmy == null) {
            return null;
        }
        try {
            return LocalDate.parse(dmy.trim(), DMY).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** « ACQUI TERME (AL) » → « Acqui Terme ». */
    static String birthPlace(String place) {
        if (place == null) {
            return null;
        }
        return titleCase(place.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim());
    }

    /** « DE CORATO » → « De Corato » ; « D'ALEMA » → « D'Alema » ; « SANT'ANGELO » → « Sant'Angelo ». */
    static String titleCase(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean up = true;
        for (char c : s.trim().toCharArray()) {
            out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = c == ' ' || c == '-' || c == '\'' || c == '’';
        }
        return out.toString();
    }

    static void download(String url, Path target) throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " pour " + url);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    private static long number(Map<String, String> p, String key, long defaultValue) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + key + " » invalide : « " + value + " » (nombre attendu).");
        }
    }

    private static String text(Map<String, String> p, String key, String defaultValue) {
        String value = p.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
