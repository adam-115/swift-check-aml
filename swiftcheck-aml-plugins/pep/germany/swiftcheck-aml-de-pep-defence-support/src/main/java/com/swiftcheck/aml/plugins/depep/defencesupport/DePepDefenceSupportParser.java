package com.swiftcheck.aml.plugins.depep.defencesupport;

import java.io.ByteArrayInputStream;
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
 * <b>de_pep_defence_support</b> - Soutien, Ausrüstung (BAAINBw), Personnel, IUD, Écoles, Aumônerie et BAMAD (BMVg / Bundeswehr).
 *
 * <p>Cadre réglementaire :
 * <ul>
 *   <li>Directive (UE) 2015/849 art. 3 pt 9 f) : Officiers supérieurs et généraux des forces armées, hauts dirigeants</li>
 *   <li>GwG § 1 Abs. 12 S. 2 Nr. 1 : Classe F.1</li>
 * </ul>
 *
 * <p>Fonctions en allemand strict sans traduction (ex: <i>Präsidentin BAAINBw</i>,
 * <i>Kommandeur Unterstützungskommando der Bundeswehr</i>, <i>Präsident Bundesamt für den Militärischen Abschirmdienst</i>).
 *
 * <p>Périmètre volumineux (+80 unités) : traitement par lots contrôlés, politesse HTTP et journalisation des erreurs HTTP.
 * Découplage strict via {@link ParserContext} : les URLs cibles sont injectées depuis la configuration en base de données.
 */
