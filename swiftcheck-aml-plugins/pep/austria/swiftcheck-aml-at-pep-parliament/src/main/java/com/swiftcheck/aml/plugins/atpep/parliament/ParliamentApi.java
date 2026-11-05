package com.swiftcheck.aml.plugins.atpep.parliament;

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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Open data du Parlement autrichien (licence CC BY 4.0, source : Parlamentsdirektion).
 *
 * <p>Deux jeux sont lus par l'interface JSON publique du Parlement :
 * <ul>
 *   <li>la liste de toutes les personnes ayant siégé au Nationalrat ou au Bundesrat depuis 1918, avec leurs mandats
 *       (dates de début et de fin, groupe parlementaire, Land) : {@code POST /Filter/api/filter/data/409} ;</li>
 *   <li>la fiche JSON de chaque personne ({@code /person/<id>?json=true}) : date et lieu de naissance, date de décès.</li>
 * </ul>
 * Les réponses sont déposées telles quelles dans {@code rawDir}. Aucune coordonnée personnelle (adresse, téléphone,
 * courriel, réseaux sociaux) n'est lue.
 */
final class ParliamentApi {

    private ParliamentApi() {
    }

    static final String LIST_FILE_PREFIX = "list-";
    static final String PERSON_DIR = "persons";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Accès réseau, remplaçable dans les tests. */
    interface Fetcher {
        /** Corps de la réponse ; lève {@link IOException} si le code HTTP n'est pas 200. */
        String post(String url, String jsonBody) throws IOException, InterruptedException;

        String get(String url) throws IOException, InterruptedException;
    }

    /** Un mandat d'une personne dans un des deux organes. */
    record Mandate(String body, String start, String end, String group, String region) {
    }

    /** Une personne de la liste : identifiant du Parlement, nom (« Nom Prénom, titres »), mandats. */
    record Member(String id, String listName, List<Mandate> mandates) {
    }

    /** La fiche d'une personne : nom d'usage, naissance, décès. */
    record Detail(String name, String birthDate, String birthPlace, String deathDate) {
    }

    /** Organe lu : nom du jeu de filtres du Parlement et libellé court. */
    record Body(String key, String gremium) {
    }

    static final Body NATIONALRAT = new Body("nationalrat", "Nationalrat");
    static final Body BUNDESRAT = new Body("bundesrat", "Bundesrat");

    /** Télécharge les deux listes dans {@code rawDir}. */
    static void downloadLists(Fetcher fetcher, String listUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        for (Body body : List.of(NATIONALRAT, BUNDESRAT)) {
            String request = "{\"GESCHL_CODE\":[\"M\",\"W\"],\"ATTR_JSON.mandate_detail.gremium_name\":[\""
                    + body.gremium() + "\"]}";
            String response = fetcher.post(listUrl, request);
            JsonNode root = JSON.readTree(response);
            if (root.path("rows").size() == 0) {
                throw new IllegalStateException("Liste du " + body.gremium() + " vide ou format changé : "
                        + (root.has("errorMsg") ? root.path("errorMsg").asText() : "pas de « rows »") + ".");
            }
            Files.writeString(rawDir.resolve(LIST_FILE_PREFIX + body.key() + ".json"), response, StandardCharsets.UTF_8);
        }
    }

