package com.swiftcheck.aml.plugins.depep.defenceluftwaffe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import com.swiftcheck.aml.plugins.depep.vita.VitaDocuments.Vita;
import com.swiftcheck.aml.plugins.depep.vita.VitaSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_defence_luftwaffe</b> - Commandement de la Luftwaffe (Armée de l'air allemande), escadres, écoles et centres (BMVg / Bundeswehr).
 *
 * <p>Cadre réglementaire :
 * <ul>
 *   <li>Directive (UE) 2015/849 art. 3 pt 9 f) : Officiers supérieurs et généraux des forces armées</li>
 *   <li>GwG § 1 Abs. 12 S. 2 Nr. 1 : Classe F.1</li>
 * </ul>
 *
 * <p>Fonctions en allemand strict sans traduction (ex: <i>Inspekteur der Luftwaffe</i>, <i>Kommandeur Luftwaffentruppenkommando</i>,
 * <i>Kommodore Taktisches Luftwaffengeschwader</i>).
 *
 * <p>Gère les CVs en PDF (avec empreinte cryptographique SHA-256) et HTML direct (fail-safe).
 * Découplage strict via {@link ParserContext} : les URLs cibles sont injectées depuis la configuration en base de données.
 */
@Extension
public class DePepDefenceLuftwaffeParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceLuftwaffeParser.class);

    public static final String ID_PREFIX = "de-pep-defence-luftwaffe";
    public static final String TOPIC = "role.pep";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Map<String, String> GERMAN_MONTHS = Map.ofEntries(
            Map.entry("januar", "01"), Map.entry("februar", "02"), Map.entry("märz", "03"),
            Map.entry("maerz", "03"), Map.entry("april", "04"), Map.entry("mai", "05"),
            Map.entry("juni", "06"), Map.entry("juli", "07"), Map.entry("august", "08"),
            Map.entry("september", "09"), Map.entry("oktober", "10"), Map.entry("november", "11"),
            Map.entry("dezember", "12")
    );

    // Regex
    private static final Pattern ADDRESS_BLOCK = Pattern.compile("(?si)<address class=\"Address\">.*?</address>");
    private static final Pattern RANK_NAME_P = Pattern.compile("(?si)<p><strong>\\s*(Generalleutnant|Generalmajor|Brigadegeneral|General|Oberst(?:leutnant|\\s+i\\.G\\.)?|Generalarzt|Flottenarzt|Flottillenadmiral|Leitender\\s+Regierungsdirektor|Ltd(?:\\.|\\s+)Regierungsdirektor)\\s*</strong>\\s*([^<]+)</p>");
    private static final Pattern DESC_P = Pattern.compile("(?si)<p>\\s*(?:(?:Generalleutnant|Generalmajor|Brigadegeneral|General|Oberst(?:leutnant|\\s+i\\.G\\.)?|Generalarzt|Flottenarzt|Flottillenadmiral|Leitender\\s+Regierungsdirektor|Ltd(?:\\.|\\s+)Regierungsdirektor)\\s+)?([^<]+?)\\s+(?:führt|ist|kommandiert)\\s+seit\\s+([A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}[./]\\d{4}|\\d{4})(.*?)</p>");
    private static final Pattern PDF_LINK = Pattern.compile("(?si)<a[^>]+href=[\"']([^\"']+\\.pdf)[\"']");
    private static final Pattern IMG_SRC = Pattern.compile("(?si)<img[^>]+src=[\"']([^\"']*(?:portrait|bild|foto)[^\"']*)[\"']");

    public record CommanderProfile(
            String slug,
            String name,
            String rank,
            String title,
            String firstName,
            String lastName,
            String positionName,
            String startDate,
            String status,
            String sourceUrl,
            String photoUrl,
            String pdfUrl,
            String pdfSha256,
            String notes
    ) {}

    public record CrawlResult(
            List<CommanderProfile> profiles,
            List<String> brokenSources,
            List<String> warnings
    ) {}

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, params);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);

        for (CommanderProfile cp : crawl.profiles()) {
            writeCommander(cp, dataset);
        }

        dataset.writeNdjsonFile(ctx.outputFile());

        StringBuilder msg = new StringBuilder();
        msg.append(crawl.profiles().size()).append(" commandants et officiers généraux de la Luftwaffe (GwG F.1) extraits.");
        if (!crawl.brokenSources().isEmpty()) {
            msg.append(" [ALERT SOURCE_BROKEN: ").append(String.join(", ", crawl.brokenSources())).append("]");
        }
        if (!crawl.warnings().isEmpty()) {
            msg.append(" [ALERT STRUCTURE_CHANGED: ").append(String.join(", ", crawl.warnings())).append("]");
        }

        return dataset.toConversionResult().withMessage(msg.toString());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> params = ctx.parameters() == null ? Map.of() : ctx.parameters();
        CrawlResult crawl = crawl(ctx, params);

        List<SourceRecord> records = new ArrayList<>();
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);

        for (CommanderProfile cp : crawl.profiles()) {
            records.add(new SourceRecord(
                    ids.buildEntityId("person", cp.slug()),
                    "Person",
                    cp.rank() + " " + cp.name(),
                    cp.sourceUrl()
            ));
        }

        return records;
    }

    public CrawlResult crawl(ParserContext ctx, Map<String, String> params) {
        List<CommanderProfile> profiles = new ArrayList<>();
        List<String> brokenSources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (ctx.rawDir() != null) {
            try {
                Files.createDirectories(ctx.rawDir());
            } catch (IOException e) {
                log.warn("Impossible de créer rawDir {}: {}", ctx.rawDir(), e.getMessage());
            }
        }

        Path input = ctx.inputFile();
        Path testDir = null;
        if (input != null) {
            if (Files.isDirectory(input)) {
                testDir = input;
            } else if (Files.isRegularFile(input)) {
                testDir = input.getParent();
            }
        }

        // Test mode / Offline execution
        if (testDir != null && Files.exists(testDir.resolve("kommando-luftwaffe.html"))) {
            try {
                String kdolHtml = Files.readString(testDir.resolve("kommando-luftwaffe.html"), StandardCharsets.UTF_8);
                if (ctx.rawDir() != null) {
                    Files.writeString(ctx.rawDir().resolve("kommando-luftwaffe.html"), kdolHtml, StandardCharsets.UTF_8);
                }
                String sourceUrl = params.getOrDefault("kommandoLuftwaffeUrl", "https://www.bundeswehr.de/de/organisation/luftwaffe/organisation-/kommando-luftwaffe");
                List<CommanderProfile> list = parseCommandersFromHtml(
                        kdolHtml,
                        sourceUrl,
                        testDir.resolve("sample-vita.pdf")
                );
                profiles.addAll(list);

                Path tlg31File = testDir.resolve("taktisches-luftwaffengeschwader-31-boelcke.html");
                if (Files.exists(tlg31File)) {
                    String tlg31Html = Files.readString(tlg31File, StandardCharsets.UTF_8);
                    if (ctx.rawDir() != null) {
                        Files.writeString(ctx.rawDir().resolve("taktisches-luftwaffengeschwader-31-boelcke.html"), tlg31Html, StandardCharsets.UTF_8);
                    }
                    profiles.addAll(parseCommandersFromHtml(
                            tlg31Html,
                            "https://www.bundeswehr.de/de/organisation/luftwaffe/organisation-/luftwaffentruppenkommando/taktisches-luftwaffengeschwader-31-boelcke",
                            testDir.resolve("sample-vita.pdf")
                    ));
                }

                Path lfaFile = testDir.resolve("das-luftfahrtamt-der-bundeswehr.html");
                if (Files.exists(lfaFile)) {
                    String lfaHtml = Files.readString(lfaFile, StandardCharsets.UTF_8);
                    if (ctx.rawDir() != null) {
                        Files.writeString(ctx.rawDir().resolve("das-luftfahrtamt-der-bundeswehr.html"), lfaHtml, StandardCharsets.UTF_8);
                    }
                    profiles.addAll(parseCommandersFromHtml(
                            lfaHtml,
                            "https://www.bundeswehr.de/de/organisation/luftwaffe/organisation-/das-luftfahrtamt-der-bundeswehr",
                            testDir.resolve("sample-vita.pdf")
                    ));
                }
            } catch (Exception e) {
                brokenSources.add("testDir: " + e.getMessage());
            }
            return new CrawlResult(profiles, brokenSources, warnings);
        }

        // Live network crawl - configuration injectée depuis ParserContext (table parser.parameters)
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        List<String> targetUrls = new ArrayList<>();
        if (params.containsKey("urls") && !params.get("urls").isBlank()) {
            for (String u : params.get("urls").split(",")) {
                String clean = u.trim();
                if (!clean.isEmpty() && !targetUrls.contains(clean)) {
                    targetUrls.add(clean);
                }
            }
        } else {
            String kommandoUrl = ctx.requireParameter("kommandoLuftwaffeUrl");
            targetUrls.add(kommandoUrl.trim());

            String rawUnits = params.get("units");
            if (rawUnits != null && !rawUnits.isBlank()) {
                for (String u : rawUnits.split(",")) {
                    String clean = u.trim();
                    if (!clean.isEmpty() && !targetUrls.contains(clean)) {
                        targetUrls.add(clean);
                    }
                }
            }
        }

        int index = 0;
        for (String url : targetUrls) {
            try {
                Thread.sleep(350); // Politesse HTTP
                String html = fetchSafe(client, url);
                if (html == null) {
                    brokenSources.add("SOURCE_BROKEN: " + url);
                    continue;
                }

                if (ctx.rawDir() != null) {
                    index++;
                    String safeName = String.format("source_%02d_%s.html", index, url.replaceAll("[^a-zA-Z0-9]+", "_"));
                    if (safeName.length() > 60) {
                        safeName = safeName.substring(0, 60) + ".html";
                    }
                    try {
                        Files.writeString(ctx.rawDir().resolve(safeName), html, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        log.warn("Échec écriture rawDir pour {}: {}", url, e.getMessage());
                    }
                }

                List<CommanderProfile> list = parseCommandersFromHtml(html, url, null);
                if (list.isEmpty()) {
                    log.debug("Aucun bloc de commandement détecté sur {}", url);
                }
                // Enrich profiles with PDF SHA-256
                for (CommanderProfile p : list) {
                    CommanderProfile enriched = enrichPdfSha256(client, p, ctx.rawDir(), brokenSources, warnings);
                    profiles.add(enriched);
                }
            } catch (Exception e) {
                log.warn("Erreur d'extraction sur {} : {}", url, e.getMessage());
                brokenSources.add(url + ": " + e.getMessage());
            }
        }

        // CV PDF donnés en paramètre (Story 3) : personnes qu'aucune page HTML ne présente. Officiers généraux,
        // amiraux et chefs civils d'un Bundesamt publiés ; officiers supérieurs comptés seulement (includeSeniorOfficers).
        List<String> vitaUrls = VitaSupport.parseList(params.get("vitaUrls"));
        if (!vitaUrls.isEmpty()) {
            Set<String> knownUrls = new LinkedHashSet<>();
            Set<String> knownNames = new LinkedHashSet<>();
            for (CommanderProfile cp : profiles) {
                if (cp.pdfUrl() != null) knownUrls.add(cp.pdfUrl());
                knownNames.add(VitaSupport.key(cp.name()));
            }
            VitaSupport.Outcome out = VitaSupport.readList(client, vitaUrls, knownUrls, knownNames, USER_AGENT,
                    ctx.rawDir(), "true".equalsIgnoreCase(params.get("includeSeniorOfficers")));
            for (Vita v : out.people()) {
                profiles.add(fromVita(v));
            }
            route(out.warnings(), brokenSources, warnings);
            log.info("CV PDF (vitaUrls) : {} fiches ajoutées, {} officiers supérieurs non publiés, {} documents sans personne",
                    out.people().size(), out.seniorOfficersSkipped(), out.withoutPerson());
        }

        return new CrawlResult(profiles, brokenSources, warnings);
    }

    /**
     * CV PDF (Story 3, règle UAT « Alfons Mais ») : le contenu du document fait foi pour le nom et le grade, pas le
     * nom du fichier ; la fiche reçoit la preuve (URL, page, extrait, SHA-256 du PDF). Le PDF est archivé dans rawDir.
     */
    private static CommanderProfile enrichPdfSha256(HttpClient client, CommanderProfile p, Path rawDir,
                                                    List<String> brokenSources, List<String> warnings) {
        if (p.pdfUrl() == null || p.pdfSha256() != null) {
            return p;
        }
        List<String> notes = new ArrayList<>();
        Vita v = VitaSupport.fetch(client, p.pdfUrl(), USER_AGENT, rawDir, notes);
        route(notes, brokenSources, warnings);
        return withVita(p, v);
    }

    /** Fusionne une fiche lue sur la page HTML et le CV PDF lu. */
    static CommanderProfile withVita(CommanderProfile p, Vita v) {
        if (v.sha256() == null) {
            return p;
        }
        String notes = (p.notes() == null ? "" : p.notes() + "\n") + VitaSupport.evidence(v);
        if (!v.hasPerson()) {
            notes += "\nCV sans nom lisible (" + v.kind() + ")";
            return new CommanderProfile(p.slug(), p.name(), p.rank(), p.title(), p.firstName(), p.lastName(),
                    p.positionName(), p.startDate(), p.status(), p.sourceUrl(), p.photoUrl(), p.pdfUrl(), v.sha256(), notes);
        }
        boolean sameName = VitaSupport.key(v.name()).equals(VitaSupport.key(p.name()));
        if (!sameName) {
            notes += "\nNom lu dans le CV ; la page indiquait : " + p.name();
        }
        String[] fl = VitaSupport.firstLast(v.name());
        String rank = v.rank() != null && !VitaSupport.isCivilHead(v.rank()) ? v.rank() : p.rank();
        String title = v.title() == null ? p.title() : (rank == null ? v.title() : rank + ", " + v.title());
        return new CommanderProfile(sameName ? p.slug() : slugify(v.name()), v.name(), rank, title, fl[0], fl[1],
                p.positionName() != null ? p.positionName() : v.function(), p.startDate(), p.status(), p.sourceUrl(),
                p.photoUrl(), p.pdfUrl(), v.sha256(), notes);
    }

    /** Fiche créée à partir d'un CV seul (paramètre vitaUrls). */
    static CommanderProfile fromVita(Vita v) {
        String[] fl = VitaSupport.firstLast(v.name());
        String rank = v.rank();
        String position = v.function() != null ? v.function() : (rank != null ? rank : "Leitung") + " (Luftwaffe)";
        String title = v.title() == null ? rank : (rank == null ? v.title() : rank + ", " + v.title());
        String notes = "Position: " + position + " (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1 - Luftwaffe)"
                + (rank == null ? "" : "\nGrade: " + rank)
                + (v.birthDate() == null ? "" : "\nGeburtsdatum: " + v.birthDate())
                + "\nCV-PDF: " + v.url() + "\n" + VitaSupport.evidence(v);
        return new CommanderProfile(slugify(v.name()), v.name(), rank, title, fl[0], fl[1], position, null, "current",
                v.url(), null, v.url(), v.sha256(), notes);
    }

    /** Range les alertes du lecteur de CV : SOURCE_BROKEN avec les sources, le reste avec les avertissements. */
    private static void route(List<String> notes, List<String> brokenSources, List<String> warnings) {
        for (String n : notes) {
            if (n.startsWith("SOURCE_BROKEN")) brokenSources.add(n); else warnings.add(n);
        }
    }

    public static List<CommanderProfile> parseCommandersFromHtml(String html, String pageUrl, Path samplePdfForTest) {
        List<CommanderProfile> list = new ArrayList<>();
        if (html == null || html.isBlank()) return list;

        Matcher mAddr = ADDRESS_BLOCK.matcher(html);
        while (mAddr.find()) {
            String addrBlock = mAddr.group(0);

            // Extract rank and name
            Matcher mRank = RANK_NAME_P.matcher(addrBlock);
            if (!mRank.find()) {
                continue;
            }

            String rank = cleanText(mRank.group(1));
            String rawName = cleanText(mRank.group(2));

            // Clean title (Dr., etc.)
            String title = rank;
            String cleanName = rawName;
            if (cleanName.startsWith("Dr. ") || cleanName.contains(" Dr. ")) {
                title = rank + ", Dr.";
                cleanName = cleanName.replaceFirst("^Dr\\.\\s+", "").trim();
            }

            String[] parts = splitFirstLastName(cleanName);
            String firstName = parts.length > 0 ? parts[0] : cleanName;
            String lastName = parts.length > 1 ? parts[1] : "";

            String slug = slugify(cleanName);

            // Description / Role / Start date
            String startDate = null;
            String positionName = "Kommandeur (Luftwaffe)";
            String descSentence = "";

            Matcher mDesc = DESC_P.matcher(addrBlock);
            if (mDesc.find()) {
                String dateRaw = mDesc.group(2);
                startDate = parseGermanMonthYear(dateRaw);
                String rest = cleanText(mDesc.group(3));
                positionName = resolvePositionFromText(addrBlock, rest, pageUrl);
                descSentence = cleanText(mDesc.group(0));
            } else {
                positionName = resolvePositionFromText(addrBlock, "", pageUrl);
            }

            // PDF CV URL
            String pdfUrl = null;
            Matcher mPdf = PDF_LINK.matcher(addrBlock);
            if (mPdf.find()) {
                String href = mPdf.group(1);
                pdfUrl = href.startsWith("http") ? href : "https://www.bundeswehr.de" + href;
            }

            // Photo URL
            String photoUrl = null;
            int blockIdx = html.indexOf(addrBlock);
            if (blockIdx > 0) {
                int lookback = Math.max(0, blockIdx - 1500);
                String preceding = html.substring(lookback, blockIdx);
                Matcher mImg = IMG_SRC.matcher(preceding);
                while (mImg.find()) {
                    String src = mImg.group(1);
                    photoUrl = src.startsWith("http") ? src : "https://www.bundeswehr.de" + src;
                }
            }

            // SHA-256 computation in test mode
            String sha256 = null;
            if (samplePdfForTest != null && Files.exists(samplePdfForTest)) {
                try {
                    byte[] bytes = Files.readAllBytes(samplePdfForTest);
                    sha256 = computeSha256(bytes);
                } catch (Exception ignored) {}
            }

            StringBuilder notes = new StringBuilder();
            notes.append("Position: ").append(positionName).append(" (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1 - Luftwaffe)");
            notes.append("\nGrade: ").append(rank);
            if (startDate != null) notes.append("\nPrise de fonction: ").append(startDate);
            if (!descSentence.isEmpty()) notes.append("\nPrésentation: ").append(descSentence);
            if (pdfUrl != null) {
                notes.append("\nCV-PDF: ").append(pdfUrl);
                if (sha256 != null) notes.append(" (SHA-256: ").append(sha256).append(")");
            }

            list.add(new CommanderProfile(
                    slug,
                    cleanName,
                    rank,
                    title,
                    firstName,
                    lastName,
                    positionName,
                    startDate,
                    "current",
                    pageUrl,
                    photoUrl,
                    pdfUrl,
                    sha256,
                    notes.toString()
            ));
        }

        return list;
    }

    private static String resolvePositionFromText(String block, String context, String pageUrl) {
        String lower = (block + " " + context + " " + pageUrl).toLowerCase(Locale.GERMAN);

        // 1. Kommando Luftwaffe
        if ((lower.contains("inspekteur") && lower.contains("luftwaffe") && !lower.contains("stellvertreter"))
                || (lower.contains("führt") && lower.contains("kommando luftwaffe"))) {
            return "Inspekteur der Luftwaffe";
        }
        if (lower.contains("stellvertreter des inspekteurs der luftwaffe") || lower.contains("stellvertretender inspekteur der luftwaffe")) {
            return "Stellvertreter des Inspekteurs der Luftwaffe";
        }
        if (lower.contains("chef des stabes") && lower.contains("kommando luftwaffe")) {
            return "Chef des Stabes Kommando Luftwaffe";
        }

        // 2. Luftwaffentruppenkommando
        if (lower.contains("kommandeur") && lower.contains("luftwaffentruppenkommando") && !lower.contains("stellvertreter")) {
            return "Kommandeur Luftwaffentruppenkommando";
        }
        if (lower.contains("stellvertret") && lower.contains("luftwaffentruppenkommando")) {
            return "Stellvertretender Kommandeur Luftwaffentruppenkommando";
        }
        if (lower.contains("chef des stabes") && lower.contains("luftwaffentruppenkommando")) {
            return "Chef des Stabes Luftwaffentruppenkommando";
        }

        // 3. Air Component Command
        if (lower.contains("air component command") || lower.contains("zentrum luftoperationen")) {
            if (lower.contains("chef des stabes")) return "Chef des Stabes Air Component Command";
            return "Kommandeur Air Component Command";
        }

        // 4. Commandements spécialisés & Espace
        if (lower.contains("weltraumkommando")) {
            return "Kommandeur Weltraumkommando der Bundeswehr";
        }
        if (lower.contains("luftfahrtamt")) {
            if (lower.contains("amtschef")) return "Amtschef Luftfahrtamt der Bundeswehr";
            return "Leitender Direktor Luftfahrtamt der Bundeswehr";
        }
        if (lower.contains("camo") || lower.contains("continuing airworthiness")) {
            return "Leiter Continuing Airworthiness Management Organisation der Bundeswehr";
        }
        if (lower.contains("luft- und raumfahrtmedizin") || lower.contains("generalarzt der luftwaffe")) {
            return "Leiter Zentrum für Luft- und Raumfahrtmedizin der Luftwaffe";
        }

        // 5. Escadres tactiques (TLG)
        if (lower.contains("geschwader-31") || lower.contains("boelcke")) {
            return "Kommodore Taktisches Luftwaffengeschwader 31 \"Boelcke\"";
        }
        if (lower.contains("geschwader-33")) {
            return "Kommodore Taktisches Luftwaffengeschwader 33";
        }
        if (lower.contains("geschwader-51") || lower.contains("immelmann")) {
            return "Kommodore Taktisches Luftwaffengeschwader 51 \"Immelmann\"";
        }
        if (lower.contains("geschwader-71") || lower.contains("richthofen")) {
            return "Kommodore Taktisches Luftwaffengeschwader 71 \"Richthofen\"";
        }
        if (lower.contains("geschwader-73") || lower.contains("steinhoff")) {
            return "Kommodore Taktisches Luftwaffengeschwader 73 \"Steinhoff\"";
        }
        if (lower.contains("geschwader-74")) {
            return "Kommodore Taktisches Luftwaffengeschwader 74";
        }

        // 6. Défense sol-air & Transport
        if (lower.contains("flugabwehrraketengeschwader") || lower.contains("flarakg")) {
            return "Kommandeur Flugabwehrraketengeschwader 1";
        }
        if (lower.contains("lufttransportgeschwader") || lower.contains("ltg 62") || lower.contains("geschwader-62")) {
            return "Kommodore Lufttransportgeschwader 62";
        }
        if (lower.contains("hubschraubergeschwader") || lower.contains("hsg 64") || lower.contains("geschwader-64")) {
            return "Kommodore Hubschraubergeschwader 64";
        }
        if (lower.contains("flugbereitschaft")) {
            return "Kommandeur Flugbereitschaft des Bundesministeriums der Verteidigung";
        }
        if (lower.contains("objektschutzregiment") || lower.contains("friesland")) {
            return "Kommandeur Objektschutzregiment der Luftwaffe \"Friesland\"";
        }
        if (lower.contains("evreux")) {
            return "Kommandeur Binationale Lufttransportstaffel Rhein/Rhine";
        }

        // 7. Écoles & Centres d'entraînement
        if (lower.contains("offizierschule der luftwaffe") || lower.contains("offizierschule")) {
            return "Kommandeur Offizierschule der Luftwaffe";
        }
        if (lower.contains("unteroffizierschule der luftwaffe") || lower.contains("unteroffizierschule")) {
            return "Kommandeur Unteroffizierschule der Luftwaffe";
        }
        if (lower.contains("waffenschule")) {
            return "Kommandeur Waffenschule Luftwaffe";
        }
        if (lower.contains("technisches ausbildungszentrum") || lower.contains("technisches-ausbildungszentrum")) {
            return "Kommandeur Technisches Ausbildungszentrum der Luftwaffe";
        }
        if (lower.contains("ausbildungskommando der luftwaffe usa") || lower.contains("taktisches-ausbildungskommando")) {
            return "Kommandeur Taktisches Ausbildungskommando der Luftwaffe USA";
        }
        if (lower.contains("luftwaffenausbildungsbataillon") || lower.contains("ausbildungsbataillon")) {
            return "Kommandeur Luftwaffenausbildungsbataillon 1";
        }

        // 8. Centres d'appui opérationnel & technique
        if (lower.contains("einsatzfuehrungsbereich-2") || lower.contains("einsatzführungsbereich 2")) {
            return "Kommandeur Einsatzführungsbereich 2";
        }
        if (lower.contains("einsatzfuehrungsbereich-3") || lower.contains("einsatzführungsbereich 3")) {
            return "Kommandeur Einsatzführungsbereich 3";
        }
        if (lower.contains("fuehrungsunterstuetzungszentrum")) {
            return "Kommandeur Führungsunterstützungszentrum der Luftwaffe";
        }
        if (lower.contains("waffensystemunterstuetzungszentrum-1")) {
            return "Kommandeur Waffensystemunterstützungszentrum 1";
        }
        if (lower.contains("waffensystemunterstuetzungszentrum")) {
            return "Kommandeur Waffensystemunterstützungszentrum 2";
        }

        return "Kommandeur (Luftwaffe)";
    }

    private static void writeCommander(CommanderProfile cp, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", cp.slug());
        person.addValue("name", cp.name());
        if (cp.rank() != null) person.addValue("title", cp.rank());
        if (cp.firstName() != null && !cp.firstName().isBlank()) person.addValue("firstName", cp.firstName());
        if (cp.lastName() != null && !cp.lastName().isBlank()) person.addValue("lastName", cp.lastName());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("position", cp.positionName());
        person.addValue("sourceUrl", cp.sourceUrl());

        if (cp.notes() != null) {
            person.addValue("summary", cp.notes());
            person.addValue("notes", cp.notes());
        }

        dataset.addEntity(person);

        if (cp.photoUrl() != null && !cp.photoUrl().isBlank()) {
            dataset.addMedia(person.getId(), cp.photoUrl(), cp.sourceUrl(), "Amtliches Porträt (bundeswehr.de)");
        }

        // Position entity
        String posSlug = slugify(cp.positionName());
        FtmEntity position = dataset.newEntity("Position", posSlug);
        position.addValue("name", cp.positionName());
        position.addValue("country", "de");
        position.addValue("organization", "Luftwaffe / Bundeswehr");
        position.addValue("topics", "military");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        // Occupancy entity
        FtmEntity occupancy = dataset.newEntity("Occupancy", cp.slug(), "luftwaffe");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", cp.status());
        occupancy.addValue("description", cp.positionName() + " (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1)");
        occupancy.addValue("sourceUrl", cp.sourceUrl());
        if (cp.startDate() != null) occupancy.addValue("startDate", cp.startDate());

        dataset.addEntity(occupancy);
    }

    public static String parseGermanMonthYear(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();

        // 1. dd.MM.yyyy
        Pattern pFull = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
        Matcher mFull = pFull.matcher(t);
        if (mFull.find()) {
            int d = Integer.parseInt(mFull.group(1));
            int m = Integer.parseInt(mFull.group(2));
            int y = Integer.parseInt(mFull.group(3));
            return String.format("%04d-%02d-%02d", y, m, d);
        }

        // 2. Month name yyyy (ex: "Oktober 2021")
        Pattern pMonthYear = Pattern.compile("([A-Za-zäöüÄÖÜ]+)\\s+(\\d{4})");
        Matcher mMY = pMonthYear.matcher(t);
        if (mMY.find()) {
            String monthName = mMY.group(1).toLowerCase(Locale.GERMAN);
            String year = mMY.group(2);
            String monthNum = GERMAN_MONTHS.get(monthName);
            if (monthNum != null) {
                return year + "-" + monthNum + "-01";
            }
        }

        // 3. MM/yyyy or MM.yyyy
        Pattern pSlash = Pattern.compile("(\\d{1,2})[./](\\d{4})");
        Matcher mSlash = pSlash.matcher(t);
        if (mSlash.find()) {
            int m = Integer.parseInt(mSlash.group(1));
            int y = Integer.parseInt(mSlash.group(2));
            return String.format("%04d-%02d-01", y, m);
        }

        // 4. yyyy alone
        Pattern pYear = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
        Matcher mYear = pYear.matcher(t);
        if (mYear.find()) {
            return mYear.group(1) + "-01-01";
        }

        return null;
    }

    public static String computeSha256(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Erreur de hachage SHA-256", e);
            return null;
        }
    }

    private static String[] splitFirstLastName(String name) {
        if (name == null || name.isBlank()) return new String[]{"", ""};
        String n = name.trim();
        int idx = n.lastIndexOf(' ');
        if (idx <= 0) return new String[]{n, ""};
        return new String[]{n.substring(0, idx).trim(), n.substring(idx + 1).trim()};
    }

    public static String slugify(String s) {
        if (s == null) return "unknown";
        String normalized = s.toLowerCase(Locale.GERMAN).replace("ß", "ss");
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private static String cleanText(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String fetchSafe(HttpClient client, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Encoding", "gzip, deflate")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                log.warn("HTTP {} sur {}", res.statusCode(), url);
                return null;
            }

            byte[] body = res.body();
            String encoding = res.headers().firstValue("Content-Encoding").orElse("");
            if ("gzip".equalsIgnoreCase(encoding)) {
                try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Échec requête sur {} : {}", url, e.getMessage());
            return null;
        }
    }
}
