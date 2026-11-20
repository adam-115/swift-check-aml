package com.swiftcheck.aml.plugins.depep.dip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pf4j.Extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_dip</b> – Personnes de l'API DIP du Bundestag (Dokumentations- und Informationssystem für
 * Parlamentsmaterialien) pour la législature en cours : membres du gouvernement fédéral (Bundeskanzler, Bundesminister,
 * Staatsminister, Staatssekretäre), membres et suppléants du Bundesrat (ministres des Länder), présidence du Bundestag,
 * Wehrbeauftragter, Beauftragte der Bundesregierung (Story 1, liens 1-2, classe A.2).
 *
 * <p>Source officielle. Les simples députés (fonction {@code MdB}) sont laissés à {@code de_pep_bundestag} (paramètre
 * {@code excludeFunctions}). Le DIP ne date pas les fonctions : la date de dernière activité documentée ({@code datum})
 * décide du statut. Activité récente ({@code activeMonths}) : fonction en cours ; plus ancienne : statut « unknown »,
 * fonction non confirmée ; plus ancienne que {@code monthsAfterEnd} : la personne n'est pas reprise (le gouvernement
 * sortant de mars-mai 2025 figure encore dans la législature 21). Libellés des fonctions en allemand, sans traduction.
 */
@Extension
public class DePepDipParser implements Iparser {

    static final String ID_PREFIX = "de-pep-dip";
    static final String TOPIC = "role.pep";
    static final int DEFAULT_WAHLPERIODE = 21;
    static final int DEFAULT_ACTIVE_MONTHS = 6;
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final String DEFAULT_EXCLUDED = "MdB";

    private static final Pattern ACADEMIC_TITLE = Pattern.compile("^((?:(?:Prof|Dr|Dr\\.-Ing|h\\.\\s?c)\\.?\\s+)+)");

    /** Abréviations du DIP développées en allemand (aucune traduction). Inconnue : gardée telle quelle. */
    static final Map<String, String> FUNCTIONS = Map.ofEntries(
            Map.entry("MdB", "Mitglied des Deutschen Bundestages"),
            Map.entry("MdBR", "Mitglied des Bundesrates"),
            Map.entry("Stellv. MdBR", "Stellvertretendes Mitglied des Bundesrates"),
            Map.entry("Bundesratspräs.", "Präsident des Bundesrates"),
            Map.entry("Bundespräs.", "Bundespräsident"),
            Map.entry("Bundeskanzl.", "Bundeskanzler"),
            Map.entry("Bundesmin.", "Bundesminister"),
            Map.entry("Bundesmin. f. besondere Aufgaben", "Bundesminister für besondere Aufgaben"),
            Map.entry("Bundesmin. f. besondere Aufgaben und Ch. des Bundeskanzleramtes",
                    "Bundesminister für besondere Aufgaben und Chef des Bundeskanzleramtes"),
            Map.entry("Staatsmin.", "Staatsminister"),
            Map.entry("Parl. Staatssekr.", "Parlamentarischer Staatssekretär"),
            Map.entry("Staatssekr.", "Staatssekretär"),
            Map.entry("Beauftr. d. Bundesregierung", "Beauftragter der Bundesregierung"),
            Map.entry("Ch.", "Chef"),
            Map.entry("Stellv. Ch.", "Stellvertretender Chef"),
            Map.entry("Bundestagspräs.", "Präsident des Deutschen Bundestages"),
            Map.entry("Bundestagsvizepräs.", "Vizepräsident des Deutschen Bundestages"),
            Map.entry("Wehrbeauftr. d. Dt. Bundestages", "Wehrbeauftragter des Deutschen Bundestages"));

    private static final Set<String> BUNDESRAT = Set.of("MdBR", "Stellv. MdBR", "Bundesratspräs.");
    private static final Set<String> BUNDESTAG = Set.of("MdB", "Bundestagspräs.", "Bundestagsvizepräs.",
            "Wehrbeauftr. d. Dt. Bundestages");

    /** Une personne du DIP, telle que la page de liste la donne. */
    record DipPerson(String id, String firstName, String lastName, String title, String function, String extra,
            String ressort, String land, String group, String lastActivity, String pageSha) {
    }

