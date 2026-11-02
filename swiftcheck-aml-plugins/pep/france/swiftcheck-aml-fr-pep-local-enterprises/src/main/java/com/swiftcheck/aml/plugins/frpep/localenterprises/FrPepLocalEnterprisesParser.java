package com.swiftcheck.aml.plugins.frpep.localenterprises;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_local_enterprises</b> – dirigeants et administrateurs des sociétés contrôlées par une collectivité
 * (catégorie FR-g, fonction « Dirigeant ou administrateur d'une société contrôlée par une collectivité »).
 *
 * <p>Périmètre, lu dans l'API Recherche d'entreprises (registre national des entreprises et Sirene, Licence ouverte),
 * entreprises actives seulement :
 * <ul>
 *   <li>sociétés d'économie mixte locales et SEMOP (natures juridiques 5515 et 5615) ;</li>
 *   <li>sociétés publiques locales : pas de nature juridique propre (SA 5599) ; retenues quand leur nom dit
 *       « société publique locale » ou « SPL » ;</li>
 *   <li>établissements publics locaux à caractère industriel ou commercial et régies dotées de la personnalité morale
 *       (4140 et 4150).</li>
 * </ul>
 * Seules sont gardées les entités au-dessus d'un seuil : chiffre d'affaires du dernier exercice publié supérieur à
 * {@code minRevenue} ; sans comptes publiés, catégorie d'entreprise Insee dans {@code sizeFallback} (ETI, GE).
 * Paramètres :
 * <pre>{@code
 * {"apiUrl": "https://recherche-entreprises.api.gouv.fr/search", "natures": "5515,5615,4140,4150",
 *  "splQueries": "societe publique locale,SPL", "minRevenue": "50000000", "sizeFallback": "ETI,GE"}
 * }</pre>
 */
@Extension
public class FrPepLocalEnterprisesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-local-enterprises";

    static final String DEFAULT_API_URL = "https://recherche-entreprises.api.gouv.fr/search";
    static final String DEFAULT_NATURES = "5515,5615,4140,4150";
    static final String DEFAULT_SPL_QUERIES = "societe publique locale,SPL";
    static final long DEFAULT_MIN_REVENUE = 50_000_000L;
    static final String DEFAULT_SIZE_FALLBACK = "ETI,GE";
    static final String SPL_NATURE = "5599";
    static final String POSITION = "Dirigeant ou administrateur d'une société contrôlée par une collectivité";

    /**
     * Nom d'une société publique locale (ou d'aménagement) : « SOCIETE PUBLIQUE LOCALE … », « SOCIETE ANONYME PUBLIQUE
     * LOCALE … », « SPL … », « S.P.L ». « LOCAL » sans E existe au registre (« SOCIETE PUBLIQUE LOCAL U SANT'ANGHJULU »).
     * Une société dont le nom ne le dit pas (« EAU DU PONANT ») n'est pas retenue : rien ne prouve que c'est une SPL.
     */
    static final Pattern SPL_NAME = Pattern.compile("\\bPUBLIQUE LOCALE?\\b|\\bSPLA?\\b|\\bS\\.P\\.L\\b");

    static final Map<String, String> NATURE_LABELS = Map.of(
            "5515", "Société d'économie mixte",
            "5615", "Société d'économie mixte à directoire",
            "4140", "Établissement public local à caractère industriel ou commercial",
            "4150", "Régie d'une collectivité locale à caractère industriel ou commercial",
            SPL_NATURE, "Société publique locale");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - entreprises publiques locales)";

    /** Bilan, pour le message de l'exécution. */
    record Report(int candidates, int retained, int byRevenue, int bySize, long minRevenue,
            Map<String, Integer> byNature, List<String> withoutLeaders, int leaders, int persons) {

        String message() {
            StringBuilder m = new StringBuilder(String.format(
                    "%d dirigeants et administrateurs (%d personnes) dans %d entités sur %d actives : %d au-dessus de "
                            + "%d M€ de chiffre d'affaires, %d ETI ou grandes entreprises sans comptes publiés.",
                    leaders, persons, retained, candidates, byRevenue, minRevenue / 1_000_000, bySize));
            List<String> natures = new ArrayList<>();
            byNature.forEach((label, n) -> natures.add(label + " : " + n));
            m.append(" Par type : ").append(String.join(", ", natures)).append('.');
            if (!withoutLeaders.isEmpty()) {
                m.append(String.format(" %d sans dirigeant au registre.", withoutLeaders.size()));
            }
            return m.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        download(httpFetcher(), p, ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), p, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque dirigeant, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(ctx.rawDir(), p, dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("position"))));
            }
        }
        return records;
    }

    /** Dépose dans {@code rawDir/companies} les listes de l'API : une série de pages par nature et par recherche SPL. */
    static void download(CompanyRegistry.Fetcher fetcher, Map<String, String> p, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String api = param(p, "apiUrl", DEFAULT_API_URL);
        for (String nature : list(param(p, "natures", DEFAULT_NATURES))) {
            CompanyRegistry.downloadFilter(fetcher, api, "nature-" + nature, "nature_juridique=" + nature, rawDir);
        }
        List<String> queries = list(param(p, "splQueries", DEFAULT_SPL_QUERIES));
        for (int i = 0; i < queries.size(); i++) {
            CompanyRegistry.downloadFilter(fetcher, api, "spl" + i, "nature_juridique=" + SPL_NATURE + "&q="
                    + URLEncoder.encode(queries.get(i), StandardCharsets.UTF_8), rawDir);
        }
    }

    /** Lit les listes de {@code rawDir}, applique le seuil et écrit les entités. Séparé de {@link #parse}. */
    Report convert(Path rawDir, Map<String, String> p, FtmDatasetBuilder dataset) throws IOException {
        Map<String, JsonNode> candidates = new LinkedHashMap<>();
        for (String nature : list(param(p, "natures", DEFAULT_NATURES))) {
            candidates.putAll(CompanyRegistry.readFilter(rawDir, "nature-" + nature));
        }
        int queries = list(param(p, "splQueries", DEFAULT_SPL_QUERIES)).size();
        for (int i = 0; i < queries; i++) {
            for (Map.Entry<String, JsonNode> e : CompanyRegistry.readFilter(rawDir, "spl" + i).entrySet()) {
                if (SPL_NATURE.equals(e.getValue().path("nature_juridique").asText())
                        && isSplName(e.getValue().path("nom_complet").asText())) {
                    candidates.putIfAbsent(e.getKey(), e.getValue());
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Aucune entité lue dans " + rawDir + " : le téléchargement n'a pas eu lieu "
                    + "ou le format de l'API a changé.");
        }
        long minRevenue = minRevenue(p);
        Set<String> sizes = Set.copyOf(list(param(p, "sizeFallback", DEFAULT_SIZE_FALLBACK)));

        int byRevenue = 0;
        int bySize = 0;
        int leaders = 0;
        Map<String, Integer> byNature = new LinkedHashMap<>();
        List<String> withoutLeaders = new ArrayList<>();
        for (JsonNode company : candidates.values()) {
            Revenue revenue = Revenue.of(company);
            String description;
            if (revenue != null) {
                if (revenue.amount() <= minRevenue) {
                    continue;
                }
                byRevenue++;
                description = String.format(Locale.FRANCE, "chiffre d'affaires %s : %,d M€", revenue.year(),
                        revenue.amount() / 1_000_000);
            } else if (sizes.contains(company.path("categorie_entreprise").asText())) {
                bySize++;
                description = "comptes non publiés, catégorie Insee " + company.path("categorie_entreprise").asText();
            } else {
                continue;
            }
            String nature = NATURE_LABELS.getOrDefault(company.path("nature_juridique").asText(), "Entité locale");
            byNature.merge(nature, 1, Integer::sum);
            int n = Leaders.write(company, null, nature + " contrôlée par une ou plusieurs collectivités ("
                    + description + ")", POSITION, dataset);
            leaders += n;
            if (n == 0) {
                withoutLeaders.add(company.path("nom_complet").asText());
            }
        }
        int persons = (int) dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        return new Report(candidates.size(), byRevenue + bySize, byRevenue, bySize, minRevenue, byNature,
                withoutLeaders, leaders, persons);
    }

    /** Chiffre d'affaires du dernier exercice publié ({@code finances} : un objet par année). */
    record Revenue(String year, long amount) {

        static Revenue of(JsonNode company) {
            Revenue last = null;
            for (Iterator<Map.Entry<String, JsonNode>> it = company.path("finances").fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> year = it.next();
                JsonNode ca = year.getValue().path("ca");
                if (ca.isNumber() && (last == null || year.getKey().compareTo(last.year()) > 0)) {
                    last = new Revenue(year.getKey(), ca.asLong());
                }
            }
            return last;
        }
    }

    static boolean isSplName(String name) {
        String upper = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return SPL_NAME.matcher(upper).find();
    }

    static long minRevenue(Map<String, String> p) {
        String value = param(p, "minRevenue", String.valueOf(DEFAULT_MIN_REVENUE));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « minRevenue » invalide : « " + value + " » (montant en euros attendu).");
        }
    }

    static List<String> list(String csv) {
        List<String> values = new ArrayList<>();
        for (String v : csv.split(",")) {
            if (!v.isBlank()) {
                values.add(v.trim());
            }
        }
        return values;
    }

    private static String param(Map<String, String> p, String key, String fallback) {
        String value = p.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static CompanyRegistry.Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
            // 429 : l'API limite le débit ; on attend avant de reprendre, sans jamais dépasser la limite
            for (int attempt = 1; ; attempt++) {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (response.statusCode() != 429 || attempt == 5) {
                    throw new IOException("HTTP " + response.statusCode() + " : " + url);
                }
                Thread.sleep(2_000L * attempt);
            }
        };
    }
}
