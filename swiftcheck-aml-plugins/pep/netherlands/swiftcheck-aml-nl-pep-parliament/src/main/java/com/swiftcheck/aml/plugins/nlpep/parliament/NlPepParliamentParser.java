package com.swiftcheck.aml.plugins.nlpep.parliament;

import java.io.IOException;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>nl_pep_parliament</b> – membres de la Tweede Kamer et de l'Eerste Kamer des États généraux néerlandais
 * (Wet ter voorkoming van witwassen en financieren van terrorisme, Wwft : personnes politiquement exposées).
 *
 * <p>Sources officielles :
 * <ul>
 *   <li><b>Tweede Kamer</b> : l'API OData ouverte {@code gegevensmagazijn.tweedekamer.nl} : {@code Persoon} (identité,
 *       naissance, décès) et {@code FractieZetelPersoon} (sièges avec dates et groupe) ; sont repris les sièges en cours et
 *       ceux terminés depuis moins de {@code monthsAfterEnd} mois, les personnes décédées sont écartées ;</li>
 *   <li><b>Eerste Kamer</b> : la liste officielle {@code eerstekamer.nl/alle_leden} (les 75 membres en fonction, groupe,
 *       naissance, anciennet&eacute; en jours : le début du mandat est la date du jour moins ces jours). Le nom complet et
 *       l'identifiant viennent de la fiche {@code Persoon} de même naissance et de même nom de famille. L'API ne dit pas
 *       dans quelle chambre un ancien député a siégé en dehors de la Tweede Kamer : les anciens sénateurs ne sont pas repris.</li>
 * </ul>
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"odataUrl": "https://gegevensmagazijn.tweedekamer.nl/OData/v4/2.0",
 *  "senateUrl": "https://www.eerstekamer.nl/alle_leden", "monthsAfterEnd": "12", "delayMs": "500"}
 * }</pre>
 */