    record Report(int listed, int excluded, int superseded, int duplicates, int nowExcluded, int corrected, int activityErrors, int current,
            int unconfirmed, int tooOld, Map<String, Integer> byFunction, int numFound, List<String> pageHashes) {
        String message(int wahlperiode, int activeMonths, int monthsAfterEnd) {
            return String.format("DIP législature %d : %d personnes lues (%d annoncées), %d simples députés laissés à "
                    + "de_pep_bundestag, dont %d dont la fiche garde une ancienne fonction (dernière activité en tant que "
                    + "député) ; %d fonctions corrigées d'après la dernière activité ; %d fonctions en cours (activité < %d "
                    + "mois), %d non confirmées (activité entre %d et %d mois), %d écartées (aucune activité depuis plus de "
                    + "%d mois) ; %d fonctions quittées (fiche DIP plus récente pour la même personne), %d fiches en double "
                    + "ignorées. Par fonction : %s.%s SHA-256 des %d pages : %s.", wahlperiode, listed, numFound,
                    excluded + nowExcluded, nowExcluded, corrected, current, activeMonths, unconfirmed, activeMonths,
                    monthsAfterEnd, tooOld, monthsAfterEnd, superseded, duplicates, byFunction,
                    activityErrors == 0 ? "" : " Dernière activité illisible pour " + activityErrors
                            + " personnes (fonction de la fiche gardée).",
                    pageHashes.size(), pageHashes.stream().map(h -> h.substring(0, 12)).collect(Collectors.joining(", ")));
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        if (ctx.rawDir() == null) {
            throw new IllegalStateException("Aucun dossier de données brutes.");
        }
        int wp = intParam(p, "wahlperiode", DEFAULT_WAHLPERIODE);
        DipApi.Download download = load(ctx, p, wp);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int active = intParam(p, "activeMonths", DEFAULT_ACTIVE_MONTHS);
        int after = intParam(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
        String endpoint = p.getOrDefault("endpoint", DipApi.DEFAULT_ENDPOINT).trim();
        DipApi.Activities activities = offline(ctx) ? DipApi.savedActivities(ctx.inputFile())
                : "false".equalsIgnoreCase(p.get("checkActivity")) ? null
                : DipApi.liveActivities(DipApi.http(apiKey(p)), endpoint, ctx.rawDir(), intParam(p, "delayMs", 500));
        Report report = convert(download, activities, wp, excluded(p), LocalDate.now(), active, after, endpoint, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message(wp, active, after));
    }

    /** Fichier d'entrée = dossier de pages déjà lues (tests, reprise) : aucune lecture réseau. */
    private static boolean offline(ParserContext ctx) {
        return ctx.inputFile() != null && Files.isDirectory(ctx.inputFile());
    }

    private static String apiKey(Map<String, String> p) {
        return p.getOrDefault("apiKey", "").isBlank() ? DipApi.DEFAULT_API_KEY : p.get("apiKey").trim();
    }

    private static DipApi.Download load(ParserContext ctx, Map<String, String> p, int wp) throws Exception {
        if (offline(ctx)) {
            return DipApi.readSaved(ctx.inputFile(), wp);
        }
        return DipApi.download(DipApi.http(apiKey(p)), p.getOrDefault("endpoint", DipApi.DEFAULT_ENDPOINT).trim(), wp,
                ctx.rawDir(), intParam(p, "delayMs", 500));
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        int wp = intParam(p, "wahlperiode", DEFAULT_WAHLPERIODE);
        Path dir = ctx.inputFile() != null && Files.isDirectory(ctx.inputFile()) ? ctx.inputFile() : ctx.rawDir();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(DipApi.readSaved(dir, wp), DipApi.savedActivities(dir), wp, excluded(p), LocalDate.now(),
                intParam(p, "activeMonths", DEFAULT_ACTIVE_MONTHS), intParam(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END),
                p.getOrDefault("endpoint", DipApi.DEFAULT_ENDPOINT).trim(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    static Report convert(DipApi.Download download, DipApi.Activities activities, int wp, Set<String> excludedFunctions, LocalDate today,
            int activeMonths, int monthsAfterEnd, String endpoint, FtmDatasetBuilder dataset) {
        String activeSince = today.minusMonths(activeMonths).toString();
        String oldest = today.minusMonths(monthsAfterEnd).toString();
        String retrieved = download.retrievedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
        Map<String, DipPerson> persons = new LinkedHashMap<>();
        for (DipApi.Page page : download.pages()) {
            for (JsonNode doc : page.documents()) {
                DipPerson person = person(doc, page.sha256());
                if (person != null) {
                    persons.putIfAbsent(person.id(), person);
                }
            }
        }
        // Le DIP ouvre une nouvelle fiche quand une personne change de fonction et garde l'ancienne : pour un même nom,
        // la fiche à l'activité la plus récente porte la fonction actuelle, les autres des fonctions quittées.
        Map<String, DipPerson> newestByName = new java.util.HashMap<>();
        for (DipPerson person : persons.values()) {
            newestByName.merge(nameKey(person), person, (a, b) -> newer(a, b) ? a : b);
        }
        int excluded = 0;
        int superseded = 0;
        int duplicates = 0;
        int nowExcluded = 0;
        int corrected = 0;
        int activityErrors = 0;
        int current = 0;
        int unconfirmed = 0;
        int tooOld = 0;
        Map<String, Integer> byFunction = new TreeMap<>();
        for (DipPerson person : persons.values()) {
            if (excludedFunctions.contains(person.function())) {
                excluded++;
                continue;
            }
            String last = person.lastActivity();
            if (last == null || last.compareTo(oldest) < 0) {
                tooOld++;
                continue;
            }
            DipPerson newest = newestByName.get(nameKey(person));
            if (newest != person) {
                if (samePosition(newest, person)) {
                    duplicates++;
                    continue;
                }
                superseded++;
                byFunction.merge(person.function(), 1, Integer::sum);
                String holder = excludedFunctions.contains(newest.function()) ? person.id() : newest.id();
                write(person, holder, wp, "ended", "fonction quittée : le DIP a ouvert une fiche plus récente pour cette "
                        + "personne (dernière activité de cette fonction le " + last + ")", retrieved, endpoint, dataset);
                continue;
            }
            if (activities != null) {
                try {
                    String[] latest = activities.latest(person.id());
                    String[] role = latest == null ? null : roleOf(latest[0], person.lastName());
                    // La présidence du Bundestag agit en séance comme député (« MdB ») : sa fonction n'est pas remise en cause.
                    if (role != null && BUNDESTAG.contains(person.function())) {
                        role = null;
                    }
                    if (role != null && excludedFunctions.contains(role[0])) {
                        nowExcluded++;
                        continue;
                    }
                    // La dernière activité est datée, la fiche non : en cas d'écart (fonction, précision, ministère ou
                    // Land), la version de l'activité l'emporte (ex. « Regierende Bürgermeisterin » restée sur la fiche).
                    boolean bundesrat = role != null && BUNDESRAT.contains(role[0]);
                    if (role != null && !(role[0].equals(person.function())
                            && java.util.Objects.equals(role[1], person.extra())
                            && java.util.Objects.equals(role[2], bundesrat ? person.land() : person.ressort()))) {
                        corrected++;
                        person = new DipPerson(person.id(), person.firstName(), person.lastName(), person.title(), role[0],
                                role[1], bundesrat ? null : role[2], bundesrat ? role[2] : null, person.group(),
                                last, person.pageSha());
                    }
                } catch (java.io.IOException e) {
                    activityErrors++;
                }
            }
            boolean active = last.compareTo(activeSince) >= 0;
            if (active) {
                current++;
            } else {
                unconfirmed++;
            }
            byFunction.merge(person.function(), 1, Integer::sum);
            write(person, person.id(), wp, active ? "current" : "unknown", "dernière activité documentée dans le DIP : "
                    + last + (active ? "" : " (fonction non confirmée en cours)"), retrieved, endpoint, dataset);
        }
        return new Report(persons.size(), excluded, superseded, duplicates, nowExcluded, corrected, activityErrors, current, unconfirmed, tooOld,
                byFunction, download.numFound(),
                download.pages().stream().map(DipApi.Page::sha256).toList());
    }

    static DipPerson person(JsonNode doc, String pageSha) {
        String id = text(doc, "id");
        String lastName = text(doc, "nachname");
        if (id == null || lastName == null) {
            return null;
        }
        String firstName = text(doc, "vorname");
        String title = null;
        String titel = text(doc, "titel");
        if (titel != null && firstName != null) {
            int at = titel.indexOf(firstName);
            if (at > 0) {
                Matcher m = ACADEMIC_TITLE.matcher(titel.substring(0, at));
                if (m.find()) {
                    title = m.group(1).trim();
                }
            }
        }
        return new DipPerson(id, firstName, lastName, title, first(doc, "funktion"), first(doc, "funktionszusatz"),
                first(doc, "ressort"), first(doc, "bundesland"), first(doc, "fraktion"), text(doc, "datum"), pageSha);
    }

    /**
     * Fonction lue dans un titre du DIP « Prénom Nom, Funktion (Zusatz), Ressort|Land|Fraktion » :
     * {fonction, précision ou null, complément ou null}, ou {@code null} si le titre n'a pas cette forme.
     */
    static String[] roleOf(String title, String lastName) {
        if (title == null || lastName == null) {
            return null;
        }
        int at = title.indexOf(lastName + ", ");
        if (at < 0) {
            return null;
        }
        String rest = title.substring(at + lastName.length() + 2).trim();
        int paren = rest.indexOf(" (");
        int comma = rest.indexOf(", ");
        String function;
        String extra = null;
        String tail = null;
        if (paren >= 0 && (comma < 0 || paren < comma)) {
            function = rest.substring(0, paren).trim();
            int close = rest.indexOf(')', paren);
            extra = close < 0 ? rest.substring(paren + 2).trim() : rest.substring(paren + 2, close).trim();
            String after = close < 0 ? "" : rest.substring(close + 1).trim();
            tail = after.startsWith(",") ? after.substring(1).trim() : null;
        } else if (comma >= 0) {
            function = rest.substring(0, comma).trim();
            tail = rest.substring(comma + 2).trim();
        } else {
            function = rest;
        }
        if (function.isEmpty()) {
            return null;
        }
        return new String[] {function, extra == null || extra.isEmpty() ? null : extra,
                tail == null || tail.isEmpty() ? null : tail};
    }

    /** Libellé allemand de la fonction : abréviation développée, précision du DIP, ministère ou Land. */
    static String positionName(DipPerson p) {
        StringBuilder sb = new StringBuilder(FUNCTIONS.getOrDefault(p.function(), p.function()));
        if (p.extra() != null) {
            sb.append(" (").append(p.extra()).append(')');
        }
        if (p.ressort() != null) {
            sb.append(", ").append(p.ressort());
        }
        if (p.land() != null) {
            sb.append(", ").append(p.land());
        }
        return sb.toString();
    }

    static String nameKey(DipPerson p) {
        return ((p.firstName() == null ? "" : p.firstName()) + " " + p.lastName()).toLowerCase(java.util.Locale.ROOT).trim();
    }

    /** Fiche la plus récente : dernière activité, puis numéro de fiche le plus grand (fiche ouverte plus tard). */
    static boolean newer(DipPerson a, DipPerson b) {
        int c = String.valueOf(a.lastActivity()).compareTo(String.valueOf(b.lastActivity()));
        if (c != 0) {
            return c > 0;
        }
        try {
            return Long.parseLong(a.id()) > Long.parseLong(b.id());
        } catch (NumberFormatException e) {
            return a.id().compareTo(b.id()) > 0;
        }
    }

    private static boolean samePosition(DipPerson a, DipPerson b) {
        return positionName(a).equals(positionName(b));
    }

    private static void write(DipPerson p, String personKey, int wp, String status, String note, String retrieved,
            String endpoint, FtmDatasetBuilder dataset) {
        String apiUrl = (endpoint.endsWith("/") ? endpoint : endpoint + "/") + "person/" + p.id();
        String positionName = positionName(p);
        String name = (p.firstName() == null ? "" : p.firstName() + " ") + p.lastName();

        FtmEntity person = dataset.newEntity("Person", personKey);
        person.addValue("name", name);
        person.addValue("firstName", p.firstName());
        person.addValue("lastName", p.lastName());
        person.addValue("title", p.title());
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("political", p.group());
        person.addValue("position", positionName);
        person.addValue("sourceUrl", apiUrl);
        person.addValue("retrievedAt", retrieved);
        person.addValue("publisher", "Deutscher Bundestag – DIP");
        person.addValue("notes", "source_type: official_api ; DIP-Person " + p.id() + " ; evidence: " + apiUrl
                + " lu le " + retrieved + " (UTC), SHA-256 de la page " + p.pageSha());
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", positionName);
        position.addValue("name", positionName);
        position.addValue("country", "de");
        position.addValue("subnationalArea", p.land());
        if (BUNDESRAT.contains(p.function())) {
            position.addValue("organization", "Bundesrat");
            position.addValue("topics", "gov.state");
            position.addValue("topics", "gov.legislative");
        } else if (BUNDESTAG.contains(p.function())) {
            position.addValue("organization", "Deutscher Bundestag");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.legislative");
        } else {
            position.addValue("organization", p.ressort() != null ? p.ressort() : "Bundesregierung");
            position.addValue("topics", "gov.national");
            position.addValue("topics", "gov.executive");
        }
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", p.id(), "wp" + wp);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", status);
        occupancy.addValue("politicalGroup", p.group());
        occupancy.addValue("description", "Wahlperiode " + wp + " ; " + note);
        occupancy.addValue("sourceUrl", apiUrl);
        dataset.addEntity(occupancy);
    }

    static Set<String> excluded(Map<String, String> p) {
        String raw = p.getOrDefault("excludeFunctions", DEFAULT_EXCLUDED);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static String text(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String first(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        if (n != null && n.isArray() && n.size() > 0) {
            String s = n.get(0).asText().trim();
            return s.isEmpty() ? null : s;
        }
        return text(doc, field);
    }

    private static int intParam(Map<String, String> p, String key, int def) {
        try {
            return Integer.parseInt(p.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
