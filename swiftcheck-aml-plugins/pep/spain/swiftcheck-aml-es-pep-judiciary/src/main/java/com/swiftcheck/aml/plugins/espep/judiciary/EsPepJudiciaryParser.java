package com.swiftcheck.aml.plugins.espep.judiciary;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <b>es_pep_judiciary</b> – Hautes juridictions d'Espagne (bloc ES-c ; Ley 10/2010, art. 14.2 ; JO C/2023/724).
 *
 * <p>Sources officielles directes :
 * <ul>
 *   <li><b>Tribunal Supremo</b> : composition officielle de la Sala de Gobierno sur le portail du
 *       Poder Judicial ({@code poderjudicial.es/.../Tribunal-Supremo/Sala-de-Gobierno/Composicion/}) ;</li>
 *   <li><b>Consejo General del Poder Judicial (CGPJ)</b> : décrets royaux de nomination des 20 vocales
 *       (RD 755/2024 et RD 756/2024) et de la présidente (RD 888/2024) publiés au BOE ;</li>
 *   <li><b>Tribunal Constitucional</b> : décrets royaux de nomination des 12 magistrats et de la présidence
 *       publiés au BOE.</li>
 * </ul>
 */
@Extension
public class EsPepJudiciaryParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepJudiciaryParser.class);

    /** Préfixe des identifiants FtM. */
    static final String ID_PREFIX = "es-pep-judiciary";
    static final String TOPIC = "role.pep";
    static final String TS_URL = "https://www.poderjudicial.es/cgpj/es/Poder-Judicial/Tribunal-Supremo/Sala-de-Gobierno/Composicion/";
    static final String BOE_DOC_URL = "https://www.boe.es/diario_boe/xml.php?id=";
    static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Espagne - Hautes Juridictions)";

    static final String TS_FILE = "tribunal-supremo.html";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Décrets royaux officiels du Tribunal Constitucional (composition actuelle des 12 membres). */
    static final List<BoeDocConfig> TC_DOCS = List.of(
            new BoeDocConfig("BOE-A-2023-712", "Presidente del Tribunal Constitucional", "Tribunal Constitucional", "tc-presidente"),
            new BoeDocConfig("BOE-A-2023-713", "Vicepresidenta del Tribunal Constitucional", "Tribunal Constitucional", "tc-vicepresidente"),
            new BoeDocConfig("BOE-A-2024-15661", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2022-24441", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2022-24442", "Magistrada del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2022-24443", "Magistrada del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2022-24444", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2021-18901", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2021-18902", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2021-18903", "Magistrada del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2017-2632", "Magistrado del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado"),
            new BoeDocConfig("BOE-A-2017-2635", "Magistrada del Tribunal Constitucional", "Tribunal Constitucional", "tc-magistrado")
    );

    /** Décrets royaux du CGPJ et Présidence du Tribunal Supremo. */
    static final List<BoeDocConfig> CGPJ_DOCS = List.of(
            new BoeDocConfig("BOE-A-2024-17786", "Presidenta del Tribunal Supremo y del CGPJ", "Tribunal Supremo y Consejo General del Poder Judicial", "ts-cgpj-presidente"),
            new BoeDocConfig("BOE-A-2024-15351", "Vocal del Consejo General del Poder Judicial", "Consejo General del Poder Judicial", "cgpj-vocal"),
            new BoeDocConfig("BOE-A-2024-15352", "Vocal del Consejo General del Poder Judicial", "Consejo General del Poder Judicial", "cgpj-vocal")
    );

    record BoeDocConfig(String id, String positionName, String organization, String positionId) {}

    record Judge(String name, String cleanName, String positionName, String organization,
                 String positionId, LocalDate startDate, String sourceUrl) {}

    record Report(int totalJudges, int tcCount, int cgpjCount, int tsCount) {
        String message() {
            return String.format("%d hauts magistrats collectés auprès des sources officielles (%d Tribunal Constitucional, %d CGPJ, %d Tribunal Supremo).",
                    totalJudges, tcCount, cgpjCount, tsCount);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        download(ctx.rawDir());
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    // ------------------------------------------------------------------ Téléchargement

    static void download(Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        // 1. Tribunal Supremo Sala de Gobierno
        try {
            HttpRequest reqTs = HttpRequest.newBuilder(URI.create(TS_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<byte[]> resTs = client.send(reqTs, HttpResponse.BodyHandlers.ofByteArray());
            if (resTs.statusCode() == 200) {
                Files.write(rawDir.resolve(TS_FILE), resTs.body());
            } else {
                log.warn("Tribunal Supremo HTTP {}", resTs.statusCode());
            }
        } catch (Exception e) {
            log.warn("Impossible de télécharger la composition du Tribunal Supremo : {}", e.getMessage());
        }

        // 2. BOE XML documents
        List<BoeDocConfig> allDocs = new ArrayList<>();
        allDocs.addAll(CGPJ_DOCS);
        allDocs.addAll(TC_DOCS);

        for (BoeDocConfig doc : allDocs) {
            Path target = rawDir.resolve("doc-" + doc.id() + ".xml");
            if (Files.isRegularFile(target)) {
                continue;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(BOE_DOC_URL + doc.id()))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/xml")
                        .GET().build();
                HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200) {
                    Files.write(target, res.body());
                } else {
                    log.warn("BOE {} HTTP {}", doc.id(), res.statusCode());
                }
                Thread.sleep(150);
            } catch (Exception e) {
                log.warn("Erreur téléchargement BOE {} : {}", doc.id(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ Conversion

    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws IOException {
        List<Judge> judges = new ArrayList<>();
        int tcCount = 0;
        int cgpjCount = 0;
        int tsCount = 0;

        // 1. Parse Tribunal Constitucional BOE docs
        for (BoeDocConfig cfg : TC_DOCS) {
            Path file = rawDir.resolve("doc-" + cfg.id() + ".xml");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Judge j = parseSingleBoeAppointment(file, cfg);
            if (j != null) {
                judges.add(j);
                tcCount++;
            }
        }

        // 2. Parse CGPJ BOE docs
        for (BoeDocConfig cfg : CGPJ_DOCS) {
            Path file = rawDir.resolve("doc-" + cfg.id() + ".xml");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if ("BOE-A-2024-15351".equals(cfg.id()) || "BOE-A-2024-15352".equals(cfg.id())) {
                List<Judge> vocales = parseCgpjVocales(file, cfg);
                judges.addAll(vocales);
                cgpjCount += vocales.size();
            } else {
                Judge j = parseSingleBoeAppointment(file, cfg);
                if (j != null) {
                    judges.add(j);
                    cgpjCount++;
                }
            }
        }

        // 3. Parse Tribunal Supremo HTML
        Path tsFile = rawDir.resolve(TS_FILE);
        if (Files.isRegularFile(tsFile)) {
            List<Judge> tsMembers = parseTribunalSupremo(tsFile);
            judges.addAll(tsMembers);
            tsCount += tsMembers.size();
        }

        if (judges.isEmpty()) {
            throw new IllegalStateException("Aucun haut magistrat trouvé dans les fichiers officiels téléchargés.");
        }

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        for (Judge j : judges) {
            String pKey = personKey(j.cleanName());
            FtmEntity person = persons.computeIfAbsent(pKey, k -> newPerson(j, dataset));
            person.addValue("position", j.positionName() + " (" + j.organization() + ")");

            FtmEntity position = dataset.newEntity("Position", j.positionId());
            position.addValue("name", j.positionName());
            position.addValue("organization", j.organization());
            position.addValue("country", "es");
            position.addValue("topics", "gov.judicial");
            position.addValue("topics", "gov.national");
            dataset.addEntity(position);

            FtmEntity occupancy = dataset.newEntity("Occupancy", pKey, j.positionId());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", position.getId());
            occupancy.addValue("status", "current");
            if (j.startDate() != null) {
                occupancy.addValue("startDate", j.startDate().toString());
            }
            occupancy.addValue("sourceUrl", j.sourceUrl());
            dataset.addEntity(occupancy);
        }

        persons.values().forEach(dataset::addEntity);
        Report report = new Report(judges.size(), tcCount, cgpjCount, tsCount);
        log.info(report.message());
        return report;
    }

    // ------------------------------------------------------------------ Parsers spécialisés

    static Judge parseSingleBoeAppointment(Path file, BoeDocConfig cfg) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        LocalDate pubDate = extractBoeDate(content);

        // Extraire le nom de la personne depuis le titre ou le texte : "... a don/doña [Nom]..."
        Pattern pTitle = Pattern.compile("por el que se nombra[\\s\\S]*?\\ba (?:don|doña|Don|Doña)\\s+([^\\.,<]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = pTitle.matcher(content);
        String rawName = null;
        if (m.find()) {
            rawName = m.group(1).trim();
        } else {
            Pattern pText = Pattern.compile("(?:nombra|nombrar)[\\s\\S]*?\\ba (?:don|doña|Don|Doña)\\s+([^\\.,<]+)", Pattern.CASE_INSENSITIVE);
            Matcher mt = pText.matcher(content);
            if (mt.find()) {
                rawName = mt.group(1).trim();
            }
        }

        if (rawName == null || rawName.isBlank()) {
            return null;
        }

        String clean = cleanName(rawName);
        String sourceUrl = "https://www.boe.es/buscar/act.php?id=" + cfg.id();
        return new Judge(clean, clean, cfg.positionName(), cfg.organization(), cfg.positionId(), pubDate, sourceUrl);
    }

    static List<Judge> parseCgpjVocales(Path file, BoeDocConfig cfg) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        LocalDate pubDate = extractBoeDate(content);
        List<Judge> list = new ArrayList<>();

        Pattern pPara = Pattern.compile("<p class=\"parrafo(?:_\\d+)?\">(?:Don|Doña)\\s+([^<\\.]+)\\.</p>", Pattern.CASE_INSENSITIVE);
        Matcher m = pPara.matcher(content);
        String sourceUrl = "https://www.boe.es/buscar/act.php?id=" + cfg.id();

        while (m.find()) {
            String rawName = m.group(1).trim();
            if (rawName.isBlank() || rawName.toLowerCase(Locale.ROOT).startsWith("el ministro")) {
                continue;
            }
            String clean = cleanName(rawName);
            list.add(new Judge(clean, clean, cfg.positionName(), cfg.organization(), cfg.positionId(), pubDate, sourceUrl));
        }
        return list;
    }

    static List<Judge> parseTribunalSupremo(Path file) throws IOException {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        List<Judge> list = new ArrayList<>();

        Pattern pAccordion = Pattern.compile("<div class=\"link level1\"[^>]*>[\\s\\S]*?<p>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE);
        Matcher m = pAccordion.matcher(html);

        while (m.find()) {
            String raw = m.group(1).replaceAll("<[^>]+>", " ").trim();
            raw = raw.replace("&nbsp;", " ").replace("&aacute;", "á").replace("&eacute;", "é")
                    .replace("&iacute;", "í").replace("&oacute;", "ó").replace("&uacute;", "ú")
                    .replace("&ntilde;", "ñ").replace("&Aacute;", "Á").replace("&Eacute;", "É")
                    .replace("&Iacute;", "Í").replace("&Oacute;", "Ó").replace("&Uacute;", "Ú")
                    .replace("&Ntilde;", "Ñ").replaceAll("\\s+", " ").trim();

            if (raw.isBlank()) {
                continue;
            }

            String posName = "Magistrado del Tribunal Supremo";
            String posId = "ts-magistrado";
            String personName = raw;

            if (raw.startsWith("Presidenta:") || raw.startsWith("Presidente:")) {
                posName = "Presidenta del Tribunal Supremo y del CGPJ";
                posId = "ts-cgpj-presidente";
                personName = raw.substring(raw.indexOf(':') + 1).trim();
            } else if (raw.startsWith("Vicepresidente:")) {
                posName = "Vicepresidente del Tribunal Supremo";
                posId = "ts-vicepresidente";
                personName = raw.substring(raw.indexOf(':') + 1).trim();
            } else if (raw.contains("Presidente Sala") || raw.contains("Presidenta Sala")) {
                posName = "Presidente de Sala del Tribunal Supremo";
                posId = "ts-sala-presidente";
                personName = raw.replaceAll("\\s*\\(President[ae][^\\)]*\\)", "").trim();
            } else if (raw.contains("Secretario de Gobierno")) {
                posName = "Secretario de Gobierno del Tribunal Supremo";
                posId = "ts-secretario";
                personName = raw.replaceAll("\\s*\\(Secretario[^\\)]*\\)", "").trim();
            }

            // Normaliser "Perelló Doménech, María Isabel" -> "María Isabel Perelló Doménech"
            String clean = reorderLastFirst(personName);
            list.add(new Judge(clean, clean, posName, "Tribunal Supremo", posId, null, TS_URL));
        }
        return list;
    }

    private static LocalDate extractBoeDate(String xml) {
        Matcher m = Pattern.compile("<fecha_publicacion>(\\d{8})</fecha_publicacion>").matcher(xml);
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(1), BASIC_DATE);
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String reorderLastFirst(String text) {
        String[] parts = text.split(",", 2);
        if (parts.length == 2) {
            return (parts[1].trim() + " " + parts[0].trim()).trim();
        }
        return text.trim();
    }

    static String cleanName(String name) {
        return name.replaceAll("\\s+", " ").trim();
    }

    static String personKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static FtmEntity newPerson(Judge j, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", personKey(j.cleanName()));
        person.addValue("name", j.cleanName());
        person.addValue("country", "es");
        person.addValue("nationality", "es");
        person.addValue("sourceUrl", j.sourceUrl());
        return person;
    }
}
