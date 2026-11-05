package com.swiftcheck.aml.plugins.ptpep.parliament;

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
import java.util.Comparator;
import java.util.List;
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
 * <b>pt_pep_parliament</b> – députés de l'Assembleia da República et membres de son bureau (Presidente,
 * Vice-Presidentes, Secretários, Vice-Secretários), d'après les données ouvertes officielles du Parlement portugais
 * (« Dados Abertos » → « Composição de órgãos »).
 *
 * <p>Parcours (aucun contournement, pages publiques) : la page {@code DAComposicaoOrgaos.aspx} liste un dossier par
 * législature ; la page du dossier de la législature donne un lien signé vers {@code OrgaoComposicaoXVII_json.txt}
 * (≈ 5 Mo). Les liens sont signés et changent : ils sont relus à chaque exécution. Le fichier contient, pour le
 * plénaire, toutes les personnes élues de la législature (titulaires, suppléants, démissionnaires), avec l'historique
 * de leur situation ({@code Efetivo}, {@code Suplente}, {@code Suspenso(Eleito)}, {@code Renunciou}…).
 *
 * <p>Règles : est député en fonction toute personne ayant une période {@code Efetivo*} sans date de fin, ou dont le
 * mandat est {@code Suspenso(Eleito)} sans fin (mandat suspendu, par exemple un membre du Gouvernement : repris, la
 * description le dit). Les suppléants qui n'ont jamais siégé et les démissionnaires qui n'ont jamais siégé sont
 * ignorés. Un ancien député est repris pendant {@code monthsAfterEnd} mois (12 par défaut) après la fin de sa
 * dernière période de siège. Les fonctions du bureau ({@code DepCargo}) sont ajoutées avec leurs dates.
 *
 * <p>Type {@code PLUGIN_MANAGED}. Paramètres :
 * <pre>{@code
 * {"pageUrl": "https://www.parlamento.pt/Cidadania/Paginas/DAComposicaoOrgaos.aspx",
 *  "legislature": "XVII", "monthsAfterEnd": "12", "delayMs": "1500"}
 * }</pre>
 * {@code legislature} est facultatif : par défaut, la première (la plus récente) de la page.
 */
