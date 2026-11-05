package com.swiftcheck.aml.plugins.iepep.parliament;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * <b>ie_pep_parliament</b> – membres de l'Oireachtas (Dáil Éireann, Seanad Éireann) et du Gouvernement irlandais
 * (Taoiseach, ministres, ministres d'État), d'après l'API ouverte officielle {@code api.oireachtas.ie}
 * (Criminal Justice (Money Laundering and Terrorist Financing) Act 2010, article 37, personnes politiquement exposées).
 *
 * <p>Parcours : {@code /v1/houses} donne les législatures ; le plugin garde les Dáil et Seanad en cours et ceux terminés
 * depuis moins de {@code monthsAfterEnd} mois, puis lit {@code /v1/members?house_no=N&chamber=dail|seanad} pour chacun.
 * Chaque membre a ses mandats (dates, parti, circonscription ou panel) et ses fonctions ({@code offices} : Taoiseach,
 * ministres, ministres d'État, Ceann Comhairle, Cathaoirleach…) avec leurs dates. Sont repris : les membres en fonction,
 * les anciens membres dont le mandat a pris fin depuis moins de {@code monthsAfterEnd} mois, et les fonctions en cours ou
 * terminées dans cette période. Les personnes décédées sont ignorées.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://api.oireachtas.ie/v1", "monthsAfterEnd": "12", "delayMs": "1000"}
 * }</pre>
 * L'API ne donne ni date de naissance ni sexe.
 */
@Extension
public class IePepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "ie-pep-parliament";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String HOUSES = "houses.json";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Irlande - Oireachtas)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(int houses, int current, int former, int skipped, int offices) {

