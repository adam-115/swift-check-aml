package com.swiftcheck.aml.plugins.bepep.king;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <b>be_pep_king</b> – le Roi des Belges (catégorie BE-a ; loi du 18 septembre 2017, art. 4, 28°, a), Annexe IV,
 * 1°, a)).
 *
 * <p>Deux sources, croisées à chaque exécution et déposées dans {@code rawDir} :
 * <ul>
 *   <li><b>monarchie.be</b>, page « Le Roi » du Palais royal : la naissance, qui fait foi (« Sa Majesté le Roi est
 *       né à Bruxelles le 15 avril 1960 ») ;</li>
 *   <li><b>Wikidata</b> : le titulaire actuel de « Roi des Belges » (Q13592862), sans date de fin, avec son nom et
 *       sa date d'accession (P580).</li>
 * </ul>
 * La page officielle ne donne pas le nom dans un champ lisible ; Wikidata est donc contrôlé par elle : le prénom et
 * l'année de naissance trouvés dans Wikidata doivent y figurer, sinon l'exécution s'arrête (changement de règne non
 * encore saisi, ou page modifiée). Paramètres :
 * <pre>{@code
 * {"pageUrl": "https://www.monarchie.be/fr/famille-royale/le-roi", "endpoint": "https://query.wikidata.org/sparql"}
 * }</pre>
 * Pas de photo : l'image Wikidata est une photo de visite officielle, pas un portrait seul.
 */
