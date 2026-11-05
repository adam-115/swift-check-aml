package com.swiftcheck.aml.plugins.frpep.stateenterprises;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <b>fr_pep_state_enterprises</b> – dirigeants et administrateurs des entreprises publiques de l'État (catégorie
 * FR-g : directive (UE) 2015/849, art. 3, point 9 g) ; CMF, art. R. 561-18).
 *
 * <p>Périmètre, relu à chaque exécution :
 * <ul>
 *   <li>les entreprises de la page « Les entreprises de l'APE » (economie.gouv.fr). La page ne donne que des noms :
 *       leur SIREN vient de {@code ape-entreprises.json}. Un nom inconnu de ce fichier est signalé, jamais deviné ;
 *       une entreprise retirée de la page disparaît du dataset ;</li>
 *   <li>les établissements publics nationaux à caractère industriel et commercial (natures juridiques 4110 et 4120),
 *       listés par l'API Recherche d'entreprises.</li>
 * </ul>
 * Les dirigeants viennent de l'API Recherche d'entreprises (registre national des entreprises, Licence ouverte).
 * Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"apeUrl": "https://www.economie.gouv.fr/agence-participations-etat/comprendre-lape/les-entreprises-de-lape",
 *  "apiUrl": "https://recherche-entreprises.api.gouv.fr/search", "natures": "4110,4120", "includeSingleShare": "false"}
 * }</pre>
 * {@code includeSingleShare} : reprendre aussi les entités où l'État ne détient qu'une action (non contrôlées).
 */
