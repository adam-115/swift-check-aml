package com.swiftcheck.aml.ingestion;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.ListType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Index des PEP déjà publiées par les autres parsers, remis à un plugin de corrélation (Story 5, étape 1 :
 * Lobbyregister ↔ base PEP).
 *
 * <p>Un plugin n'a accès ni à la base ni au dossier de Yente. Pour croiser ses personnes avec toute la base PEP,
 * le core lui écrit donc un fichier NDJSON (une PEP par ligne) dans le dossier de travail et lui en donne le chemin
 * dans le paramètre {@value #PARAM_FILE}. Un reçu ({@value #RECEIPT_NAME} : empreinte SHA-256 de l'index et nombre
 * de PEP par jeu) est déposé dans {@code rawDir} : archivé avec les réponses brutes, il prouve quelle version de la
 * base a servi au rapprochement, et son empreinte entre dans celle de l'exécution (une base PEP changée relance la
 * corrélation même si la source du plugin n'a pas changé). L'index lui-même (plusieurs dizaines de Mo) n'est pas
 * archivé : il se reconstruit à partir des fichiers publiés, dont chaque version est tracée.
 *
 * <p>Activé par le paramètre {@value #PARAM_ENABLE}{@code =true} du parser. Seules les personnes portant le
 * thème {@code role.pep} des parsers PEP actifs sont reprises, le parser lui-même exclu.
 *
 * <p>Statut de chaque PEP, calculé d'après ses entités Occupancy : ACTIVE_PEP (un mandat en cours, ou aucune donnée
 * de mandat dans une liste des titulaires actuels), COOLING (dernier mandat terminé depuis moins de 12 mois, GwG
 * § 15 Abs. 7), EXPIRED (au-delà). Une note {@code pep_status:} de la fiche l'emporte.
 */
@Service
public class PepIndexService {