@Extension
public class BePepKingParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "be-pep-king";
    static final String TOPIC = "role.pep";

    static final String DEFAULT_PAGE_URL = "https://www.monarchie.be/fr/famille-royale/le-roi";
    static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    static final String PAGE_FILE = "monarchie-le-roi.html";
    static final String WIKIDATA_FILE = "wikidata-roi.json";
    /** Numéro du Moniteur belge de l'avènement (21 juillet 2013) : source primaire qui confirme le Roi. */
    static final String DEFAULT_MONITEUR_URL = "https://www.ejustice.just.fgov.be/mopdf/2013/07/21_1.pdf";
    static final String MONITEUR_FILE = "moniteur-2013-07-21.pdf";
    static final String POSITION = "Roi des Belges";
    static final String KING_QID = "Q13592862";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - Roi des Belges)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BIRTH = Pattern.compile("est né à ([\\p{L}' -]+?) le (\\d{1,2})(?:er)? (\\p{L}+) (\\d{4})");
    private static final Map<String, String> MONTHS = Map.ofEntries(Map.entry("janvier", "01"), Map.entry("février", "02"),
            Map.entry("mars", "03"), Map.entry("avril", "04"), Map.entry("mai", "05"), Map.entry("juin", "06"),
            Map.entry("juillet", "07"), Map.entry("août", "08"), Map.entry("septembre", "09"),
            Map.entry("octobre", "10"), Map.entry("novembre", "11"), Map.entry("décembre", "12"));

    static final String QUERY = """
            SELECT ?king ?kingLabel ?birth ?start WHERE {
              ?king p:P39 ?held . ?held ps:P39 wd:%s .
              OPTIONAL { ?held pq:P580 ?start }
              FILTER NOT EXISTS { ?held pq:P582 ?end }
              FILTER NOT EXISTS { ?king wdt:P570 ?death }
              OPTIONAL { ?king wdt:P569 ?birth }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "fr,en". }
            }""".formatted(KING_QID);

    /** Le Roi : QID Wikidata, nom, accession ; naissance lue sur monarchie.be. */
    record King(String qid, String name, String start, String birthDate, String birthPlace) {
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Files.createDirectories(ctx.rawDir());
        String pageUrl = param(p, "pageUrl", DEFAULT_PAGE_URL);
        Files.write(ctx.rawDir().resolve(PAGE_FILE), get(pageUrl, "text/html,*/*;q=0.8"));
        Files.write(ctx.rawDir().resolve(MONITEUR_FILE), get(param(p, "moniteurUrl", DEFAULT_MONITEUR_URL), "application/pdf,*/*;q=0.8"));
        Files.write(ctx.rawDir().resolve(WIKIDATA_FILE), get(param(p, "endpoint", DEFAULT_ENDPOINT) + "?query="
                + URLEncoder.encode(QUERY, StandardCharsets.UTF_8), "application/sparql-results+json"));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        King king = convert(ctx.rawDir(), pageUrl, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage("Roi des Belges : " + king.name() + ", en fonction depuis le "
                + king.start() + " (Wikidata, contrôlé par monarchie.be et par le Moniteur belge).");
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        King king = convert(ctx.rawDir(), param(p, "pageUrl", DEFAULT_PAGE_URL), dataset);
        return List.of(new SourceRecord(dataset.buildEntityId(king.qid()), "Person", king.name(),
                param(p, "pageUrl", DEFAULT_PAGE_URL)));
    }

    /** Lit les deux fichiers de {@code rawDir}, les croise et écrit le Roi. Séparé de {@link #parse} pour les tests. */
    King convert(Path rawDir, String pageUrl, FtmDatasetBuilder dataset) throws IOException {
        String page = text(Files.readString(rawDir.resolve(PAGE_FILE), StandardCharsets.UTF_8));
        JsonNode bindings = JSON.readTree(rawDir.resolve(WIKIDATA_FILE).toFile()).path("results").path("bindings");
        if (bindings.size() != 1) {
            throw new IllegalStateException("Wikidata : " + bindings.size() + " Rois des Belges en fonction au lieu "
                    + "d'un. Changement de règne en cours de saisie : vérifier Wikidata.");
        }
        JsonNode b = bindings.get(0);
        String qid = b.path("king").path("value").asText().replaceFirst(".*/", "");
        String name = b.path("kingLabel").path("value").asText();
        String start = date(b.path("start").path("value").asText(null));
        String wikidataBirth = date(b.path("birth").path("value").asText(null));

        Matcher birth = BIRTH.matcher(page);
        if (!birth.find()) {
            throw new IllegalStateException("Naissance du Roi introuvable sur " + pageUrl + " : la page a changé.");
        }
        String month = MONTHS.get(birth.group(3).toLowerCase(Locale.FRENCH));
        String birthDate = birth.group(4) + "-" + month + "-" + (birth.group(2).length() == 1 ? "0" : "") + birth.group(2);
        String firstName = name.split("\\s+")[0];
        if (!page.contains(firstName) || !birthDate.equals(wikidataBirth)) {
            throw new IllegalStateException("Wikidata (" + name + ", né le " + wikidataBirth + ") ne correspond pas à "
                    + "la page officielle (né le " + birthDate + ") : vérifier un changement de règne.");
        }
        Path moniteur = rawDir.resolve(MONITEUR_FILE);
        if (!Files.exists(moniteur)) {
            throw new IllegalStateException(MONITEUR_FILE + " absent de " + rawDir + " : le Moniteur belge n'a pas été téléchargé.");
        }
        MoniteurCheck.verify(MoniteurCheck.pdfText(moniteur), firstName, start, DEFAULT_MONITEUR_URL);
        King king = new King(qid, name, start, birthDate, birth.group(1).trim());

        FtmEntity person = dataset.newEntity("Person", qid);
        person.addValue("name", name);
        person.addValue("alias", "Roi " + firstName);
        person.addValue("firstName", firstName);
        person.addValue("birthDate", king.birthDate());
        person.addValue("birthPlace", king.birthPlace());
        person.addValue("gender", "male");
        person.addValue("country", "be");
        person.addValue("nationality", "be");
        person.addValue("position", POSITION);
        person.addValue("wikidataId", qid);
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "king");
        position.addValue("name", POSITION);
        position.addValue("country", "be");
        position.addValue("organization", "Maison royale");
        position.addValue("topics", "gov.head");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", qid, "king");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", start);
        occupancy.addValue("status", "current");
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
        return king;
    }

    private static String date(String raw) {
        return raw == null || raw.length() < 10 ? null : raw.substring(0, 10);
    }

    /** Texte de la page : sans balises ni entités, espaces réduits. */
    static String text(String html) {
        return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&#039;", "'").replace("&rsquo;", "’")
                .replace(' ', ' ').replaceAll("\\s+", " ");
    }

    private static String param(Map<String, String> p, String key, String fallback) {
        String value = p.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static byte[] get(String url, String accept) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", USER_AGENT).header("Accept", accept).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " : " + url);
        }
        return response.body();
    }
}