    /** Télécharge la fiche d'une personne (un échec isolé est signalé, pas fatal). */
    static boolean downloadPerson(Fetcher fetcher, String personUrl, String id, Path rawDir, long delayMs)
            throws InterruptedException {
        Path file = rawDir.resolve(PERSON_DIR).resolve(id + ".json");
        try {
            Files.createDirectories(file.getParent());
            String response = fetcher.get(personUrl.replace("{id}", id));
            Files.writeString(file, response, StandardCharsets.UTF_8);
            Thread.sleep(delayMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Les personnes d'un organe, lues dans le fichier de liste de {@code rawDir}. */
    static List<Member> members(Path rawDir, Body body) throws IOException {
        Path file = rawDir.resolve(LIST_FILE_PREFIX + body.key() + ".json");
        JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<Member> out = new ArrayList<>();
        for (JsonNode row : root.path("rows")) {
            JsonNode attributes = row.get(5);
            String uri = attributes == null ? "" : attributes.path("uri").asText("");
            String id = uri.replaceAll("^.*/person/", "");
            if (id.isBlank() || !id.matches("\\d+")) {
                continue;
            }
            List<Mandate> mandates = new ArrayList<>();
            for (JsonNode m : attributes.path("mandate_detail")) {
                if (!body.gremium().equals(m.path("gremium_name").asText())) {
                    continue;
                }
                String start = isoDate(m.path("mandat_von").asText(null));
                if (start == null) {
                    continue;
                }
                String group = m.path("fraktion").asText(null);
                String region = m.path("wahlkreis_bundesland").asText(null);
                mandates.add(new Mandate(body.gremium(), start, isoDate(m.path("mandat_bis").asText(null)),
                        blankToNull(group), blankToNull(region)));
            }
            out.add(new Member(id, row.get(0).asText(), mandates));
        }
        return out;
    }

    /** La fiche d'une personne, ou {@code null} si elle n'a pas été téléchargée. */
    static Detail detail(Path rawDir, String id) throws IOException {
        Path file = rawDir.resolve(PERSON_DIR).resolve(id + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return parseDetail(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static final Pattern BIRTH = Pattern.compile("Geb\\.:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}|\\d{2}\\.\\d{4}|\\d{4})(?:,\\s*(.+))?");
    private static final Pattern DEATH = Pattern.compile("Verst\\.:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}|\\d{2}\\.\\d{4}|\\d{4})");

    static Detail parseDetail(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        String name = cleanName(root.path("content").path("headingbox").path("title_plain").asText(null));
        JsonNode bio = findText(root, "gebtext");
        String birthText = bio == null ? null : bio.asText();
        String birthDate = null;
        String birthPlace = null;
        if (birthText != null) {
            Matcher m = BIRTH.matcher(birthText);
            if (m.find()) {
                birthDate = isoPartial(m.group(1));
                birthPlace = m.group(2) == null ? null : m.group(2).trim();
            }
        }
        JsonNode death = findText(root, "sterbtext");
        String deathDate = null;
        if (death != null) {
            Matcher m = DEATH.matcher(death.asText());
            deathDate = m.find() ? isoPartial(m.group(1)) : "unknown";
        }
        return new Detail(name, birthDate, birthPlace, deathDate);
    }

    /** « Mag. Elke Hanel-Torsch » → « Elke Hanel-Torsch » ; « Markus Stotter, BA » → « Markus Stotter » (titres académiques écartés). */
    static String cleanName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String[] tokens = displayName.split(",")[0].trim().split("\\s+");
        int from = 0;
        while (from < tokens.length - 1 && (tokens[from].contains(".") || tokens[from].startsWith("("))) {
            from++;
        }
        return blankToNull(String.join(" ", java.util.Arrays.copyOfRange(tokens, from, tokens.length)));
    }

    /** Premier nœud nommé {@code field} dans l'arbre (la fiche range la naissance dans un bloc de profondeur variable). */
    private static JsonNode findText(JsonNode node, String field) {
        if (node.has(field) && node.get(field).isTextual() && !node.get(field).asText().isBlank()) {
            return node.get(field);
        }
        for (JsonNode child : node) {
            JsonNode found = findText(child, field);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** « 07.11.2025 » → « 2025-11-07 » ; absent ou vide → {@code null}. */
    static String isoDate(String dmy) {
        if (dmy == null || dmy.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dmy.trim(), DMY).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** « 13.09.1989 » → « 1989-09-13 », « 09.1989 » → « 1989-09 », « 1989 » → « 1989 ». */
    static String isoPartial(String text) {
        String[] parts = text.split("\\.");
        return switch (parts.length) {
            case 3 -> parts[2] + "-" + parts[1] + "-" + parts[0];
            case 2 -> parts[1] + "-" + parts[0];
            default -> parts[0];
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Client HTTP : User-Agent générique, nouvelle tentative lente sur 429/503, aucune protection contournée. */
    static Fetcher httpFetcher(String userAgent) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return new Fetcher() {
            @Override
            public String post(String url, String body) throws IOException, InterruptedException {
                return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
                        .header("User-Agent", userAgent).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
            }

            @Override
            public String get(String url) throws IOException, InterruptedException {
                return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", userAgent).GET().build());
            }

            private String send(HttpRequest request) throws IOException, InterruptedException {
                for (int attempt = 1; ; attempt++) {
                    HttpResponse<String> response = client.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int code = response.statusCode();
                    if (code == 200) {
                        return response.body();
                    }
                    if ((code == 429 || code == 503) && attempt < 4) {
                        Thread.sleep(Math.min(response.headers().firstValueAsLong("Retry-After").orElse(10L * attempt),
                                120L) * 1000L);
                        continue;
                    }
                    throw new IOException("HTTP " + code + " pour " + request.uri());
                }
            }
        };
    }
}
