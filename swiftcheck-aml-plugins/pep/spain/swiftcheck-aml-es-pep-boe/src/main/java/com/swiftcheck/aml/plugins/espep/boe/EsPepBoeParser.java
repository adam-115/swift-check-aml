package com.swiftcheck.aml.plugins.espep.boe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.espep.boe.BoeApi.Item;
import com.swiftcheck.aml.plugins.espep.boe.BoeFunctions.Function;
import com.swiftcheck.aml.plugins.espep.boe.BoeTitles.Event;
import com.swiftcheck.aml.plugins.espep.boe.BoeTitles.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>es_pep_boe</b> – nominations et cessations publiées au Bulletin officiel de l'État (BOE), section II.A
 * « Autoridades y personal – Nombramientos, situaciones e incidencias » : Gouvernement, secrétaires d'État, hauts
 * cargos de l'administration (loi 3/2015), magistrats des hautes juridictions, procureurs en chef, Banque d'Espagne,
 * ambassadeurs et officiers généraux (blocs ES-a, ES-c, ES-d, ES-e et ES-f ; loi 10/2010, art. 14).
 *
 * <p>Source : API de données ouvertes du BOE (voir {@link BoeApi}). Les décrets de nomination suivent une formule
 * stable (voir {@link BoeTitles}) : la cessation ferme la fonction avec sa vraie date. Le titre d'un décret qui nomme
 * tous les ministres d'un coup ne donne pas les noms : le texte est alors téléchargé. Type {@code PLUGIN_MANAGED}.
 * Paramètres (tous facultatifs) :
 * <pre>{@code
 * {"archiveMonths": "26", "startDate": "2024-09-01", "sections": "2A", "delayMs": "250", "monthsAfterEnd": "24"}
 * }</pre>
 * {@code startDate} l'emporte sur {@code archiveMonths}. Le BOE ne donne que les changements : un titulaire en
 * fonction depuis avant la période n'y figure pas, les parsers par institution (Congreso, Banque d'Espagne…) les
 * couvrent.
 */