@Extension
public class NlPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "nl-pep-parliament";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String SENATE_FILE = "eerste-kamer.html";
    static final int PAGE_SIZE = 250;

    static final String TWEEDE = "Lid van de Tweede Kamer der Staten-Generaal";
    static final String EERSTE = "Lid van de Eerste Kamer der Staten-Generaal";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lijst PEP Nederland - Staten-Generaal)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final Pattern SENATOR = Pattern.compile("(?is)<li class=\"persoon[^\"]*\">(.*?)</li>");
    private static final Pattern SENATOR_NAME = Pattern.compile("(?is)<div class=\"naam\">(.*?)</div>");
    private static final Pattern SENATOR_PARTY = Pattern.compile("(?is)</div></div><div>([^<]*)</div><div>Anciënniteit:\\s*(\\d+)\\s*dagen");
    private static final Pattern SENATOR_BIRTH = Pattern.compile("Geboortedatum:\\s*(\\d{2}-\\d{2}-\\d{4})");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Une personne de l'API OData. */
    record Person(String id, String firstNames, String infix, String lastName, String initials, String sex, String function,
            String birthDate, String birthPlace, String deathDate) {

        String fullName() {
            List<String> parts = new ArrayList<>();
            parts.add(firstNames == null || firstNames.isBlank() ? initials : firstNames);
            if (infix != null && !infix.isBlank()) {
                parts.add(infix);
            }
            parts.add(lastName);
            return String.join(" ", parts.stream().filter(s -> s != null && !s.isBlank()).toList()).trim();
        }
    }

    /** Un sénateur de la liste officielle. */
    record Senator(String name, String party, int days, String birthDate) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int lowerCurrent, int lowerFormer, int upperCurrent, int upperUnmatched, int skippedDead) {

        String message() {
            return String.format("Tweede Kamer : %d en fonction, %d anciens (période de rétention) ; Eerste Kamer : %d en fonction "
                    + "(%d sans fiche Persoon correspondante) ; %d personnes décédées écartées.", lowerCurrent, lowerFormer,
                    upperCurrent, upperUnmatched, skippedDead);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        download(httpFetcher(delayMs(p)), required(p, "odataUrl"), required(p, "senateUrl"), LocalDate.now(), months, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), LocalDate.now(), months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Télécharge les personnes, les sièges (en cours ou terminés depuis {@code cutoff}) et la liste de l'Eerste Kamer. */
    static void download(Fetcher fetcher, String odataUrl, String senateUrl, LocalDate today, int monthsAfterEnd, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = odataUrl.endsWith("/") ? odataUrl.substring(0, odataUrl.length() - 1) : odataUrl;
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        pages(fetcher, base + "/Persoon?$select=Id,Titels,Initialen,Tussenvoegsel,Achternaam,Voornamen,Geslacht,Functie,"
                + "Geboortedatum,Geboorteplaats,Overlijdensdatum&$filter=Verwijderd%20eq%20false&$orderby=Id", "persoon", rawDir);
        pages(fetcher, base + "/FractieZetelPersoon?$select=Persoon_Id,Functie,Van,TotEnMet&$filter=Verwijderd%20eq%20false%20and%20"
                + "(TotEnMet%20eq%20null%20or%20TotEnMet%20ge%20" + cutoff + "T00:00:00Z)"
                + "&$expand=FractieZetel($select=Fractie_Id;$expand=Fractie($select=Afkorting))&$orderby=Id", "zetel", rawDir);
        Files.writeString(rawDir.resolve(SENATE_FILE), fetcher.get(senateUrl), StandardCharsets.UTF_8);
    }

    private static void pages(Fetcher fetcher, String url, String prefix, Path rawDir) throws IOException, InterruptedException {
        for (int page = 0; ; page++) {
            String body = fetcher.get(url + "&$top=" + PAGE_SIZE + "&$skip=" + (page * PAGE_SIZE));
            Files.writeString(rawDir.resolve(String.format("%s-%03d.json", prefix, page)), body, StandardCharsets.UTF_8);
            JsonNode value = JSON.readTree(body).path("value");
            if (!value.isArray()) {
                throw new IllegalStateException("Réponse OData inattendue (pas de « value ») pour " + prefix + " : " + url);
            }
            if (value.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les parlementaires. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        Map<String, Person> persons = new HashMap<>();
        for (JsonNode n : read(rawDir, "persoon")) {
            persons.put(n.path("Id").asText(), new Person(n.path("Id").asText(), text(n, "Voornamen"), text(n, "Tussenvoegsel"),
                    text(n, "Achternaam"), text(n, "Initialen"), text(n, "Geslacht"), text(n, "Functie"),
                    text(n, "Geboortedatum"), text(n, "Geboorteplaats"), text(n, "Overlijdensdatum")));
        }
        if (persons.isEmpty()) {
            throw new IllegalStateException("Aucune personne dans persoon-*.json : le téléchargement n'a pas eu lieu ou le format a changé.");
        }
        int lowerCurrent = 0, lowerFormer = 0, dead = 0;
        java.util.Set<String> seenCurrent = new java.util.HashSet<>();
        java.util.Set<String> seenFormer = new java.util.HashSet<>();
        for (JsonNode seat : read(rawDir, "zetel")) {
            Person person = persons.get(seat.path("Persoon_Id").asText());
            if (person == null) {
                continue;
            }
            if (person.deathDate() != null) {
                dead++;
                continue;                                       // décédé : plus une personne à surveiller
            }
            LocalDate start = date(text(seat, "Van"));
            LocalDate end = date(text(seat, "TotEnMet"));
            if (end != null && end.isBefore(cutoff)) {
                continue;
            }
            String group = seat.path("FractieZetel").path("Fractie").path("Afkorting").asText(null);
            write(person, person.id(), TWEEDE, "tweede-kamer", start, end, group == null ? null : "Fractie : " + group, dataset);
            if (end == null) {
                if (seenCurrent.add(person.id())) {
                    lowerCurrent++;
                }
            } else if (seenFormer.add(person.id())) {
                lowerFormer++;
            }
        }
        if (lowerCurrent == 0) {
            throw new IllegalStateException("Aucun siège en cours à la Tweede Kamer : fichiers incomplets ou format changé.");
        }
        int upper = 0, unmatched = 0;
        Path senate = rawDir.resolve(SENATE_FILE);
        if (!Files.exists(senate)) {
            throw new IllegalStateException(SENATE_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Senator> senators = senators(Files.readString(senate, StandardCharsets.UTF_8));
        if (senators.isEmpty()) {
            throw new IllegalStateException("Aucun sénateur lu dans " + SENATE_FILE + " (éléments « li.persoon ») : la page a peut-être changé.");
        }
        for (Senator s : senators) {
            // Même naissance et même nom de famille ; la fonction « Eerste Kamerlid » est préférée, mais d'anciens
            // députés devenus sénateurs peuvent avoir gardé « Oud Kamerlid » ou « Tweede Kamerlid »
            Person match = persons.values().stream().filter(p -> s.birthDate() != null && s.birthDate().equals(p.birthDate())
                    && normalize(s.name()).contains(normalize(p.lastName())))
                    .min(java.util.Comparator.comparing((Person p) -> !"Eerste Kamerlid".equals(p.function()))).orElse(null);
            LocalDate start = today.minusDays(s.days());
            String description = s.party() == null ? null : "Groupe : " + s.party();
            if (match != null) {
                write(match, match.id(), EERSTE, "eerste-kamer", start, null, description, dataset);
            } else {
                unmatched++;
                Person alone = new Person(null, null, null, s.name(), null, null, null, s.birthDate(), null, null);
                write(alone, "eerste-kamer|" + s.name() + "|" + s.birthDate(), EERSTE, "eerste-kamer", start, null, description, dataset);
            }
            upper++;
        }
        return new Report(lowerCurrent, lowerFormer, upper, unmatched, dead);
    }

    /** Les sénateurs de la liste officielle : nom, groupe, anciennet&eacute; en jours et naissance. */
    static List<Senator> senators(String html) {
        List<Senator> out = new ArrayList<>();
        Matcher item = SENATOR.matcher(html);
        while (item.find()) {
            String block = Html.decode(item.group(1));          // « Anci&euml;nniteit » → « Anciënniteit »
            Matcher name = SENATOR_NAME.matcher(block);
            Matcher party = SENATOR_PARTY.matcher(block);
            if (!name.find()) {
                continue;
            }
            String partyName = null;
            int days = 0;
            if (party.find()) {
                partyName = Html.text(party.group(1));
                days = Integer.parseInt(party.group(2));
            }
            Matcher birth = SENATOR_BIRTH.matcher(block);
            String birthDate = birth.find() ? LocalDate.parse(birth.group(1), DMY).toString() : null;
            out.add(new Senator(Html.text(name.group(1)), partyName, days, birthDate));
        }
        return out;
    }

    private static void write(Person p, String id, String seatLabel, String seatKey, LocalDate start, LocalDate end,
            String description, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", id);
        String name = p.fullName();
        person.addValue("name", name);
        if (p.initials() != null && !p.initials().isBlank() && p.lastName() != null) {
            String withInitials = (p.initials() + " " + (p.infix() == null ? "" : p.infix() + " ") + p.lastName()).replaceAll("\\s+", " ").trim();
            if (!withInitials.equals(name)) {
                person.addValue("alias", withInitials);
            }
        }
        person.addValue("firstName", p.firstNames());
        person.addValue("lastName", p.lastName());
        person.addValue("gender", "man".equals(p.sex()) ? "male" : "vrouw".equals(p.sex()) ? "female" : null);
        person.addValue("birthDate", p.birthDate());
        person.addValue("birthPlace", p.birthPlace());
        person.addValue("country", "nl");
        person.addValue("position", seatLabel);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", seatKey);
        position.addValue("name", seatLabel);
        position.addValue("country", "nl");
        position.addValue("organization", seatKey.equals("tweede-kamer") ? "Tweede Kamer" : "Eerste Kamer");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.legislative");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", id, seatKey, String.valueOf(start));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start == null ? null : start.toString());
        occupancy.addValue("endDate", end == null ? null : end.toString());
        occupancy.addValue("status", end == null ? "current" : "ended");
        occupancy.addValue("description", description);
        dataset.addEntity(occupancy);
    }

    private static List<JsonNode> read(Path rawDir, String prefix) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)) {
            files = s.filter(f -> f.getFileName().toString().startsWith(prefix + "-") && f.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        }
        List<JsonNode> out = new ArrayList<>();
        for (Path file : files) {
            JsonNode value = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("value");
            value.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static LocalDate date(String s) {
        return s == null ? null : LocalDate.parse(s.substring(0, 10));
    }

    private static String normalize(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");
    }

    static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Paramètre « " + key + " » manquant dans la configuration du parser (voir le README du plugin).");
        }
        return v.trim();
    }

    static int monthsAfterEnd(Map<String, String> p) {
        String v = p.get("monthsAfterEnd");
        if (v == null || v.isBlank()) {
            return 12;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 500 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3)).header("User-Agent", USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