@Extension
public class PtPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "pt-pep-parliament";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String ROOT_PAGE = "root-page.html";
    static final String LEGISLATURE_PAGE = "legislature-page.html";
    static final String COMPOSITION = "composicao.json";

    static final String SEAT = "Deputado à Assembleia da República";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (lista PEP Portugal - parlamento)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b[^>]*>");
    private static final Pattern TITLE = Pattern.compile("(?is)\\btitle=\"([^\"]*)\"");
    private static final Pattern HREF = Pattern.compile("(?is)\\bhref=\"([^\"]*)\"");
    private static final Pattern FOLDER = Pattern.compile("^Pasta\\s+([IVXLC]+)\\s+Legislatura$");
    private static final Pattern JSON_FILE = Pattern.compile("^OrgaoComposicao([IVXLC]+)_json\\.txt$");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Une période de situation d'un député. */
    record Period(String kind, LocalDate start, LocalDate end) {
        boolean seated() {
            return kind.startsWith("Efetivo");
        }

        boolean suspendedElected() {
            return kind.startsWith("Suspenso(Eleito)");
        }
    }

    /** Bilan, pour le message de l'exécution. */
    record Report(String legislature, int current, int suspended, int former, int skipped, int officers) {

        String message() {
            return String.format("Législature %s : %d députés en fonction, %d mandats suspendus, %d anciens députés "
                    + "(moins d'un an), %d personnes ignorées (suppléants ou démissionnaires sans siège) ; "
                    + "%d fonctions du bureau.", legislature, current, suspended, former, skipped, officers);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        Fetcher fetcher = httpFetcher(delayMs(p));
        String pageUrl = required(p, "pageUrl");
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int months = monthsAfterEnd(p);
        LocalDate today = LocalDate.now();

        List<String> legs = resolveLegislatures(p);
        int totalCurrent = 0, totalSuspended = 0, totalFormer = 0, totalOfficers = 0;
        List<String> processed = new ArrayList<>();

        for (String leg : legs) {
            try {
                downloadLegislature(fetcher, pageUrl, leg, ctx.rawDir());
                Report rep = convert(ctx.rawDir(), leg, pageUrl, today, months, dataset);
                totalCurrent += rep.current();
                totalSuspended += rep.suspended();
                totalFormer += rep.former();
                totalOfficers += rep.officers();
                processed.add(leg);
            } catch (Exception e) {
                if (processed.isEmpty()) {
                    throw e;
                }
            }
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        String msg = String.format("Législatures %s : %d députés en fonction, %d mandats suspendus, %d anciens députés, %d fonctions du bureau.",
                String.join(", ", processed), totalCurrent, totalSuspended, totalFormer, totalOfficers);
        return dataset.toConversionResult().withMessage(msg);
    }

    static List<String> resolveLegislatures(Map<String, String> p) {
        if (p.containsKey("legislature") && !p.get("legislature").isBlank()) {
            return List.of(p.get("legislature").trim());
        }
        if (p.containsKey("legislatures") && !p.get("legislatures").isBlank()) {
            return java.util.Arrays.stream(p.get("legislatures").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return List.of("XVII", "XVI", "XV", "XIV", "XIII", "XII");
    }

    static void downloadLegislature(Fetcher fetcher, String pageUrl, String wanted, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        Path rootFile = rawDir.resolve(ROOT_PAGE);
        String root;
        if (Files.exists(rootFile)) {
            root = Files.readString(rootFile, StandardCharsets.UTF_8);
        } else {
            root = fetcher.get(pageUrl);
            Files.writeString(rootFile, root, StandardCharsets.UTF_8);
        }
        String[] folder = folderLink(root, pageUrl, wanted);
        String legislature = folder[0];
        Path legPageFile = rawDir.resolve("legislature-page-" + legislature + ".html");
        String page;
        if (Files.exists(legPageFile)) {
            page = Files.readString(legPageFile, StandardCharsets.UTF_8);
        } else {
            page = fetcher.get(folder[1]);
            Files.writeString(legPageFile, page, StandardCharsets.UTF_8);
        }
        String jsonUrl = jsonLink(page, folder[1], legislature);
        Path jsonFile = rawDir.resolve("composicao_" + legislature + ".json");
        if (!Files.exists(jsonFile)) {
            Files.writeString(jsonFile, fetcher.get(jsonUrl), StandardCharsets.UTF_8);
        }
        if ("XVII".equals(wanted) || !Files.exists(rawDir.resolve(COMPOSITION))) {
            Files.copy(jsonFile, rawDir.resolve(COMPOSITION), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), p.get("legislature"), p.getOrDefault("pageUrl", ""), LocalDate.now(), monthsAfterEnd(p), dataset);
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
     * Suit la page des législatures puis la page de la législature et dépose les trois fichiers dans {@code rawDir}.
     *
     * @return le sigle de la législature lue (« XVII »)
     */
    static String download(Fetcher fetcher, String pageUrl, String wanted, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String root = fetcher.get(pageUrl);
        Files.writeString(rawDir.resolve(ROOT_PAGE), root, StandardCharsets.UTF_8);
        String[] folder = folderLink(root, pageUrl, wanted);
        String legislature = folder[0];
        String page = fetcher.get(folder[1]);
        Files.writeString(rawDir.resolve(LEGISLATURE_PAGE), page, StandardCharsets.UTF_8);
        String jsonUrl = jsonLink(page, folder[1], legislature);
        Files.writeString(rawDir.resolve(COMPOSITION), fetcher.get(jsonUrl), StandardCharsets.UTF_8);
        return legislature;
    }

    /** {sigle, adresse} du dossier de la législature demandée, ou du premier de la page. */
    static String[] folderLink(String html, String base, String wanted) {
        Matcher a = ANCHOR.matcher(html);
        while (a.find()) {
            String title = attr(TITLE, a.group());
            String href = attr(HREF, a.group());
            Matcher m = title == null ? null : FOLDER.matcher(title.trim());
            if (m != null && m.matches() && href != null && (wanted == null || wanted.isBlank() || wanted.equals(m.group(1)))) {
                return new String[] { m.group(1), URI.create(base).resolve(unescape(href)).toString() };
            }
        }
        throw new IllegalStateException("Aucun dossier de législature" + (wanted == null || wanted.isBlank() ? "" : " « " + wanted + " »")
                + " dans la page " + base + " : la page a peut-être changé.");
    }

    /** Adresse du fichier JSON de composition des organes de la législature. */
    static String jsonLink(String html, String base, String legislature) {
        Matcher a = ANCHOR.matcher(html);
        while (a.find()) {
            String title = attr(TITLE, a.group());
            String href = attr(HREF, a.group());
            Matcher m = title == null ? null : JSON_FILE.matcher(title.trim());
            if (m != null && m.matches() && href != null && m.group(1).equals(legislature)) {
                return URI.create(base).resolve(unescape(href)).toString();
            }
        }
        throw new IllegalStateException("Fichier OrgaoComposicao" + legislature + "_json.txt introuvable dans la page "
                + base + " : la page a peut-être changé.");
    }

    private static String attr(Pattern pattern, String tag) {
        Matcher m = pattern.matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&");
    }

    /** Lit la composition du plénaire et écrit les députés. Séparé de {@link #parse} pour les tests. */
    Report convert(Path rawDir, String legislature, String pageUrl, LocalDate today, int monthsAfterEnd,
            FtmDatasetBuilder dataset) throws IOException {
        Path file = rawDir.resolve(COMPOSITION);
        if (!Files.exists(file) && legislature != null) {
            file = rawDir.resolve("composicao_" + legislature + ".json");
        }
        if (!Files.exists(file)) {
            throw new IllegalStateException(COMPOSITION + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode members = JSON.readTree(text.startsWith("﻿") ? text.substring(1) : text).path("Plenario").path("Composicao");
        if (!members.isArray() || members.isEmpty()) {
            throw new IllegalStateException("Plenario.Composicao absent ou vide dans " + COMPOSITION
                    + " : le format du fichier a changé.");
        }
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        int current = 0, suspended = 0, former = 0, skipped = 0, officers = 0;
        for (JsonNode member : members) {
            List<Period> periods = periods(member.path("DepSituacao"));
            Period open = periods.stream().filter(x -> x.seated() && x.end() == null).findFirst().orElse(null);
            Period susp = periods.stream().filter(x -> x.suspendedElected() && x.end() == null).findFirst().orElse(null);
            LocalDate start = periods.stream().filter(x -> x.seated() || x.suspendedElected()).map(Period::start)
                    .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            LocalDate end = null;
            String status;
            String note = null;
            if (open != null) {
                status = "current";
                current++;
            } else if (susp != null) {
                status = "current";
                note = "Mandat suspendu (Suspenso)";
                suspended++;
            } else {
                end = periods.stream().filter(Period::seated).map(Period::end).filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null);
                if (end == null || end.isBefore(cutoff)) {
                    skipped++;
                    continue;
                }
                status = "ended";
                former++;
            }
            officers += write(member, legislature, pageUrl, start, end, status, note, cutoff, dataset);
        }
        if (current == 0 && ("XVII".equals(legislature) || legislature == null)) {
            throw new IllegalStateException("Aucun député en fonction dans " + COMPOSITION + " : fichier incomplet ou "
                    + "format changé.");
        }
        return new Report(legislature, current, suspended, former, skipped, officers);
    }

    private static List<Period> periods(JsonNode situation) {
        List<Period> list = new ArrayList<>();
        for (JsonNode s : situation) {
            list.add(new Period(s.path("sioDes").asText(""), date(s.path("sioDtInicio")), date(s.path("sioDtFim"))));
        }
        return list;
    }

    private static LocalDate date(JsonNode n) {
        return n == null || n.isNull() || n.asText().isBlank() ? null : LocalDate.parse(n.asText().substring(0, 10));
    }

    /** Écrit la personne, son siège et ses fonctions du bureau ; renvoie le nombre de fonctions du bureau. */
    private static int write(JsonNode m, String legislature, String pageUrl, LocalDate start, LocalDate end,
            String status, String note, LocalDate cutoff, FtmDatasetBuilder dataset) {
        String id = m.path("DepId").asText();
        String fullName = m.path("DepNomeCompleto").asText(null);
        String parliamentaryName = m.path("DepNomeParlamentar").asText(null);
        String name = fullName == null || fullName.isBlank() ? parliamentaryName : fullName;

        FtmEntity person = dataset.newEntity("Person", id);
        person.addValue("name", name);
        if (parliamentaryName != null && !parliamentaryName.isBlank() && !parliamentaryName.equals(name)) {
            person.addValue("alias", parliamentaryName);
        }
        person.addValue("country", "pt");
        person.addValue("position", SEAT);
        person.addValue("sourceUrl", pageUrl);
        dataset.addEntity(person);

        String group = parliamentaryGroup(m.path("DepGP"));
        String circle = m.path("DepCPDes").asText(null);
        String description = joinParts("Círculo eleitoral : " + circle, group == null ? null : "Grupo parlamentar : " + group, note);

        FtmEntity seat = dataset.newEntity("Position", "deputy");
        seat.addValue("name", SEAT);
        seat.addValue("country", "pt");
        seat.addValue("organization", "Assembleia da República");
        seat.addValue("topics", "gov.national");
        seat.addValue("topics", "gov.legislative");
        dataset.addEntity(seat);

        FtmEntity occupancy = dataset.newEntity("Occupancy", id, "deputy", legislature);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", seat.getId());
        occupancy.addValue("startDate", start == null ? null : start.toString());
        occupancy.addValue("endDate", end == null ? null : end.toString());
        occupancy.addValue("status", status);
        occupancy.addValue("description", description);
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);

        int officers = 0;
        for (JsonNode role : m.path("DepCargo")) {
            String label = officeLabel(role.path("carDes").asText(""));
            LocalDate roleStart = date(role.path("carDtInicio"));
            LocalDate roleEnd = date(role.path("carDtFim"));
            if (label == null || (roleEnd != null && roleEnd.isBefore(cutoff))) {
                continue;
            }
            person.addValue("position", label);
            FtmEntity post = dataset.newEntity("Position", "office", label);
            post.addValue("name", label);
            post.addValue("country", "pt");
            post.addValue("organization", "Assembleia da República");
            post.addValue("topics", "gov.national");
            post.addValue("topics", "gov.legislative");
            dataset.addEntity(post);
            FtmEntity held = dataset.newEntity("Occupancy", id, "office", label, String.valueOf(roleStart));
            held.addValue("holder", person.getId());
            held.addValue("post", post.getId());
            held.addValue("startDate", roleStart == null ? null : roleStart.toString());
            held.addValue("endDate", roleEnd == null ? null : roleEnd.toString());
            held.addValue("status", roleEnd == null ? "current" : "ended");
            held.addValue("sourceUrl", pageUrl);
            dataset.addEntity(held);
            officers++;
        }
        return officers;
    }

    private static String officeLabel(String carDes) {
        return switch (carDes.trim()) {
            case "Presidente" -> "Presidente da Assembleia da República";
            case "Vice-Presidente" -> "Vice-Presidente da Assembleia da República";
            case "Secretário" -> "Secretário da Mesa da Assembleia da República";
            case "Vice-Secretário" -> "Vice-Secretário da Mesa da Assembleia da República";
            default -> carDes.isBlank() ? null : carDes.trim() + " (Assembleia da República)";
        };
    }

    /** Groupe parlementaire le plus récent. */
    private static String parliamentaryGroup(JsonNode groups) {
        String best = null;
        String bestStart = "";
        for (JsonNode g : groups) {
            String start = g.path("gpDtInicio").asText("");
            if (best == null || start.compareTo(bestStart) > 0) {
                best = g.path("gpSigla").asText(null);
                bestStart = start;
            }
        }
        return best;
    }

    private static String joinParts(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank() && !p.endsWith(" null")) {
                kept.add(p);
            }
        }
        return String.join(" ; ", kept);
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
            return 180;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « monthsAfterEnd » invalide : « " + v + " » (nombre de mois attendu).");
        }
    }

    private static long delayMs(Map<String, String> p) {
        String v = p.get("delayMs");
        return v == null || v.isBlank() ? 1500 : Long.parseLong(v.trim());
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