        String message() {
            return String.format("%d chambres lues : %d membres en fonction, %d anciens membres (moins d'un an), "
                    + "%d plus anciens ou décédés ignorés ; %d fonctions (ministres, présidents de chambre…).",
                    houses, current, former, skipped, offices);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int months = monthsAfterEnd(p);
        download(httpFetcher(delayMs(p)), required(p, "apiUrl"), LocalDate.now(), months, ctx.rawDir());
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

    /**
     * Lit la liste des chambres et télécharge les membres de chaque Dáil et Seanad en cours ou terminé depuis moins de
     * {@code monthsAfterEnd} mois. Retourne le nombre de chambres lues.
     */
    static int download(Fetcher fetcher, String apiUrl, LocalDate today, int monthsAfterEnd, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String housesText = fetcher.get(base + "/houses?limit=200");
        Files.writeString(rawDir.resolve(HOUSES), housesText, StandardCharsets.UTF_8);
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        int read = 0;
        for (JsonNode result : JSON.readTree(housesText).path("results")) {
            JsonNode house = result.path("house");
            String code = house.path("houseCode").asText();
            String number = house.path("houseNo").asText();
            LocalDate end = date(house.path("dateRange").path("end"));
            if (!(code.equals("dail") || code.equals("seanad")) || (end != null && end.isBefore(cutoff))) {
                continue;
            }
            String members = fetcher.get(base + "/members?house_no=" + number + "&chamber=" + code + "&limit=1000");
            Files.writeString(rawDir.resolve("members-" + code + "-" + number + ".json"), members, StandardCharsets.UTF_8);
            read++;
        }
        if (read == 0) {
            throw new IllegalStateException("Aucun Dáil ni Seanad en cours dans " + base + "/houses : le format de l'API a peut-être changé.");
        }
        return read;
    }

    /** Lit les fichiers de membres de {@code rawDir} et écrit les personnes. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(rawDir)) {
            files = s.filter(f -> f.getFileName().toString().startsWith("members-")).sorted().toList();
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("Aucun fichier members-*.json dans " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        int current = 0, former = 0, skipped = 0, offices = 0;
        java.util.Set<String> seenCurrent = new java.util.HashSet<>();
        for (Path file : files) {
            JsonNode results = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("Réponse inattendue dans " + file.getFileName() + " (pas de results).");
            }
            for (JsonNode result : results) {
                JsonNode member = result.path("member");
                if (!member.path("dateOfDeath").isNull() && !member.path("dateOfDeath").asText("").isBlank()) {
                    skipped++;
                    continue;
                }
                boolean kept = false;
                for (JsonNode m : member.path("memberships")) {
                    JsonNode membership = m.path("membership");
                    LocalDate end = date(membership.path("dateRange").path("end"));
                    if (end != null && end.isBefore(cutoff)) {
                        continue;
                    }
                    offices += write(member, membership, cutoff, dataset);
                    kept = true;
                    if (end == null) {
                        if (seenCurrent.add(member.path("pId").asText())) {
                            current++;
                        }
                    } else {
                        former++;
                    }
                }
                if (!kept) {
                    skipped++;
                }
            }
        }
        if (current == 0) {
            throw new IllegalStateException("Aucun membre en fonction dans " + rawDir + " : fichiers incomplets ou format changé.");
        }
        return new Report(files.size(), current, former, skipped, offices);
    }

    /** Écrit la personne, son siège et ses fonctions de la période ; retourne le nombre de fonctions. */
    private static int write(JsonNode member, JsonNode membership, LocalDate cutoff, FtmDatasetBuilder dataset) {
        String id = member.path("pId").asText();
        String name = member.path("fullName").asText();
        JsonNode house = membership.path("house");
        String code = house.path("houseCode").asText();
        boolean dail = code.equals("dail");
        String seatLabel = dail ? "Teachta Dála (Member of Dáil Éireann)" : "Senator (Member of Seanad Éireann)";
        LocalDate start = date(membership.path("dateRange").path("start"));
        LocalDate end = date(membership.path("dateRange").path("end"));

        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", name);
        person.addValue("firstName", member.path("firstName").asText(null));
        person.addValue("lastName", member.path("lastName").asText(null));
        person.addValue("country", "ie");
        person.addValue("position", seatLabel);
        person.addValue("sourceUrl", member.path("uri").asText(null));
        dataset.addEntity(person);

        String party = null;
        for (JsonNode p : membership.path("parties")) {
            party = p.path("party").path("showAs").asText(party);
        }
        String represents = null;
        for (JsonNode r : membership.path("represents")) {
            represents = r.path("represent").path("showAs").asText(represents);
        }
        List<String> parts = new ArrayList<>();
        parts.add(house.path("showAs").asText());
        if (represents != null) {
            parts.add((dail ? "Circonscription : " : "Panel : ") + represents);
        }
        if (party != null) {
            parts.add("Parti : " + party);
        }

        FtmEntity seat = dataset.newEntity("Position", dail ? "dail" : "seanad");
        seat.addValue("name", seatLabel);
        seat.addValue("country", "ie");
        seat.addValue("organization", dail ? "Dáil Éireann" : "Seanad Éireann");
        seat.addValue("topics", "gov.national");
        seat.addValue("topics", "gov.legislative");
        dataset.addEntity(seat);

        FtmEntity occupancy = dataset.newEntity("Occupancy", id, code, house.path("houseNo").asText());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", seat.getId());
        occupancy.addValue("startDate", start == null ? null : start.toString());
        occupancy.addValue("endDate", end == null ? null : end.toString());
        occupancy.addValue("status", end == null ? "current" : "ended");
        occupancy.addValue("description", String.join(" ; ", parts));
        occupancy.addValue("sourceUrl", membership.path("uri").asText(null));
        dataset.addEntity(occupancy);

        int offices = 0;
        for (JsonNode o : membership.path("offices")) {
            JsonNode office = o.path("office");
            String label = office.path("officeName").path("showAs").asText("");
            LocalDate oStart = date(office.path("dateRange").path("start"));
            LocalDate oEnd = date(office.path("dateRange").path("end"));
            if (label.isBlank() || (oEnd != null && oEnd.isBefore(cutoff))) {
                continue;
            }
            person.addValue("position", label);
            FtmEntity post = dataset.newEntity("Position", "office", label);
            post.addValue("name", label);
            post.addValue("country", "ie");
            post.addValue("organization", officeBody(label, dail));
            for (String topic : officeTopics(label)) {
                post.addValue("topics", topic);
            }
            dataset.addEntity(post);
            FtmEntity held = dataset.newEntity("Occupancy", id, "office", label, String.valueOf(oStart));
            held.addValue("holder", person.getId());
            held.addValue("post", post.getId());
            held.addValue("startDate", oStart == null ? null : oStart.toString());
            held.addValue("endDate", oEnd == null ? null : oEnd.toString());
            held.addValue("status", oEnd == null ? "current" : "ended");
            held.addValue("sourceUrl", membership.path("uri").asText(null));
            dataset.addEntity(held);
            offices++;
        }
        return offices;
    }

    /** Thèmes FtM d'une fonction : chef du Gouvernement, ministre ou président de chambre. */
    static List<String> officeTopics(String label) {
        String l = label.toLowerCase();
        if (l.startsWith("taoiseach")) {
            return List.of("gov.national", "gov.executive", "gov.head");
        }
        if (l.startsWith("minister") || l.startsWith("tánaiste") || l.startsWith("attorney")) {
            return List.of("gov.national", "gov.executive");
        }
        if (l.contains("comhairle") || l.contains("cathaoirleach") || l.contains("chathaoirleach") || l.contains("whip")) {
            return List.of("gov.national", "gov.legislative");
        }
        return List.of("gov.national");
    }

    private static String officeBody(String label, boolean dail) {
        String l = label.toLowerCase();
        if (l.startsWith("taoiseach") || l.startsWith("minister") || l.startsWith("tánaiste") || l.startsWith("attorney")) {
            return "Government of Ireland";
        }
        return dail ? "Dáil Éireann" : "Seanad Éireann";
    }

    private static LocalDate date(JsonNode n) {
        if (n == null || n.isNull() || n.asText().isBlank()) {
            return null;
        }
        return LocalDate.parse(n.asText().substring(0, 10));
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
        return v == null || v.isBlank() ? 1000 : Long.parseLong(v.trim());
    }

    private static Fetcher httpFetcher(long delayMs) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return url -> {
            Thread.sleep(delayMs);
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(3)).header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
