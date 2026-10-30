package com.swiftcheck.aml.plugins.frpep.jorf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.jorf.JorfArticles.Article;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Event;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Institution;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_jorf</b> – Hautes fonctions nommées au Journal officiel : membres du Conseil d'État, de la Cour de
 * cassation et de la Cour des comptes, ambassadeurs. Équivalent français de {@code lu_pep_legilux}.
 *
 * <p>Catégories <b>FR-d</b> (Conseil d'État, Cour de cassation), <b>FR-e</b> (Cour des comptes) et <b>FR-f</b>
 * (ambassadeurs) du II de l'article R. 561-18 du CMF. Source : open data du Journal officiel publié par la DILA
 * (Licence Ouverte), lu par {@link JorfDownloader} ; les nominations et départs sont lus par
 * {@link NominationReader}.
 *
 * <p>Paramètres :
 * <pre>{@code
 * {"cacheDir": "D:/swiftcheck/cache/jorf", "since": "1990-01-01", "monthsAfterEnd": "12",
 *  "ambassadorMaxYears": "6", "extraordinaryMaxYears": "5", "cassationMaxYears": "15", "memberMaxYears": "40",
 *  "delayMs": "300"}
 * }</pre>
 * {@code cacheDir} (obligatoire) : dossier des extraits de la DILA conservés entre deux exécutions (voir
 * {@link JorfDownloader}). {@code indexUrl} : adresse de l'open data, par défaut {@value #DEFAULT_INDEX_URL}.
 *
 * <p>Reconstitution des mandats, par personne et par institution (par poste pour un ambassadeur), dans l'ordre des
 * dates d'effet :
 * <ul>
 *   <li>une nomination ouvre un mandat ; une promotion dans la même institution (maître des requêtes → conseiller
 *       d'État) le prolonge et change la fonction affichée ;</li>
 *   <li>un départ (retraite, nomination ailleurs) le ferme ; la nomination d'un ambassadeur ferme le mandat de son
 *       prédécesseur au même poste et son propre poste précédent ;</li>
 *   <li>un maintien en fonction fixe la fin prévue.</li>
 * </ul>
 * Sans fin connue, le statut est {@code unknown}, comme dans {@code lu_pep_legilux} : un décès ou un départ sans
 * décret n'apparaît pas au JO. Un mandat sans fin connue est écarté au-delà d'une durée propre à l'institution
 * ({@link Settings#maxYearsWithoutEnd}).
 */
@Extension
public class FrPepJorfParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepJorfParser.class);

    static final String ID_PREFIX = "fr-pep-jorf";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_INDEX_URL = "https://echanges.dila.gouv.fr/OPENDATA/JORF/";
    static final String DEFAULT_SINCE = "1990-01-01";

    /** Nombre maximal de textes non lus cités dans le message de l'exécution. */
    private static final int MAX_LISTED = 20;

    /** Réglages de la conversion, lus dans les paramètres du parser. */
    record Settings(LocalDate since, int monthsAfterEnd, int ambassadorMaxYears, int extraordinaryMaxYears,
            int cassationMaxYears, int memberMaxYears) {

        static Settings of(Map<String, String> p) {
            return new Settings(LocalDate.parse(p.getOrDefault("since", DEFAULT_SINCE).trim()),
                    integer(p, "monthsAfterEnd", 12), integer(p, "ambassadorMaxYears", 6),
                    integer(p, "extraordinaryMaxYears", 5), integer(p, "cassationMaxYears", 15),
                    integer(p, "memberMaxYears", 40));
        }

        /**
         * Durée au-delà de laquelle un mandat sans fin connue est écarté : un poste d'ambassadeur dure 3 à 4 ans, une
         * nomination « en service extraordinaire » 4 ou 5 ans ; on entre à la Cour de cassation en fin de carrière
         * (limite d'âge vers 68 ans), alors qu'on entre au Conseil d'État ou à la Cour des comptes vers 25 ans.
         */
        int maxYearsWithoutEnd(Institution institution, String function) {
            if (function != null && JorfArticles.fold(function).contains("service extraordinaire")) {
                return extraordinaryMaxYears;
            }
            return switch (institution) {
                case AMBASSADE -> ambassadorMaxYears;
                case COUR_CASSATION -> cassationMaxYears;
                default -> memberMaxYears;
            };
        }
    }

    /** Fonction affichée : motif sur la fonction écrite (sans accents, minuscules) → libellé. */
    private record Role(Pattern pattern, String label) {
    }

    private static final Map<Institution, List<Role>> ROLES = Map.of(
            Institution.CONSEIL_ETAT, List.of(
                    role("vice-president\\w* du conseil d'etat", "Vice-président du Conseil d'État"),
                    role("president\\w* (?:adjoint\\w* )?de (?:la )?section", "Président de section au Conseil d'État"),
                    role("conseill\\w* d'etat en service extraordinaire", "Conseiller d'État en service extraordinaire"),
                    role("conseill\\w* d'etat", "Conseiller d'État"),
                    role("maitre(?:sse)?s? des requetes en service extraordinaire",
                            "Maître des requêtes en service extraordinaire au Conseil d'État"),
                    role("maitre(?:sse)?s? des requetes", "Maître des requêtes au Conseil d'État"),
                    role("rapporteure? publi(?:c|que)", "Rapporteur public au Conseil d'État"),
                    role("auditeur|auditrice", "Auditeur au Conseil d'État")),
            Institution.COUR_CASSATION, List.of(
                    role("premier\\w* president\\w*", "Premier président de la Cour de cassation"),
                    role("procureur\\w* general\\w*", "Procureur général près la Cour de cassation"),
                    role("premier\\w* avocat\\w* general\\w*", "Premier avocat général à la Cour de cassation"),
                    role("avocat\\w* general\\w* referendaire", "Avocat général référendaire à la Cour de cassation"),
                    role("avocat\\w* general\\w*", "Avocat général à la Cour de cassation"),
                    role("president\\w* de chambre", "Président de chambre à la Cour de cassation"),
                    role("conseill\\w* referendaire", "Conseiller référendaire à la Cour de cassation"),
                    role("conseill\\w*", "Conseiller à la Cour de cassation"),
                    role("auditeur|auditrice", "Auditeur à la Cour de cassation")),
            Institution.COUR_COMPTES, List.of(
                    role("premier\\w* president\\w*", "Premier président de la Cour des comptes"),
                    role("procureur\\w* general\\w*", "Procureur général près la Cour des comptes"),
                    role("premier\\w* avocat\\w* general\\w*", "Premier avocat général à la Cour des comptes"),
                    role("avocat\\w* general\\w*", "Avocat général à la Cour des comptes"),
                    role("president\\w* de chambre", "Président de chambre à la Cour des comptes"),
                    role("conseill\\w* maitre\\w* en service extraordinaire",
                            "Conseiller maître en service extraordinaire à la Cour des comptes"),
                    role("conseill\\w* maitre", "Conseiller maître à la Cour des comptes"),
                    role("conseill\\w* referendaire\\w* en service extraordinaire",
                            "Conseiller référendaire en service extraordinaire à la Cour des comptes"),
                    role("conseill\\w* referendaire", "Conseiller référendaire à la Cour des comptes"),
                    role("auditeur|auditrice", "Auditeur à la Cour des comptes")));

    private static final Map<Institution, String> FALLBACK_LABELS = Map.of(
            Institution.CONSEIL_ETAT, "Membre du Conseil d'État",
            Institution.COUR_CASSATION, "Magistrat de la Cour de cassation",
            Institution.COUR_COMPTES, "Magistrat de la Cour des comptes");

    private static final Map<Institution, String> POSITION_TOPICS = Map.of(
            Institution.CONSEIL_ETAT, "gov.judicial",
            Institution.COUR_CASSATION, "gov.judicial",
            Institution.COUR_COMPTES, "gov.national",
            Institution.AMBASSADE, "role.diplo");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters();
        Settings settings = Settings.of(p);
        JorfDownloader.Download download = new JorfDownloader().download(
                p.getOrDefault("indexUrl", DEFAULT_INDEX_URL).trim(), Path.of(ctx.requireParameter("cacheDir").trim()),
                settings.since(), integer(p, "delayMs", 300), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), settings, LocalDate.now(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(String.format("%d fichiers de la DILA (%d nouveaux). %s",
                download.files(), download.downloaded(), report.message()));
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), Settings.of(ctx.parameters()), LocalDate.now(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int articles, int events, int persons, int current, int unknown, int ended, int dropped,
            List<String> unread) {

        String message() {
            StringBuilder m = new StringBuilder(String.format("%d articles du JO, %d nominations, départs ou maintiens"
                    + " lus. %d personnes publiées : %d mandats en cours, %d sans fin connue, %d terminés récemment"
                    + " (%d mandats anciens écartés).", articles, events, persons, current, unknown, ended, dropped));
            if (!unread.isEmpty()) {
                m.append(String.format(" %d textes sans tournure reconnue : %s%s", unread.size(),
                        String.join(", ", unread.subList(0, Math.min(MAX_LISTED, unread.size()))),
                        unread.size() > MAX_LISTED ? "…" : "."));
            }
            return m.toString();
        }
    }

    /**
     * Lit {@code jorf-articles.zip} dans {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être
     * testé sans réseau, avec une date du jour fixe.
     *
     * @throws IllegalStateException si aucune personne n'est publiée (sélection ou rédaction des textes changée)
     */
    Report convert(Path rawDir, Settings settings, LocalDate today, FtmDatasetBuilder dataset) throws Exception {
        List<Article> articles = new ArrayList<>();
        for (String xml : JorfArticles.readZip(rawDir.resolve(JorfArticles.ARTICLES_FILE)).values()) {
            Article a = JorfArticles.parse(xml);
            if (!a.publication().isEmpty() && a.publication().compareTo(settings.since().toString()) >= 0) {
                articles.add(a);
            }
        }
        // Ordre de publication, puis ordre des articles dans le texte
        articles.sort(Comparator.comparing(Article::publication).thenComparing(Article::textId)
                .thenComparing(a -> number(a.number())));

        List<Event> events = new ArrayList<>();
        Set<String> unread = new LinkedHashSet<>();
        Set<String> readTexts = new LinkedHashSet<>();
        for (Article article : articles) {
            List<Event> read = NominationReader.read(article);
            events.addAll(read);
            (read.isEmpty() ? unread : readTexts).add(article.textId());
        }
        unread.removeAll(readTexts);

        List<Mandate> mandates = buildMandates(events);

        Map<String, FtmEntity> persons = new LinkedHashMap<>();
        int current = 0;
        int unknown = 0;
        int ended = 0;
        int dropped = 0;
        LocalDate oldestEnd = today.minusMonths(settings.monthsAfterEnd());
        for (Mandate m : mandates) {
            int maxYears = settings.maxYearsWithoutEnd(m.institution, m.function);
            boolean endedLongAgo = m.end != null && m.end.isBefore(oldestEnd);
            boolean staleOpen = m.end == null && m.start != null && m.start.isBefore(today.minusYears(maxYears));
            if (endedLongAgo || staleOpen) {
                dropped++;
                continue;
            }
            String status = status(m.end, today);
            switch (status) {
                case "current" -> current++;
                case "ended" -> ended++;
                default -> unknown++;
            }
            FtmEntity person = persons.computeIfAbsent(m.personKey, k -> newPerson(m, dataset));
            String label = label(m);
            person.addValue("position", status.equals("ended") ? "Ancien : " + label : label);
            m.sources.forEach(url -> person.addValue("sourceUrl", url));
            if (m.institution == Institution.AMBASSADE) {
                person.addValue("topics", "role.diplo");
            }
            addMandate(m, label, status, person, dataset);
        }
        persons.values().forEach(dataset::addEntity);
        if (persons.isEmpty()) {
            throw new IllegalStateException("Aucune personne trouvée dans les " + articles.size() + " articles du JO :"
                    + " la sélection ou la rédaction des décrets a probablement changé.");
        }
        Report report = new Report(articles.size(), events.size(), persons.size(), current, unknown, ended, dropped,
                new ArrayList<>(unread));
        log.info(report.message());
        return report;
    }

    /** Un mandat reconstitué : une personne dans une institution (dans un poste, pour un ambassadeur). */
    static final class Mandate {
        String personKey;
        String name;
        Institution institution;
        String post;
        String function;
        LocalDate start;
        LocalDate end;
        boolean open = true;
        final Set<String> sources = new LinkedHashSet<>();
    }

    /**
     * Applique les événements, dans l'ordre des dates d'effet ; à date égale, nomination, puis départ, puis maintien
     * (un maintien en fonction au-delà de la limite d'âge suit la retraite qu'il prolonge), puis ordre de publication.
     */
    static List<Mandate> buildMandates(List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::date, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Event::kind));
        List<Mandate> mandates = new ArrayList<>();
        for (Event e : sorted) {
            String person = personKey(e.name());
            String post = e.post() == null ? null : JorfArticles.fold(e.post());
            switch (e.kind()) {
                case NOMINATION -> {
                    if (e.institution() == Institution.AMBASSADE) {
                        // Le prédécesseur au même poste et le poste précédent de la personne prennent fin
                        for (Mandate m : mandates) {
                            if (m.open && m.institution == Institution.AMBASSADE
                                    && (post.equals(m.post) != m.personKey.equals(person))) {
                                close(m, e.date());
                            }
                        }
                    }
                    Mandate m = find(mandates, person, e.institution(), post, true);
                    if (m == null) {
                        m = newMandate(e, person, post);
                        m.start = e.date();
                        mandates.add(m);
                    }
                    m.function = e.function();
                    m.name = e.name();
                    m.sources.add(e.article().url());
                }
                case DEPARTURE -> {
                    boolean closed = false;
                    for (Mandate m : mandates) {
                        if (m.open && m.personKey.equals(person) && m.institution == e.institution()
                                && (post == null || post.equals(m.post))) {
                            close(m, e.date());
                            m.sources.add(e.article().url());
                            closed = true;
                        }
                    }
                    if (!closed) {
                        Mandate last = find(mandates, person, e.institution(), post, false);
                        if (last != null) {
                            // Décret modificatif ou republication : la dernière date fait foi
                            last.end = e.date();
                            last.sources.add(e.article().url());
                        } else {
                            // Nommé avant la période lue : seule la fin est connue
                            Mandate m = newMandate(e, person, post);
                            m.function = e.function();
                            close(m, e.date());
                            mandates.add(m);
                        }
                    }
                }
                case EXTENSION -> {
                    Mandate m = find(mandates, person, e.institution(), post, true);
                    if (m == null) {
                        // Maintenu en fonction après sa retraite : le mandat fermé reprend jusqu'à la nouvelle fin
                        m = find(mandates, person, e.institution(), post, false);
                        if (m != null) {
                            m.open = true;
                        }
                    }
                    if (m == null) {
                        m = newMandate(e, person, post);
                        m.function = e.function();
                        mandates.add(m);
                    }
                    m.end = e.until();
                    m.sources.add(e.article().url());
                }
            }
        }
        return mandates;
    }

    private static Mandate find(List<Mandate> mandates, String person, Institution institution, String post,
            boolean open) {
        Mandate found = null;
        for (Mandate m : mandates) {
            if (m.open == open && m.personKey.equals(person) && m.institution == institution
                    && (post == null || post.equals(m.post))) {
                found = m;
            }
        }
        return found;
    }

    private static Mandate newMandate(Event e, String person, String post) {
        Mandate m = new Mandate();
        m.personKey = person;
        m.name = e.name();
        m.institution = e.institution();
        m.post = post;
        m.sources.add(e.article().url());
        return m;
    }

    private static void close(Mandate m, LocalDate date) {
        m.end = date;
        m.open = false;
    }

    /** Libellé de la fonction : forme canonique (« Conseiller d'État »), ou poste d'ambassadeur. */
    static String label(Mandate m) {
        if (m.institution == Institution.AMBASSADE) {
            String f = m.function == null ? "" : JorfArticles.fold(m.function);
            String post = m.function == null ? m.post : NominationReader.post(m.function);
            String kind = f.contains("representant") ? "Représentant permanent de la France" : "Ambassadeur de France";
            return post == null || post.equals(m.function) ? capitalize(m.function == null ? kind : m.function)
                    : kind + " " + post;
        }
        String f = m.function == null ? "" : JorfArticles.fold(m.function);
        for (Role role : ROLES.get(m.institution)) {
            if (role.pattern().matcher(f).find()) {
                return role.label();
            }
        }
        return FALLBACK_LABELS.get(m.institution);
    }

    private static FtmEntity newPerson(Mandate m, FtmDatasetBuilder dataset) {
        String[] name = splitName(m.name);
        // ID = prénom + nom : les décrets ne donnent ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", m.personKey);
        person.addValue("name", m.name);
        person.addValue("firstName", name[0]);
        person.addValue("lastName", name[1]);
        person.addValue("country", "fr");
        return person;
    }

    private static void addMandate(Mandate m, String label, String status, FtmEntity person,
            FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", JorfArticles.fold(label));
        position.addValue("name", label);
        position.addValue("country", "fr");
        position.addValue("organization", m.institution == Institution.AMBASSADE ? "Ministère de l'Europe et des"
                + " affaires étrangères" : m.institution.label);
        position.addValue("topics", POSITION_TOPICS.get(m.institution));
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.personKey, m.institution.key,
                m.post == null ? "" : m.post, m.start == null ? "" : m.start.toString());
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", m.start == null ? null : m.start.toString());
        occupancy.addValue("endDate", m.end == null ? null : m.end.toString());
        occupancy.addValue("status", status);
        m.sources.forEach(url -> occupancy.addValue("sourceUrl", url));
        dataset.addEntity(occupancy);
    }

    /** {@code ended} si la fin est passée, {@code current} si elle est à venir, {@code unknown} sans fin connue. */
    static String status(LocalDate end, LocalDate today) {
        if (end == null) {
            return "unknown";
        }
        return end.isBefore(today) ? "ended" : "current";
    }

    /** Clé de personne : nom sans accents ni majuscules, pour que « Pierre Truche » et « Pierre TRUCHE » se rejoignent. */
    static String personKey(String name) {
        return JorfArticles.fold(name).replaceAll("\\s+", " ").trim();
    }

    /**
     * Sépare « Jean-Baptiste JEANGENE VILMER » en prénom et nom : les mots tout en majuscules forment le nom. Sans
     * mot en majuscules (« Pierre Truche », textes anciens), le dernier mot est le nom. Repris de
     * {@code lu_pep_legilux}.
     */
    static String[] splitName(String fullName) {
        String[] words = fullName.trim().split("\\s+");
        boolean anyUpper = false;
        for (String word : words) {
            if (isUpper(word)) {
                anyUpper = true;
            }
        }
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            boolean isLast = anyUpper ? isUpper(words[i]) || (last.length() > 0 && words[i].length() <= 3)
                    : i == words.length - 1;
            StringBuilder target = isLast ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(words[i]);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }

    private static boolean isUpper(String word) {
        return word.length() > 1 && word.equals(word.toUpperCase(Locale.FRENCH)) && !word.equals(word.toLowerCase(Locale.FRENCH));
    }

    private static Role role(String regex, String label) {
        return new Role(Pattern.compile(regex), label);
    }

    private static String capitalize(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static int number(String articleNumber) {
        try {
            return Integer.parseInt(articleNumber.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int integer(Map<String, String> parameters, String name, int defaultValue) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre " + name + " : nombre entier attendu, reçu « " + value + " »");
        }
    }
}