@Extension
public class DePepDefenceSupportParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepDefenceSupportParser.class);

    public static final String ID_PREFIX = "de-pep-defence-support";
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
    private static final Pattern RANK_NAME_P = Pattern.compile("(?si)<p><strong>\\s*(Generalleutnant|Generalmajor|Brigadegeneral|General|Vizeadmiral|Konteradmiral|Flottillenadmiral|Generalstabsarzt|Generalarzt|Admiralarzt|Oberstabsarzt|Kapitän\\s+zur\\s+See|Kpt\\s+zS|Fregattenkapitän|FKpt|Oberst(?:leutnant|\\s+i\\.G\\.)?|Major|Präsidentin|Präsident|Vizepräsidentin|Vizepräsident|Amtschef|Militärbundesrabbiner|Militärbischof|Militärgeneraldekan|Leitende(?:r)?\\s+Regierungsdirektor(?:in)?|Regierungsdirektor(?:in)?)\\s*</strong>\\s*([^<]+)</p>");
    private static final Pattern DESC_P = Pattern.compile("(?si)<p>\\s*(?:(?:Generalleutnant|Generalmajor|Brigadegeneral|General|Vizeadmiral|Konteradmiral|Flottillenadmiral|Generalstabsarzt|Generalarzt|Admiralarzt|Oberstabsarzt|Kapitän\\s+zur\\s+See|Kpt\\s+zS|Fregattenkapitän|FKpt|Oberst(?:leutnant|\\s+i\\.G\\.)?|Major|Präsidentin|Präsident|Vizepräsidentin|Vizepräsident|Amtschef|Militärbundesrabbiner|Militärbischof|Militärgeneraldekan|Leitende(?:r)?\\s+Regierungsdirektor(?:in)?|Regierungsdirektor(?:in)?)\\s+)?([^<]+?)\\s+(?:führt|ist|leitet|kommandiert)\\s+seit\\s+([A-Za-zäöüÄÖÜ]+\\s*\\d{4}|\\d{1,2}[./]\\d{4}|\\d{4})(.*?)</p>");
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
        msg.append(crawl.profiles().size()).append(" hauts dirigeants et officiers généraux du soutien et de l'armement (GwG F.1) extraits.");
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
        if (testDir != null && Files.exists(testDir.resolve("unterstuetzungskommando.html"))) {
            try {
                String uskHtml = Files.readString(testDir.resolve("unterstuetzungskommando.html"), StandardCharsets.UTF_8);
                String sourceUrl = params.getOrDefault("supportUrl", "https://www.bundeswehr.de/de/organisation/unterstuetzungsbereich/unterstuetzungskommando-der-bundeswehr");
                List<CommanderProfile> list = parseCommandersFromHtml(
                        uskHtml,
                        sourceUrl,
                        testDir.resolve("sample-vita.pdf")
                );
                profiles.addAll(list);

                Path baainbwFile = testDir.resolve("baainbw.html");
                if (Files.exists(baainbwFile)) {
                    String baainHtml = Files.readString(baainbwFile, StandardCharsets.UTF_8);
                    profiles.addAll(parseCommandersFromHtml(
                            baainHtml,
                            "https://www.bundeswehr.de/de/organisation/ausruestung-baainbw/struktur/baainbw",
                            testDir.resolve("sample-vita.pdf")
                    ));
                }

                Path bamadFile = testDir.resolve("bamad.html");
                if (Files.exists(bamadFile)) {
                    String bamadHtml = Files.readString(bamadFile, StandardCharsets.UTF_8);
                    profiles.addAll(parseCommandersFromHtml(
                            bamadHtml,
                            "https://www.bundeswehr.de/de/organisation/mad-bundesamt-fuer-den-militaerischen-abschirmdienst",
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
            String primaryUrl = ctx.requireParameter("supportUrl");
            targetUrls.add(primaryUrl.trim());

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

        // Traitement par lots avec politesse HTTP
        int batchSize = 10;
        for (int i = 0; i < targetUrls.size(); i += batchSize) {
            int end = Math.min(i + batchSize, targetUrls.size());
            List<String> batch = targetUrls.subList(i, end);

            for (String url : batch) {
                try {
                    Thread.sleep(300); // Politesse HTTP
                    String html = fetchSafe(client, url);
                    if (html == null) {
                        brokenSources.add("SOURCE_BROKEN: " + url);
                        continue;
                    }
                    List<CommanderProfile> list = parseCommandersFromHtml(html, url, null);
                    if (list.isEmpty()) {
                        log.debug("Aucun bloc de commandement détecté sur {}", url);
                        warnings.add("Aucun profil de commandement détecté sur: " + url);
                    }
                    // Enrich profiles with PDF SHA-256
                    for (CommanderProfile p : list) {
                        CommanderProfile enriched = enrichPdfSha256(client, p);
                        profiles.add(enriched);
                    }
                } catch (Exception e) {
                    log.warn("Erreur d'extraction sur {} : {}", url, e.getMessage());
                    brokenSources.add(url + ": " + e.getMessage());
                }
            }
        }

        return new CrawlResult(profiles, brokenSources, warnings);
    }

    private static CommanderProfile enrichPdfSha256(HttpClient client, CommanderProfile profile) {
        if (profile.pdfUrl() == null || profile.pdfSha256() != null) {
            return profile;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(profile.pdfUrl()))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/pdf,*/*")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200 && res.body() != null && res.body().length > 0) {
                String sha256 = computeSha256(res.body());
                String notes = profile.notes() + "\nEmpreinte SHA-256 CV: " + sha256;
                return new CommanderProfile(
                        profile.slug(),
                        profile.name(),
                        profile.rank(),
                        profile.title(),
                        profile.firstName(),
                        profile.lastName(),
                        profile.positionName(),
                        profile.startDate(),
                        profile.status(),
                        profile.sourceUrl(),
                        profile.photoUrl(),
                        profile.pdfUrl(),
                        sha256,
                        notes
                );
            }
        } catch (Exception e) {
            log.debug("Impossible de télécharger le PDF pour calculer le SHA-256 : {}", profile.pdfUrl());
        }
        return profile;
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
            String positionName = "Führungsposition (Bundeswehr)";
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
            notes.append("Position: ").append(positionName).append(" (GwG § 1 Abs. 12 S. 2 Nr. 1 - Class F.1 - Bundeswehr)");
            notes.append("\nFunktion/Dienstgrad: ").append(rank);
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

        // 1. BAAINBw & Centres techniques WTD
        if (lower.contains("baainbw")) {
            if (lower.contains("vizepräsident")) return "Vizepräsident BAAINBw";
            return "Präsidentin BAAINBw";
        }
        if (lower.contains("wtd-41") || lower.contains("wtd 41")) {
            return "Leiter Wehrtechnische Dienststelle für landgebundene Fahrzeugsysteme (WTD 41)";
        }
        if (lower.contains("wtd-52") || lower.contains("wtd 52")) {
            return "Leiter Wehrtechnische Dienststelle für Schutz- und Sondertechnik (WTD 52)";
        }
        if (lower.contains("wtd-61") || lower.contains("wtd 61")) {
            return "Leiter Wehrtechnische Dienststelle für Luftfahrzeuge und Luftfahrtgerät (WTD 61)";
        }
        if (lower.contains("wtd-71") || lower.contains("wtd 71")) {
            return "Leiter Wehrtechnische Dienststelle für Schiffe und Marinewaffen (WTD 71)";
        }
        if (lower.contains("wtd-81") || lower.contains("wtd 81")) {
            return "Leiter Wehrtechnische Dienststelle für Informationstechnologie und Elektronik (WTD 81)";
        }
        if (lower.contains("wtd-91") || lower.contains("wtd 91")) {
            return "Leiter Wehrtechnische Dienststelle für Waffen und Munition (WTD 91)";
        }
        if (lower.contains("marinearsenal")) {
            return "Direktor Marinearsenal";
        }
        if (lower.contains("wiweb")) {
            return "Direktor Wehrwissenschaftliches Institut für Werk- und Betriebsstoffe";
        }
        if (lower.contains("wis") && lower.contains("ausruestung")) {
            return "Direktor Wehrwissenschaftliches Institut für Schutztechnologien - ABC-Schutz";
        }

        // 2. Renseignement BAMAD & Académies
        if (lower.contains("militärischen abschirmdienst") || lower.contains("militaerischen-abschirmdienst") || lower.contains("mad-bundesamt")) {
            return "Präsidentin Bundesamt für den Militärischen Abschirmdienst";
        }
        if (lower.contains("fuehrungsakademie") || lower.contains("führungsakademie")) {
            return "Kommandeur der Führungsakademie der Bundeswehr";
        }
        if (lower.contains("zentrum-innere-fuehrung") || lower.contains("zentrum innere führung")) {
            return "Kommandeur Zentrum Innere Führung";
        }
        if (lower.contains("bundesakademie-fuer-sicherheitspolitik") || lower.contains("baks")) {
            return "Präsident der Bundesakademie für Sicherheitspolitik";
        }

        // 3. IUD (Infrastructure, environnement, services)
        if (lower.contains("baiudbw")) {
            return "Präsident Bundesamt für Infrastruktur, Umweltschutz und Dienstleistungen der Bundeswehr";
        }
        if (lower.contains("verpflegungsamt")) {
            return "Leiter Verpflegungsamt der Bundeswehr";
        }
        if (lower.contains("brandschutzamt")) {
            return "Leiter Brandschutzamt der Bundeswehr";
        }

        // 4. Personal & Universités
        if (lower.contains("bapersbw") || lower.contains("personalmanagement")) {
            return "Präsident Bundesamt für das Personalmanagement der Bundeswehr";
        }
        if (lower.contains("helmut-schmidt-universitaet") || lower.contains("universitaet-der-bundeswehr-hamburg")) {
            return "Präsidentin / Präsident Helmut-Schmidt-Universität";
        }
        if (lower.contains("universitaet-der-bundeswehr-muenchen")) {
            return "Präsidentin / Präsident Universität der Bundeswehr München";
        }
        if (lower.contains("bundessprachenamt")) {
            return "Präsident Bundessprachenamt";
        }
        if (lower.contains("bildungszentrum-der-bundeswehr")) {
            return "Präsident Bildungszentrum der Bundeswehr";
        }

        // 5. Unterstützungsbereich (Soutien, Santé, Logistik, Feldjäger)
        if (lower.contains("unterstuetzungskommando") || lower.contains("unterstützungskommando")) {
            return "Kommandeur Unterstützungskommando der Bundeswehr";
        }
        if (lower.contains("gesundheitsversorgung")) {
            return "Kommandeur Kommando Gesundheitsversorgung der Bundeswehr";
        }
        if (lower.contains("abc-abwehrkommando") || lower.contains("abc-abwehr")) {
            return "Kommandeur ABC-Abwehrkommando der Bundeswehr";
        }
        if (lower.contains("logistikkommando")) {
            return "Kommandeur Logistikkommando der Bundeswehr";
        }
        if (lower.contains("wachbataillon")) {
            return "Kommandeur Wachbataillon beim Bundesministerium der Verteidigung";
        }
        if (lower.contains("feldjaegerregiment-1") || lower.contains("feldjägerregiment 1")) {
            return "Kommandeur Feldjägerregiment 1";
        }
        if (lower.contains("feldjaegerregiment-2") || lower.contains("feldjägerregiment 2")) {
            return "Kommandeur Feldjägerregiment 2";
        }
        if (lower.contains("feldjaegerregiment-3") || lower.contains("feldjägerregiment 3")) {
            return "Kommandeur Feldjägerregiment 3";
        }
        if (lower.contains("kommando-feldjaeger") || lower.contains("kommando feldjäger")) {
            return "Kommandeur Kommando Feldjäger der Bundeswehr";
        }
        if (lower.contains("dmv-nato-und-eu") || lower.contains("dmv nato")) {
            return "Deutscher Militärischer Vertreter im Militärausschuss der NATO und der EU";
        }
        if (lower.contains("streitkraefteamt") || lower.contains("streitkräfteamt")) {
            return "Amtschef Streitkräfteamt";
        }
        if (lower.contains("planungsamt")) {
            return "Amtschef Planungsamt der Bundeswehr";
        }
        if (lower.contains("operatives-fuehrungskommando")) {
            return "Befehlshaber Operatives Führungskommando der Bundeswehr";
        }

        // 6. Musique & Aumônerie
        if (lower.contains("zentrum-militaermusik") || lower.contains("militärmusik")) {
            return "Leiter Zentrum Militärmusik der Bundeswehr";
        }
        if (lower.contains("militaerbundesrabbiner")) {
            return "Militärbundesrabbiner";
        }
        if (lower.contains("evangelische-militaerseelsorge")) {
            return "Evangelischer Militärbischof";
        }
        if (lower.contains("sportschule-der-bundeswehr") || lower.contains("sportschule")) {
            return "Kommandeur Sportschule der Bundeswehr";
        }

        return "Führungsposition (Bundeswehr)";
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
        position.addValue("organization", "Bundeswehr / BMVg");
        position.addValue("topics", "military");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        // Occupancy entity
        FtmEntity occupancy = dataset.newEntity("Occupancy", cp.slug(), "support");
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

        // 2. Month name yyyy (ex: "Januar 2024")
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