@Extension
public class EsPepBoeParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(EsPepBoeParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "es-pep-boe";
    static final String TOPIC = "role.pep";

    static final int DEFAULT_ARCHIVE_MONTHS = 26;
    static final int DEFAULT_MONTHS_AFTER_END = 24;
    static final long DEFAULT_DELAY_MS = 250;
    static final String DEFAULT_SECTIONS = "2A";

    /** Décret qui nomme plusieurs personnes à la fois : les noms ne sont que dans le texte. */
    static final Pattern MULTI_TITLE = Pattern.compile(
            "(?iu)por el que se nombran (?:Ministros|Ministras)(?: y Ministras)?(?: del Gobierno)?\\.?$");
    private static final Pattern MULTI_LINE = Pattern.compile(
            "(?u)(Ministr[oa]\\b[^.]*?)\\s+a\\s+(?:don|doña)\\s+([^.]+?)\\.");

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int days, int articles, int events, int persons, int current, int ended, int dropped,
            int multiDocuments, Map<String, Integer> byCategory) {

        String message() {
            return String.format("%d jours de BOE, %d articles, %d nominations ou cessations reconnues, %d personnes "
                    + "(%d en fonction, %d sorties, %d écartées car sorties depuis trop longtemps). Par bloc : %s.",
                    days, articles, events, persons, current, ended, dropped, byCategory);
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        LocalDate today = LocalDate.now();
        LocalDate from = p.containsKey("startDate") ? LocalDate.parse(p.get("startDate").trim())
                : today.minusMonths(number(p, "archiveMonths", DEFAULT_ARCHIVE_MONTHS));
        Set<String> sections = sections(p);
        long delay = number(p, "delayMs", DEFAULT_DELAY_MS);
        BoeApi.Fetcher fetcher = BoeApi.httpFetcher();

        BoeApi.Download download = BoeApi.download(fetcher, BoeApi.DEFAULT_SUMMARY_URL, from, today, sections, delay,
                ctx.rawDir());
        downloadMultiDocuments(fetcher, delay, ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), today, (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END),
                dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        String message = report.message();
        if (!download.failedDays().isEmpty()) {
            message += String.format(" %d jours non lus : %s.", download.failedDays().size(),
                    String.join(", ", download.failedDays().subList(0, Math.min(10, download.failedDays().size()))));
        }
        return dataset.toConversionResult().withMessage(message);
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    private static void downloadMultiDocuments(BoeApi.Fetcher fetcher, long delay, Path rawDir) throws Exception {
        for (List<Item> items : BoeApi.read(rawDir).values()) {
            for (Item item : items) {
                if (MULTI_TITLE.matcher(item.title()).find()) {
                    BoeApi.document(fetcher, BoeApi.DEFAULT_DOCUMENT_URL, item.id(), delay, rawDir);
                }
            }
        }
    }

    /** Un mandat reconstitué : une personne dans une fonction précise. */
    static final class Mandate {
        String personKey;
        String name;
        Function function;
        String cargo;
        String label;
        String department;
        LocalDate start;
        LocalDate end;
        final Set<String> sources = new LinkedHashSet<>();
    }

    Report convert(Path rawDir, LocalDate today, int monthsAfterEnd, FtmDatasetBuilder dataset) throws Exception {
        BoeFunctions functions = BoeFunctions.load();
        Map<LocalDate, List<Item>> byDay = BoeApi.read(rawDir);
        if (byDay.isEmpty()) {
            throw new IllegalStateException("Aucun sommaire du BOE dans " + rawDir + " : le téléchargement a échoué.");
        }
        int articles = 0;
        int multiDocuments = 0;
        List<Event> events = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Item>> day : byDay.entrySet()) {
            for (Item item : day.getValue()) {
                articles++;
                List<Event> read = BoeTitles.read(item.id(), item.title(), item.department(), day.getKey());
                if (read.isEmpty() && MULTI_TITLE.matcher(item.title()).find()) {
                    read = multiDocument(rawDir, item, day.getKey());
                    multiDocuments += read.isEmpty() ? 0 : 1;
                }
                events.addAll(read);
            }
        }
        events.sort(Comparator.comparing(Event::date).thenComparing(Event::id));

        List<Mandate> mandates = buildMandates(events, functions);
        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        LocalDate oldestEnd = today.minusMonths(monthsAfterEnd);
        int current = 0;
        int ended = 0;
        int dropped = 0;
        for (Mandate m : mandates) {
            if (m.end != null && m.end.isBefore(oldestEnd)) {
                dropped++;
                continue;
            }
            boolean isEnded = m.end != null && m.end.isBefore(today);
            if (isEnded) {
                ended++;
            } else {
                current++;
            }
            byCategory.merge(m.function.category(), 1, Integer::sum);
            FtmEntity person = persons.computeIfAbsent(m.personKey, k -> newPerson(m, dataset));
            person.addValue("position", (isEnded ? "Ancien : " : "") + m.label);
            m.sources.forEach(id -> person.addValue("sourceUrl", sourceUrl(id)));
            if (m.function.topics().contains("role.diplo")) {
                person.addValue("topics", "role.diplo");
            }
            addMandate(m, isEnded, person, dataset);
        }
        persons.values().forEach(dataset::addEntity);
        if (persons.isEmpty()) {
            throw new IllegalStateException("Aucune personne trouvée dans " + articles + " articles du BOE : "
                    + "la rédaction des titres ou la liste des fonctions (boe-functions.json) a probablement changé.");
        }
        Report report = new Report(byDay.size(), articles, events.size(), persons.size(), current, ended, dropped,
                multiDocuments, byCategory);
        log.info(report.message());
        return report;
    }

    /** Noms d'un décret qui nomme plusieurs ministres : « Ministra de Defensa a doña María Margarita Robles ». */
    static List<Event> multiDocument(Path rawDir, Item item, LocalDate publication) throws java.io.IOException {
        Path file = rawDir.resolve(BoeApi.DOCUMENT_PREFIX + item.id() + ".xml");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8).replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ");
        LocalDate date = BoeTitles.documentDate(item.title(), publication);
        List<Event> events = new ArrayList<>();
        Matcher m = MULTI_LINE.matcher(text);
        while (m.find()) {
            events.add(new Event(Kind.APPOINT, m.group(2).trim(), m.group(1).trim(), date, item.id(), item.title(),
                    item.department()));
        }
        return events;
    }

    /**
     * Reconstitue les mandats : une nomination ouvre un mandat, la cessation de la même fonction le ferme à sa date.
     * Une cessation sans nomination dans la période donne un mandat sans début. Un grade militaire remplace le
     * précédent.
     */
    static List<Mandate> buildMandates(List<Event> events, BoeFunctions functions) {
        Map<String, Mandate> open = new LinkedHashMap<>();
        List<Mandate> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Event e : events) {
            if (!seen.add(e.id() + "|" + e.kind() + "|" + BoeTitles.key(e.name()) + "|" + BoeTitles.key(e.cargo()))) {
                continue;
            }
            var match = functions.match(e.cargo());
            if (match.isEmpty()) {
                continue;
            }
            Function f = match.get();
            String personKey = BoeTitles.key(e.name());
            String label = f.label(e.cargo());
            String mandateKey = personKey + "|" + f.key() + "|" + BoeTitles.key(label);
            if (e.kind() == Kind.APPOINT) {
                if (f.key().equals("militar")) {
                    open.entrySet().removeIf(entry -> {
                        Mandate previous = entry.getValue();
                        boolean replaced = previous.personKey.equals(personKey) && previous.function.group().equals(f.group());
                        if (replaced) {
                            previous.end = e.date();
                        }
                        return replaced;
                    });
                }
                if (open.containsKey(mandateKey)) {
                    open.get(mandateKey).sources.add(e.id());
                    continue;
                }
                Mandate m = newMandate(e, f, personKey, label);
                m.start = e.date();
                open.put(mandateKey, m);
                all.add(m);
            } else {
                Mandate m = open.remove(mandateKey);
                if (m == null) {
                    m = newMandate(e, f, personKey, label);
                    all.add(m);
                }
                m.end = e.date();
                m.sources.add(e.id());
            }
        }
        return all;
    }

    private static Mandate newMandate(Event e, Function f, String personKey, String label) {
        Mandate m = new Mandate();
        m.personKey = personKey;
        m.name = e.name();
        m.function = f;
        m.cargo = e.cargo();
        m.label = label;
        m.department = e.department();
        m.sources.add(e.id());
        return m;
    }

    private static FtmEntity newPerson(Mandate m, FtmDatasetBuilder dataset) {
        // ID = nom sans accents : les titres ne donnent ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", m.personKey);
        person.addValue("name", m.name);
        person.addValue("country", "es");
        return person;
    }

    private static void addMandate(Mandate m, boolean ended, FtmEntity person, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", BoeTitles.key(m.label));
        position.addValue("name", m.label);
        position.addValue("country", "es");
        position.addValue("organization", m.function.organization());
        m.function.topics().stream().filter(t -> t.startsWith("gov.")).forEach(t -> position.addValue("topics", t));
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.personKey, m.function.key(), BoeTitles.key(m.label),
                m.start == null ? "" : m.start.toString());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", m.start == null ? null : m.start.toString());
        occupancy.addValue("endDate", m.end == null ? null : m.end.toString());
        occupancy.addValue("status", ended ? "ended" : "current");
        occupancy.addValue("description", m.department);
        m.sources.forEach(id -> occupancy.addValue("sourceUrl", sourceUrl(id)));
        dataset.addEntity(occupancy);
    }

    static String sourceUrl(String id) {
        return "https://www.boe.es/diario_boe/txt.php?id=" + id;
    }

    private static Set<String> sections(Map<String, String> p) {
        Set<String> sections = new LinkedHashSet<>();
        for (String s : p.getOrDefault("sections", DEFAULT_SECTIONS).split("[,\\s]+")) {
            if (!s.isBlank()) {
                sections.add(s.trim());
            }
        }
        return sections;
    }

    private static long number(Map<String, String> p, String key, long fallback) {
        try {
            return Long.parseLong(p.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
