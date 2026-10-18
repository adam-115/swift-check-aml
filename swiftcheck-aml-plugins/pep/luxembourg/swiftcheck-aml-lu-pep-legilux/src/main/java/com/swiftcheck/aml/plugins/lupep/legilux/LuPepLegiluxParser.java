package com.swiftcheck.aml.plugins.lupep.legilux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.lupep.legilux.AppointmentReader.Appointment;
import com.swiftcheck.aml.plugins.lupep.legilux.AppointmentReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_legilux</b> – hautes fonctions luxembourgeoises nommées par arrêté grand-ducal et publiées au Journal
 * officiel (Legilux) : Conseil d'État, hautes juridictions, Cour des comptes, direction de la Banque centrale,
 * direction de la CSSF et du Commissariat aux assurances.
 *
 * <p>Source : point SPARQL de Legilux (paramètre {@code endpoint}) et texte XML de chaque publication.
 * Type {@code PLUGIN_MANAGED} : ce plugin interroge lui-même Legilux et dépose les réponses brutes dans
 * {@code ctx.rawDir()} ({@link LegiluxDownloader}) ; le core les signe et les archive. La conversion ne lit ensuite
 * que ces fichiers : le test du parser les relit sans réseau.
 *
 * <p>Seules les fonctions de {@link #PEP_FUNCTIONS} sont gardées : environ 5 % des actes « Magistrature »
 * concernent une haute juridiction, le reste concerne des juges de première instance, qui ne sont pas PEP.
 *
 * <p>Pour chaque personne et chaque fonction, un mandat ({@code Occupancy}) :
 * <ul>
 *   <li>début = date d'effet de la nomination ;</li>
 *   <li>fin = date d'effet de la démission, ou fin prévue par l'arrêté (« jusqu'au », « pour une période de
 *       N ans ») ;</li>
 *   <li>statut {@code ended} si la fin est passée, {@code current} si elle est à venir, {@code unknown} sans fin
 *       connue : un départ à la retraite ou une promotion n'a pas toujours d'acte de démission.</li>
 * </ul>
 * Un acte qui concerne une haute fonction mais dont la tournure n'est pas reconnue n'arrête pas l'exécution :
 * il est cité dans le message de l'exécution (« actes atypiques »). L'exécution échoue seulement si aucune
 * personne n'est trouvée.
 *
 * <p>Paramètres du parser (JSON) :
 * {@code {"endpoint": "https://data.legilux.public.lu/sparqlendpoint", "since": "2017-01-01", "yearsAfterEnd": "5"}}.
 */
@Extension
public class LuPepLegiluxParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "lu-pep-legilux";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    /** Avant 2017, les publications de Legilux n'ont pas de texte XML exploitable. */
    static final String DEFAULT_SINCE = "2017-01-01";

    /** Nombre maximal d'actes atypiques cités dans le message de l'exécution. */
    private static final int MAX_LISTED = 20;

    /**
     * Une haute fonction reconnue dans le texte : clé (pour relier nomination et démission), motif sur la fonction
     * telle qu'écrite, topic FtM de la fonction.
     */
    record PepFunction(String key, Pattern pattern, String topic) {
    }

    /** Hautes fonctions gardées (liste officielle du Luxembourg, JO C/2023/724, catégories b, d, e et i). */
    static final List<PepFunction> PEP_FUNCTIONS = List.of(
            new PepFunction("conseil-etat", pattern("conseil(?:ler)? d'[ée]tat"), "gov.national"),
            new PepFunction("cour-cassation", pattern("cour de cassation"), "gov.judicial"),
            // « supérieur » sans e : faute présente dans certains actes
            new PepFunction("cour-superieure", pattern("^(?:vice-)?présidente? de la cour supérieure? de justice"),
                    "gov.judicial"),
            new PepFunction("parquet-general", pattern("procureur général d'[ée]tat"), "gov.judicial"),
            new PepFunction("cour-administrative", pattern("cour administrative"), "gov.judicial"),
            new PepFunction("cour-constitutionnelle", pattern("cour constitutionnelle"), "gov.judicial"),
            new PepFunction("cour-des-comptes", pattern("cour des comptes"), "gov.national"),
            new PepFunction("bcl-direction", pattern("^(?:directeur|directrice).*banque centrale"), "gov.financial"),
            new PepFunction("regulateur-direction", pattern("^(?:directeur|directrice|directeur général"
                    + "|membre du comité de direction).*(?:commission de surveillance du secteur financier"
                    + "|commissariat aux assurances|institut luxembourgeois de régulation)"), "gov.financial"));

    /** Institutions des hautes fonctions : un acte qui les cite sans événement lu est signalé comme atypique. */
    private static final Pattern PEP_INSTITUTIONS = pattern("conseil(?:ler)? d'[ée]tat|cour de cassation"
            + "|cour supérieure? de justice|procureur général d'[ée]tat|cour administrative|cour constitutionnelle"
            + "|cour des comptes|banque centrale|commission de surveillance du secteur financier"
            + "|commissariat aux assurances");

    /** Fonctions d'appui, jamais PEP même dans une haute juridiction. */
    private static final Pattern NOT_PEP = pattern("greffier|référendaire|attaché|secrétaire|stagiaire"
            + "|pool de complément|remplaçant|suppléant");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        String endpoint = ctx.requireParameter("endpoint");
        LocalDate since = LocalDate.parse(ctx.parameters().getOrDefault("since", DEFAULT_SINCE).trim());
        int missingTexts = new LegiluxDownloader().download(endpoint, since, ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), oldestEndDate(ctx.parameters(), LocalDate.now()), LocalDate.now(),
                dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        ParserResult result = dataset.toConversionResult();
        return new ParserResult(result.entityCount(), result.rejectedCount(), result.conflictCount(),
                result.rejectedByProperty(), report.message(missingTexts));
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), oldestEndDate(ctx.parameters(), LocalDate.now()), LocalDate.now(), dataset);
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
    record Report(int actCount, int eventCount, List<String> atypicalActs, List<String> actsWithoutText) {

        String message(int missingTexts) {
            StringBuilder m = new StringBuilder(String.format("%d actes lus, %d nominations ou démissions de hautes"
                    + " fonctions.", actCount, eventCount));
            if (missingTexts + actsWithoutText.size() > 0) {
                m.append(String.format(" %d actes sans texte XML.", Math.max(missingTexts, actsWithoutText.size())));
            }
            if (!atypicalActs.isEmpty()) {
                m.append(String.format(" %d actes atypiques (haute fonction, tournure non reconnue) : %s%s",
                        atypicalActs.size(), String.join(", ", atypicalActs.subList(0,
                                Math.min(MAX_LISTED, atypicalActs.size()))),
                        atypicalActs.size() > MAX_LISTED ? "…" : "."));
            }
            return m.toString();
        }
    }

    /**
     * Lit les fichiers bruts de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé sans
     * réseau, avec une date du jour fixe.
     *
     * @throws IllegalStateException si aucune personne n'est trouvée (requête ou format de Legilux changé)
     */
    Report convert(Path rawDir, LocalDate oldestEndDate, LocalDate today, FtmDatasetBuilder dataset) throws Exception {
        List<LegiluxActs.Act> acts = LegiluxActs.readActList(rawDir.resolve(LegiluxActs.ACT_LIST_FILE));
        Path textsFile = rawDir.resolve(LegiluxActs.TEXTS_FILE);
        Map<String, String> texts = Files.exists(textsFile) ? LegiluxActs.readTexts(textsFile) : Map.of();

        // 1. Les événements des hautes fonctions, dans l'ordre des actes (date de l'arrêté)
        List<Event> events = new ArrayList<>();
        List<String> atypical = new ArrayList<>();
        List<String> withoutText = new ArrayList<>();
        for (LegiluxActs.Act act : acts) {
            String text = texts.get(LegiluxActs.textEntryName(act.uri()));
            if (text == null || text.isBlank()) {
                withoutText.add(LegiluxActs.shortRef(act.uri()));
                continue;
            }
            LocalDate decreeDate = AppointmentReader.decreeDate(text);
            LocalDate actDate = decreeDate != null ? decreeDate : LocalDate.parse(act.date());
            List<Appointment> appointments = AppointmentReader.read(text);
            for (Appointment appointment : appointments) {
                PepFunction function = pepFunction(appointment.function());
                if (function != null) {
                    events.add(new Event(act.uri(), actDate, appointment, function));
                }
            }
            // Rien de lu dans un acte qui cite une haute institution : tournure à ajouter un jour.
            // Un titre honorifique (« le titre honorifique de ses fonctions est conféré ») n'est pas un mandat.
            String normalized = AppointmentReader.normalize(text);
            if (appointments.isEmpty() && PEP_INSTITUTIONS.matcher(normalized).find()
                    && !normalized.contains("titre honorifique")) {
                atypical.add(LegiluxActs.shortRef(act.uri()));
            }
        }

        // 2. Les mandats : une nomination ouvre un mandat, une démission ferme les mandats ouverts de même clé
        List<Mandate> mandates = buildMandates(events);

        // 3. Les entités FtM, sans les mandats terminés depuis trop longtemps
        Map<String, FtmEntity> personsById = new LinkedHashMap<>();
        for (Mandate mandate : mandates) {
            if (mandate.end != null && mandate.end.isBefore(oldestEndDate)) {
                continue;
            }
            FtmEntity person = personsById.computeIfAbsent(mandate.personKey, key -> newPerson(mandate, dataset));
            person.addValue("sourceUrl", mandate.actUri);
            addMandate(mandate, person, today, dataset);
        }
        personsById.values().forEach(dataset::addEntity);
        if (personsById.isEmpty()) {
            throw new IllegalStateException("Aucune personne trouvée dans les " + acts.size() + " actes de Legilux :"
                    + " la requête SPARQL ou la rédaction des actes a probablement changé.");
        }
        return new Report(acts.size(), events.size(), atypical, withoutText);
    }

    /** Une nomination, un renouvellement ou une démission d'une haute fonction, avec son acte. */
    private record Event(String actUri, LocalDate actDate, Appointment appointment, PepFunction function) {

        /** Date d'effet, ou date de l'arrêté si l'acte n'en donne pas. */
        LocalDate date() {
            return appointment.effective() != null ? appointment.effective() : actDate;
        }

        String personKey() {
            return LuPepLegiluxParser.personKey(appointment.fullName());
        }
    }

    /** Un mandat reconstitué à partir des actes. */
    private static final class Mandate {
        String personKey;
        String fullName;
        String function;
        PepFunction pepFunction;
        String actUri;
        LocalDate start;
        LocalDate end;
        boolean open = true;
    }

    private static List<Mandate> buildMandates(List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort((a, b) -> a.date().compareTo(b.date()));
        List<Mandate> mandates = new ArrayList<>();
        for (Event event : sorted) {
            Appointment a = event.appointment();
            List<Mandate> open = mandates.stream()
                    .filter(m -> m.open && m.personKey.equals(event.personKey())
                            && m.pepFunction.key().equals(event.function().key()))
                    .toList();
            if (a.kind() == Kind.RESIGNATION) {
                for (Mandate m : open) {
                    m.end = event.date();
                    m.open = false;
                }
                if (open.isEmpty()) {
                    Mandate closed = lastClosed(mandates, event);
                    if (closed != null) {
                        // Deuxième démission pour le même mandat (la première a été « rapportée ») : elle fait foi
                        closed.end = event.date();
                    } else {
                        // Nommé avant 2017 : seule la fin du mandat est connue
                        Mandate m = newMandate(event);
                        m.end = event.date();
                        m.open = false;
                        mandates.add(m);
                    }
                }
            } else if (open.stream().anyMatch(m -> sameFunction(m, a))) {
                // Renouvellement, ou nouvelle nomination à la même fonction : le mandat ouvert continue,
                // seule la fin prévue peut changer
                open.stream().filter(m -> sameFunction(m, a) && a.until() != null).forEach(m -> m.end = a.until());
            } else {
                Mandate m = newMandate(event);
                // Un renouvellement sans nomination connue : mandat en cours, début inconnu
                m.start = a.kind() == Kind.NOMINATION ? event.date() : null;
                m.end = a.until();
                mandates.add(m);
            }
        }
        return mandates;
    }

    /** Dernier mandat déjà terminé de la même personne dans la même institution, ou {@code null}. */
    private static Mandate lastClosed(List<Mandate> mandates, Event event) {
        Mandate last = null;
        for (Mandate m : mandates) {
            if (!m.open && m.personKey.equals(event.personKey()) && m.pepFunction.key().equals(event.function().key())) {
                last = m;
            }
        }
        return last;
    }

    private static Mandate newMandate(Event event) {
        Mandate m = new Mandate();
        m.personKey = event.personKey();
        m.fullName = event.appointment().fullName();
        m.function = event.appointment().function();
        m.pepFunction = event.function();
        m.actUri = event.actUri();
        return m;
    }

    private static boolean sameFunction(Mandate m, Appointment a) {
        return functionKey(m.function).equals(functionKey(a.function()));
    }

    private static FtmEntity newPerson(Mandate mandate, FtmDatasetBuilder dataset) {
        String[] name = splitName(mandate.fullName);
        // ID = prénom + nom : les actes ne donnent ni identifiant ni date de naissance
        FtmEntity person = dataset.newEntity("Person", mandate.personKey);
        person.addValue("name", mandate.fullName);
        person.addValue("firstName", name[0]);
        person.addValue("lastName", name[1]);
        person.addValue("country", "lu");
        return person;
    }

    /** La fonction ({@code Position}, partagée par ses titulaires) et le mandat ({@code Occupancy}). */
    private static void addMandate(Mandate mandate, FtmEntity person, LocalDate today, FtmDatasetBuilder dataset) {
        FtmEntity position = dataset.newEntity("Position", functionKey(mandate.function));
        position.addValue("name", capitalize(mandate.function));
        position.addValue("country", "lu");
        position.addValue("topics", mandate.pepFunction.topic());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", mandate.personKey, functionKey(mandate.function),
                LegiluxActs.shortRef(mandate.actUri));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", mandate.start == null ? null : mandate.start.toString());
        occupancy.addValue("endDate", mandate.end == null ? null : mandate.end.toString());
        occupancy.addValue("status", status(mandate.end, today));
        occupancy.addValue("sourceUrl", mandate.actUri);
        dataset.addEntity(occupancy);
    }

    /** {@code ended} si la fin est passée, {@code current} si elle est à venir, {@code unknown} sans fin connue. */
    static String status(LocalDate end, LocalDate today) {
        if (end == null) {
            return "unknown";
        }
        return end.isBefore(today) ? "ended" : "current";
    }

    /** La haute fonction reconnue dans la fonction écrite, ou {@code null} si elle n'est pas PEP. */
    static PepFunction pepFunction(String function) {
        String text = AppointmentReader.normalize(function).toLowerCase(Locale.FRENCH).trim();
        if (NOT_PEP.matcher(text).find()) {
            return null;
        }
        for (PepFunction pep : PEP_FUNCTIONS) {
            if (pep.pattern().matcher(text).find()) {
                return pep;
            }
        }
        return null;
    }

    /** Clé de personne : prénom et nom en minuscules, pour que « WEYMERSKIRCH » et « Weymerskirch » se rejoignent. */
    static String personKey(String fullName) {
        return AppointmentReader.normalize(fullName).toLowerCase(Locale.FRENCH).trim();
    }

    /**
     * Clé de fonction, sans majuscules ni accents : « Vice-Président du Conseil d'Etat » et « vice-président du
     * Conseil d'État » sont la même fonction.
     */
    private static String functionKey(String function) {
        return Normalizer.normalize(AppointmentReader.normalize(function).toLowerCase(Locale.FRENCH).trim(),
                Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Sépare « Stephen DE RON » en prénom « Stephen » et nom « DE RON » : les mots tout en majuscules forment le nom.
     * Sans mot en majuscules (« Patrick Weymerskirch »), le dernier mot est le nom.
     *
     * @return {prénom, nom} ; le prénom vaut {@code null} s'il n'y en a pas
     */
    static String[] splitName(String fullName) {
        String[] words = fullName.trim().split("\\s+");
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        boolean anyUpper = false;
        for (String word : words) {
            if (word.length() > 1 && word.equals(word.toUpperCase(Locale.FRENCH))) {
                anyUpper = true;
            }
        }
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            boolean isLast = anyUpper ? word.length() > 1 && word.equals(word.toUpperCase(Locale.FRENCH))
                    : i == words.length - 1;
            StringBuilder target = isLast ? last : first;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }

    private static String capitalize(String text) {
        String t = AppointmentReader.normalize(text).trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Date limite d'après le paramètre {@code yearsAfterEnd} (5 ans par défaut). */
    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate today) {
        String years = parameters.getOrDefault("yearsAfterEnd", "5");
        try {
            return today.minusYears(Integer.parseInt(years.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre yearsAfterEnd : nombre entier attendu, reçu « " + years + " »");
        }
    }
}