    private static final Logger log = LoggerFactory.getLogger(PepIndexService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Paramètre du parser qui active la corrélation. */
    public static final String PARAM_ENABLE = "pepCorrelation";
    /** Paramètre transmis au plugin : chemin de l'index. */
    public static final String PARAM_FILE = "pepIndexFile";
    /** Nom du fichier écrit dans rawDir. */
    public static final String FILE_NAME = "swiftcheck-pep-index.ndjson";
    /** Reçu déposé dans rawDir. */
    public static final String RECEIPT_NAME = "swiftcheck-pep-index-receipt.json";

    /** Bilan : fichier écrit, nombre de PEP et de jeux lus, empreinte de l'index, PEP par jeu. */
    public record Result(Path file, int pepCount, int datasetCount, String sha256, Map<String, Integer> perDataset) {
    }

    private final ParserRepository parsers;
    private final DatasetPublisher publisher;

    public PepIndexService(ParserRepository parsers, DatasetPublisher publisher) {
        this.parsers = parsers;
        this.publisher = publisher;
    }

    public static boolean enabled(Map<String, String> parameters) {
        return parameters != null && "true".equalsIgnoreCase(parameters.get(PARAM_ENABLE));
    }

    /** Écrit l'index des PEP publiées par les autres parsers PEP actifs. */
    public Result build(Parser self, ApplicationConfigEntity config, Path file, LocalDate today) throws IOException {
        int peps = 0;
        int datasets = 0;
        Map<String, Integer> perDataset = new java.util.TreeMap<>();
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Parser p : parsers.findByEnabledTrue()) {
                if (p.getId() == self.getId() || p.getListType() != ListType.PEP || p.getDatasetName() == null) {
                    continue;
                }
                Path published = publisher.publishedFile(p, config);
                if (!Files.exists(published)) {
                    continue;
                }
                DatasetFile data;
                try {
                    data = DatasetFile.read(published);
                } catch (IOException e) {
                    log.warn("Index PEP : {} illisible ({})", published, e.getMessage());
                    continue;
                }
                int n = write(p.getDatasetName(), data, out, today);
                if (n > 0) {
                    datasets++;
                    peps += n;
                    perDataset.put(p.getDatasetName(), n);
                }
            }
        }
        log.info("Index PEP pour {} : {} PEP de {} jeux", self.getDatasetName(), peps, datasets);
        return new Result(file, peps, datasets, SourceFileService.sha256(file), perDataset);
    }

    /** Écrit le reçu de l'index dans rawDir (archivé avec les réponses brutes). */
    public static Path writeReceipt(Result index, Path rawDir) throws IOException {
        // Sans date : deux index identiques donnent le même reçu, donc la même empreinte (exécution UNCHANGED).
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("indexSha256", index.sha256());
        receipt.put("pepCount", index.pepCount());
        receipt.put("datasetCount", index.datasetCount());
        ObjectNode per = receipt.putObject("perDataset");
        index.perDataset().forEach(per::put);
        Path file = rawDir.resolve(RECEIPT_NAME);
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardCharsets.UTF_8);
        return file;
    }

    /** Écrit les PEP d'un jeu ; renvoie leur nombre. */
    static int write(String dataset, DatasetFile data, BufferedWriter out, LocalDate today) throws IOException {
        // Mandats par titulaire : en cours ? date de fin la plus récente ?
        Map<String, boolean[]> current = new HashMap<>();
        Map<String, String> lastEnd = new HashMap<>();
        Map<String, List<JsonNode>> persons = new java.util.LinkedHashMap<>();     // ordre du fichier : index reproductible
        for (Map.Entry<String, DatasetFile.Entity> e : data.entities().entrySet()) {
            String schema = e.getValue().schema();
            if (!"Occupancy".equals(schema) && !"Person".equals(schema)) {
                continue;
            }
            JsonNode props = DatasetFile.propertiesOf(e.getValue());
            if ("Person".equals(schema)) {
                if (has(props.path("topics"), "role.pep")) {
                    persons.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(props);
                }
                continue;
            }
            String status = first(props.path("status"));
            String end = first(props.path("endDate"));
            for (JsonNode holder : props.path("holder")) {
                String h = holder.asText();
                boolean[] c = current.computeIfAbsent(h, k -> new boolean[] {false, false});
                c[1] = true;                                                   // au moins un mandat
                if ("current".equals(status) || (end == null && !"ended".equals(status))) {
                    c[0] = true;
                }
                if (end != null && (lastEnd.get(h) == null || end.compareTo(lastEnd.get(h)) > 0)) {
                    lastEnd.put(h, end);
                }
            }
        }
        int n = 0;
        for (Map.Entry<String, List<JsonNode>> e : persons.entrySet()) {
            String id = e.getKey();
            JsonNode props = e.getValue().get(0);
            String name = first(props.path("name"));
            if (name == null) {
                continue;
            }
            ObjectNode line = JSON.createObjectNode();
            line.put("dataset", dataset);
            line.put("id", id);
            line.set("names", strings(props.path("name"), props.path("alias")));
            put(line, "firstName", first(props.path("firstName")));
            put(line, "lastName", first(props.path("lastName")));
            put(line, "birthDate", first(props.path("birthDate")));
            line.set("position", strings(props.path("position")));
            line.set("country", strings(props.path("country"), props.path("nationality")));
            put(line, "sourceUrl", first(props.path("sourceUrl")));

            String status;
            String end = lastEnd.get(id);
            String noted = notedStatus(props.path("notes"));
            boolean[] c = current.get(id);
            if (noted != null) {
                status = noted;
            } else if (c == null || c[0]) {
                status = "ACTIVE_PEP";
            } else if (end == null) {
                status = "EXPIRED";
            } else {
                LocalDate until = endDate(end).plusMonths(12);
                status = today.isAfter(until) ? "EXPIRED" : "COOLING";
            }
            line.put("status", status);
            put(line, "endDate", c != null && !c[0] ? end : null);
            out.write(JSON.writeValueAsString(line));
            out.newLine();
            n++;
        }
        return n;
    }

    /** Statut noté par le plugin (« pep_status: COOLING … »), ou null. */
    private static String notedStatus(JsonNode notes) {
        for (JsonNode note : notes) {
            String t = note.asText();
            if (t.startsWith("pep_status:")) {
                String s = t.substring("pep_status:".length()).trim().split("[\\s(]", 2)[0].toUpperCase();
                if (s.equals("ACTIVE_PEP") || s.equals("COOLING") || s.equals("EXPIRED")) {
                    return s;
                }
            }
        }
        return null;
    }

    /** « 2021 », « 2021-12 » ou « 2021-12-31 » : dernier jour connu. */
    static LocalDate endDate(String raw) {
        String s = raw.trim();
        try {
            if (s.matches("\\d{4}")) {
                return LocalDate.of(Integer.parseInt(s), 12, 31);
            }
            if (s.matches("\\d{4}-\\d{2}")) {
                return java.time.YearMonth.parse(s).atEndOfMonth();
            }
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (RuntimeException e) {
            return LocalDate.MIN.plusYears(1);           // date illisible : traitée comme très ancienne
        }
    }

    private static boolean has(JsonNode array, String value) {
        for (JsonNode v : array) {
            if (value.equals(v.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String first(JsonNode array) {
        return array.isArray() && !array.isEmpty() && !array.get(0).asText().isBlank() ? array.get(0).asText() : null;
    }

    private static ArrayNode strings(JsonNode... arrays) {
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode a : arrays) {
            for (JsonNode v : a) {
                if (!v.asText().isBlank()) {
                    result.add(v.asText());
                }
            }
        }
        return result;
    }

    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