@Extension
public class FrPepStateEnterprisesParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-state-enterprises";

    static final String DEFAULT_APE_URL =
            "https://www.economie.gouv.fr/agence-participations-etat/comprendre-lape/les-entreprises-de-lape";
    static final String DEFAULT_API_URL = "https://recherche-entreprises.api.gouv.fr/search";
    static final String DEFAULT_NATURES = "4110,4120";
    static final String APE_FILE = "ape.html";
    static final String POSITION = "Dirigeant ou administrateur d'un établissement ou d'une société publique de l'État";

    /** En dessous, la page a changé de format : mieux vaut s'arrêter que publier une liste tronquée. */
    static final int MIN_LISTED = 30;

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - entreprises publiques)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Entrée de {@code ape-entreprises.json}. */
    record Known(List<String> names, List<String> sirens, String reason, boolean singleShare) {
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(String perimeter, int apeListed, int apeCompanies, List<String> apeWithoutSiren,
            List<String> apeUnknown, int epics, List<String> withoutLeaders, int leaders, int persons) {

        String message() {
            StringBuilder m = new StringBuilder(perimeter).append(' ').append(String.format(
                    "%d dirigeants et administrateurs (%d personnes) : %d entreprises de l'APE sur %d listées, %d EPIC "
                            + "nationaux.", leaders, persons, apeCompanies, apeListed, epics));
            if (!apeWithoutSiren.isEmpty()) {
                m.append(" Sans SIREN français : ").append(String.join(" ; ", apeWithoutSiren)).append('.');
            }
            if (!apeUnknown.isEmpty()) {
                m.append(" Noms nouveaux sur la page APE, à ajouter dans ape-entreprises.json : ")
                        .append(String.join(" ; ", apeUnknown)).append('.');
            }
            if (!withoutLeaders.isEmpty()) {
                m.append(String.format(" %d entreprises sans dirigeant dans le registre : %s.", withoutLeaders.size(),
                        String.join(" ; ", withoutLeaders.subList(0, Math.min(10, withoutLeaders.size())))
                                + (withoutLeaders.size() > 10 ? "…" : "")));
            }
            return m.toString();
        }
    }

    /** Entreprise retenue : sa fiche, son libellé et son contexte. */
    private record Retained(JsonNode company, String label, String description) {
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

    /** Dépose dans {@code rawDir} la page APE, la fiche de chacune de ses entreprises et les listes d'EPIC. */
    static void download(CompanyRegistry.Fetcher fetcher, Map<String, String> p, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String apeUrl = param(p, "apeUrl", DEFAULT_APE_URL);
        Files.deleteIfExists(rawDir.resolve(APE_FILE));
        // Le périmètre vient uniquement de la page de l'APE, lue à chaque exécution : si elle ne répond pas, l'exécution
        // échoue (aucune liste de secours embarquée). Le fichier ape-entreprises.json ne sert qu'à relier un libellé de la
        // page à son SIREN.
        Files.write(rawDir.resolve(APE_FILE), fetcher.get(apeUrl));
        String api = param(p, "apiUrl", DEFAULT_API_URL);
        Map<String, Known> known = known();
        boolean singleShare = Boolean.parseBoolean(param(p, "includeSingleShare", "false"));
        Set<String> sirens = new LinkedHashSet<>();
        for (ApePage.Listed listed : listed(rawDir)) {
            Known k = known.get(ApePage.fold(listed.name()));
            if (k != null && (singleShare || !listed.singleShare())) {
                sirens.addAll(k.sirens());
            }
        }
        for (String siren : sirens) {
            CompanyRegistry.downloadSiren(fetcher, api, siren, rawDir);
        }
        for (String nature : natures(p)) {
            CompanyRegistry.downloadFilter(fetcher, api, "nature-" + nature, "nature_juridique=" + nature, rawDir);
        }
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entreprises et leurs dirigeants. Séparé de {@link #parse}. */
    Report convert(Path rawDir, Map<String, String> p, FtmDatasetBuilder dataset) throws IOException {
        List<ApePage.Listed> listed = listed(rawDir);
        String perimeter = "Périmètre : page de l'APE.";
        boolean singleShare = Boolean.parseBoolean(param(p, "includeSingleShare", "false"));
        Map<String, Known> known = known();

        Map<String, Retained> retained = new LinkedHashMap<>();
        List<String> withoutSiren = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<Known> seen = new java.util.HashSet<>();
        int apeListed = 0;
        for (ApePage.Listed l : listed) {
            if (l.singleShare() && !singleShare) {
                continue;
            }
            Known k = known.get(ApePage.fold(l.name()));
            if (k == null) {
                apeListed++;
                unknown.add(l.name());
                continue;
            }
            if (!seen.add(k)) {
                continue;               // nom coupé en deux lignes sur la page (« Société aéroportuaire… », « Pôle Caraïbes »)
            }
            apeListed++;
            if (k.sirens().isEmpty()) {
                withoutSiren.add(l.name() + " (" + k.reason() + ")");
                continue;
            }
            for (String siren : k.sirens()) {
                JsonNode company = CompanyRegistry.readSiren(rawDir, siren);
                if (company == null) {
                    withoutSiren.add(l.name() + " (SIREN " + siren + " introuvable dans le registre)");
                } else if (!retained.containsKey(siren)) {
                    String label = k.sirens().size() == 1 ? k.names().get(0) : null;
                    retained.put(siren, new Retained(company, label, "Portefeuille de l'Agence des participations de "
                            + "l'État – " + l.sector() + (l.singleShare() ? " (l'État ne détient qu'une action)" : "")));
                }
            }
        }
        int apeCompanies = retained.size();

        int epics = 0;
        for (String nature : natures(p)) {
            for (Map.Entry<String, JsonNode> e : CompanyRegistry.readFilter(rawDir, "nature-" + nature).entrySet()) {
                epics++;
                retained.putIfAbsent(e.getKey(), new Retained(e.getValue(), null,
                        "Établissement public national à caractère industriel ou commercial"));
            }
        }

        int leaders = 0;
        List<String> withoutLeaders = new ArrayList<>();
        for (Retained r : retained.values()) {
            int n = Leaders.write(r.company(), r.label(), r.description(), POSITION, dataset);
            leaders += n;
            if (n == 0) {
                withoutLeaders.add(r.label() != null ? r.label() : r.company().path("nom_complet").asText());
            }
        }
        int persons = (int) dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        return new Report(perimeter, apeListed, apeCompanies, withoutSiren, unknown, epics, withoutLeaders, leaders, persons);
    }

    /** Entreprises de la page de l'APE téléchargée dans {@code rawDir}. */
    static List<ApePage.Listed> listed(Path rawDir) throws IOException {
        Path page = rawDir.resolve(APE_FILE);
        if (Files.exists(page)) {
            List<ApePage.Listed> listed = ApePage.read(Files.readString(page, StandardCharsets.UTF_8));
            if (listed.size() < MIN_LISTED) {
                throw new IllegalStateException("Page de l'APE : " + listed.size() + " entreprises lues, au moins "
                        + MIN_LISTED + " attendues. Le format de la page a probablement changé.");
            }
            return listed;
        }
        throw new IllegalStateException(APE_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
    }

    /** {@code ape-entreprises.json}, indexé par nom replié (voir {@link ApePage#fold}). */
    static Map<String, Known> known() throws IOException {
        Map<String, Known> byName = new HashMap<>();
        for (Known k : knownList()) {
            k.names().forEach(n -> byName.put(ApePage.fold(n), k));
        }
        return byName;
    }

    /** Entrées de {@code ape-entreprises.json}, dans l'ordre du fichier. */
    static List<Known> knownList() throws IOException {
        List<Known> list = new ArrayList<>();
        try (InputStream in = FrPepStateEnterprisesParser.class.getResourceAsStream("/ape-entreprises.json")) {
            if (in == null) {
                throw new IllegalStateException("ape-entreprises.json absent du plugin.");
            }
            for (JsonNode e : JSON.readTree(in).path("entreprises")) {
                List<String> names = new ArrayList<>();
                e.path("names").forEach(n -> names.add(n.asText()));
                List<String> sirens = new ArrayList<>();
                e.path("sirens").forEach(s -> sirens.add(s.asText()));
                list.add(new Known(names, sirens, e.path("reason").asText("sans SIREN"),
                        e.path("singleShare").asBoolean(false)));
            }
        }
        return list;
    }

    static List<String> natures(Map<String, String> p) {
        List<String> natures = new ArrayList<>();
        for (String n : param(p, "natures", DEFAULT_NATURES).split(",")) {
            if (!n.isBlank()) {
                natures.add(n.trim());
            }
        }
        return natures;
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
            if (url.contains("economie.gouv.fr")) {
                try {
                    return com.swiftcheck.aml.sdk.StealthBrowserFetcher.fetchBytes(url);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Téléchargement interrompu : " + url, e);
                }
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", url.contains("recherche-entreprises") ? "application/json" : "text/html,*/*;q=0.8")
                    .GET().build();
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
