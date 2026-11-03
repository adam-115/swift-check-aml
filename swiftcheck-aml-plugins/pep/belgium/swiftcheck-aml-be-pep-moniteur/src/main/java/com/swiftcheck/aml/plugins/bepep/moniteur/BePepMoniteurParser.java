package com.swiftcheck.aml.plugins.bepep.moniteur;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Appointment;
import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>be_pep_moniteur</b> – nominations, renouvellements et démissions de hautes fonctions publiques belges
 * publiées au Moniteur belge (Journal officiel, {@code ejustice.just.fgov.be}) :
 * <ul>
 *   <li><b>BE-d</b> : magistrats de la Cour de cassation, conseillers aux cours d'appel et cours du travail
 *       (y compris suppléants), membres du Conseil d'État ;</li>
 *   <li><b>BE-f</b> : ambassadeurs, consuls généraux et officiers généraux (général, lieutenant-général, général-major) ;</li>
 *   <li><b>BE-g</b> : commissaires du gouvernement et administrateurs d'organismes publics fédéraux ou régionaux ;</li>
 *   <li><b>BE-a</b> : membres et anciens membres des gouvernements fédéral et régionaux nommés ou démissionnés par arrêté royal.</li>
 * </ul>
 *
 * <p>Type {@code PLUGIN_MANAGED} : le downloader télécharge les sommaires et les articles pertinents dans {@code rawDir} ;
 * le parseur lit ces fichiers locaux sans accès réseau lors de la conversion ou des tests.
 */
@Extension
public class BePepMoniteurParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(BePepMoniteurParser.class);

    static final String ID_PREFIX = "be-pep-moniteur";
    static final String TOPIC = "role.pep";
    static final String DEFAULT_SINCE_DAYS = "30";
    static final String DEFAULT_DELAY_MS = "1000";

    record PepFunction(String key, Pattern pattern, String topic) {
    }

    /**
     * Hautes fonctions belges retenues (critères PEP de l'art. 4 de la loi belge du 18 septembre 2017).
     */
    static final List<PepFunction> PEP_FUNCTIONS = List.of(
            // BE-d : Cour de cassation
            new PepFunction("cour-cassation", pattern("(?:premier\\s+président|président|conseiller|procureur\\s+général|premier\\s+avocat\\s+général|avocat\\s+général|référendaire).*cour\\s+de\\s+cassation"), "gov.judicial"),
            // BE-d : Cours d'appel et Cours du travail (y compris suppléants)
            new PepFunction("cour-appel", pattern("(?:premier\\s+président|président|conseiller(?:\\s+social)?|magistrat\\s+suppléant|procureur\\s+général|avocat\\s+général|substitut\\s+général).*cour\\s+d['’]appel"), "gov.judicial"),
            new PepFunction("cour-travail", pattern("(?:premier\\s+président|président|conseiller(?:\\s+social)?|magistrat\\s+suppléant|procureur\\s+général|avocat\\s+général).*cour\\s+du\\s+travail"), "gov.judicial"),
            // BE-d : Conseil d'État
            new PepFunction("conseil-etat", pattern("(?:premier\\s+président|président|conseiller|auditeur\\s+général|auditeur|auditeur\\s+adjoint|assesseur).*conseil\\s+d['’][ée]tat"), "gov.judicial"),
            // BE-f : Officiers généraux
            new PepFunction("officier-general", pattern("\\b(?:général|lieutenant-général|général-major|amiral|vice-amiral|amiral\\s+de\\s+division)\\b"), "gov.national"),
            // BE-f : Ambassadeurs et consuls généraux
            new PepFunction("diplomate", pattern("\\b(?:ambassadeur|consul\\s+général|chargé\\s+d['’]affaires)\\b"), "gov.national"),
            // BE-g : Commissaires du gouvernement & administrateurs d'organismes d'intérêt public
            new PepFunction("commissaire-gvt", pattern("\\b(?:commissaire\\s+général|commissaire\\s+du\\s+gouvernement)\\b"), "gov.financial"),
            new PepFunction("administrateur-public", pattern("\\b(?:administrateur|membre\\s+du\\s+conseil\\s+d['’]administration)\\s+de\\s+la\\s+(?:société|commission|université|agence|autorité|banque)\\b"), "gov.financial"),
            // BE-a : Membres de gouvernements
            new PepFunction("gouvernement", pattern("\\b(?:premier\\s+ministre|vice-premier\\s+ministre|ministre-président|ministre|secrétaire\\s+d['’][ée]tat)\\b"), "gov.national")
    );

    /** Fonctions judiciaires de premier niveau qui NE sont PAS PEP au sens strict. */
    private static final Pattern NOT_PEP = pattern("tribunal\\s+de\\s+première\\s+instance|tribunal\\s+de\\s+police"
            + "|juge\\s+de\\s+paix|tribunal\\s+de\\s+l['’]entreprise|tribunal\\s+du\\s+travail|juridictions?\\s+consulaires?"
            + "|greffier|assistant\\s+au\\s+greffe|stagiaire|expert");

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Path rawDir = ctx.rawDir();
        Map<String, String> p = ctx.parameters() != null ? ctx.parameters() : Map.of();

        // Si aucun fichier html présent, télécharger
        boolean hasHtml = false;
        if (Files.exists(rawDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rawDir, "*.html")) {
                hasHtml = stream.iterator().hasNext();
            }
        }
        if (!hasHtml) {
            long delayMs = Long.parseLong(p.getOrDefault("delayMs", DEFAULT_DELAY_MS).trim());
            int days = Integer.parseInt(p.getOrDefault("days", DEFAULT_SINCE_DAYS).trim());
            LocalDate until = LocalDate.now();
            LocalDate since = until.minusDays(days);
            String baseUrl = p.get("baseUrl");
            new MoniteurDownloader(Duration.ofMillis(delayMs)).download(baseUrl, since, until, rawDir);
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(rawDir, oldestEndDate(p, LocalDate.now()), LocalDate.now(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), oldestEndDate(ctx.parameters() != null ? ctx.parameters() : Map.of(), LocalDate.now()),
                LocalDate.now(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Person".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Person", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    record Report(int articlesRead, int eventsFound, int pepEvents, List<String> samples) {
        String message() {
            return String.format("%d actes du Moniteur analysés, %d événements extraits dont %d hautes fonctions PEP.",
                    articlesRead, eventsFound, pepEvents);
        }
    }

    Report convert(Path rawDir, LocalDate oldestEndDate, LocalDate today, FtmDatasetBuilder dataset) throws Exception {
        List<Path> articleFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rawDir, "article-*.html")) {
            for (Path entry : stream) {
                articleFiles.add(entry);
            }
        }

        List<Event> events = new ArrayList<>();
        int totalEvents = 0;
        List<String> samples = new ArrayList<>();

        for (Path file : articleFiles) {
            String fileName = file.getFileName().toString();
            String numac = fileName.replace("article-", "").replace(".html", "");
            String sourceUrl = "https://www.ejustice.just.fgov.be/cgi/article.pl?numac_search=" + numac;

            String text = MoniteurAppointmentReader.extractHtmlText(file);
            if (text.isBlank()) {
                continue;
            }
            LocalDate decreeDate = MoniteurAppointmentReader.decreeDate(text);
            if (decreeDate == null) {
                decreeDate = today;
            }

            List<Appointment> list = MoniteurAppointmentReader.read(text);
            totalEvents += list.size();

            for (Appointment app : list) {
                PepFunction fn = matchPepFunction(app.function());
                if (fn != null) {
                    events.add(new Event(sourceUrl, decreeDate, app, fn));
                    if (samples.size() < 10) {
                        samples.add(app.fullName() + " (" + app.function() + ")");
                    }
                }
            }
        }

        List<Mandate> mandates = buildMandates(events);
        Map<String, FtmEntity> persons = new LinkedHashMap<>();

        for (Mandate m : mandates) {
            if (m.end != null && m.end.isBefore(oldestEndDate)) {
                continue;
            }
            FtmEntity p = persons.computeIfAbsent(m.personKey, k -> newPerson(m, dataset));
            p.addValue("sourceUrl", m.actUrl);
            addMandate(m, p, today, dataset);
        }

        persons.values().forEach(dataset::addEntity);

        if (persons.isEmpty() && !articleFiles.isEmpty()) {
            throw new IllegalStateException("Aucune personne PEP extraite des " + articleFiles.size()
                    + " actes du Moniteur belge.");
        }

        return new Report(articleFiles.size(), totalEvents, events.size(), samples);
    }

    private record Event(String actUrl, LocalDate actDate, Appointment app, PepFunction pepFn) {
        LocalDate date() {
            return app.effective() != null ? app.effective() : actDate;
        }

        String personKey() {
            return cleanPersonKey(app.fullName());
        }
    }

    private static final class Mandate {
        String personKey;
        String fullName;
        String civility;
        String function;
        PepFunction pepFunction;
        String actUrl;
        LocalDate start;
        LocalDate end;
        boolean open = true;
    }

    private static List<Mandate> buildMandates(List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort((a, b) -> a.date().compareTo(b.date()));
        List<Mandate> mandates = new ArrayList<>();

        for (Event ev : sorted) {
            Appointment a = ev.app();
            List<Mandate> open = mandates.stream()
                    .filter(m -> m.open && m.personKey.equals(ev.personKey())
                            && m.pepFunction.key().equals(ev.pepFn().key()))
                    .toList();

            if (a.kind() == Kind.RESIGNATION) {
                for (Mandate m : open) {
                    m.end = ev.date();
                    m.open = false;
                }
                if (open.isEmpty()) {
                    Mandate m = new Mandate();
                    m.personKey = ev.personKey();
                    m.fullName = a.fullName();
                    m.civility = a.civility();
                    m.function = a.function();
                    m.pepFunction = ev.pepFn();
                    m.actUrl = ev.actUrl();
                    m.end = ev.date();
                    m.open = false;
                    mandates.add(m);
                }
            } else if (!open.isEmpty() && (a.kind() == Kind.RENEWAL || open.stream().anyMatch(m -> sameFunction(m, a)))) {
                open.forEach(m -> {
                    if (a.until() != null) {
                        m.end = a.until();
                    }
                });
            } else {
                Mandate m = new Mandate();
                m.personKey = ev.personKey();
                m.fullName = a.fullName();
                m.civility = a.civility();
                m.function = a.function();
                m.pepFunction = ev.pepFn();
                m.actUrl = ev.actUrl();
                m.start = a.kind() == Kind.NOMINATION ? ev.date() : null;
                m.end = a.until();
                mandates.add(m);
            }
        }
        return mandates;
    }

    private static boolean sameFunction(Mandate m, Appointment a) {
        return functionKey(m.function).equals(functionKey(a.function()));
    }

    private static FtmEntity newPerson(Mandate m, FtmDatasetBuilder dataset) {
        FtmEntity person = dataset.newEntity("Person", m.personKey);
        person.addValue("name", m.fullName);
        String[] parts = splitName(m.fullName);
        if (parts[0] != null) {
            person.addValue("firstName", parts[0]);
        }
        person.addValue("lastName", parts[1]);
        if (m.civility != null) {
            String c = m.civility.toLowerCase(Locale.FRENCH);
            if (c.startsWith("mme") || c.contains("madame")) {
                person.addValue("gender", "female");
            } else if (c.startsWith("m.") || c.contains("monsieur")) {
                person.addValue("gender", "male");
            }
        }
        person.addValue("country", "be");
        return person;
    }

    private static void addMandate(Mandate m, FtmEntity person, LocalDate today, FtmDatasetBuilder dataset) {
        String posKey = functionKey(m.function);
        FtmEntity position = dataset.newEntity("Position", posKey);
        position.addValue("name", capitalize(m.function));
        position.addValue("country", "be");
        position.addValue("topics", m.pepFunction.topic());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.personKey, posKey, m.actUrl.replaceAll("[^a-zA-Z0-9_-]", "_"));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        if (m.start != null) {
            occupancy.addValue("startDate", m.start.toString());
        }
        if (m.end != null) {
            occupancy.addValue("endDate", m.end.toString());
        }
        occupancy.addValue("status", m.end == null ? "unknown" : (m.end.isBefore(today) ? "ended" : "current"));
        occupancy.addValue("sourceUrl", m.actUrl);
        dataset.addEntity(occupancy);
    }

    static PepFunction matchPepFunction(String function) {
        if (function == null) {
            return null;
        }
        String norm = function.toLowerCase(Locale.FRENCH).trim();
        if (NOT_PEP.matcher(norm).find()) {
            return null;
        }
        for (PepFunction pf : PEP_FUNCTIONS) {
            if (pf.pattern().matcher(norm).find()) {
                return pf;
            }
        }
        return null;
    }

    static String cleanPersonKey(String fullName) {
        return Normalizer.normalize(fullName.toLowerCase(Locale.FRENCH).trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]", "");
    }

    static String functionKey(String function) {
        return Normalizer.normalize(function.toLowerCase(Locale.FRENCH).trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    static String[] splitName(String fullName) {
        String clean = fullName.replaceAll("\\s+", " ").trim();
        String[] words = clean.split(" ");
        if (words.length == 1) {
            return new String[] { null, words[0] };
        }
        // Format belge courant dans le Moniteur : « Vandermeersch D. » ou « Van der Mersch X. »
        // ou « SOETE Dennis »
        if (words[0].equals(words[0].toUpperCase(Locale.FRENCH)) && words[0].length() > 1
                && words[words.length - 1].matches("^[A-Z][a-z]+$")) {
            // NOM Prénom : ex "SOETE Dennis"
            return new String[] { words[words.length - 1], words[0] };
        }
        // Par défaut : dernier mot = prénom ou initiale si se termine par un point
        if (words[words.length - 1].matches("^[A-Z]\\.?$")) {
            StringBuilder last = new StringBuilder();
            for (int i = 0; i < words.length - 1; i++) {
                if (i > 0) last.append(" ");
                last.append(words[i]);
            }
            return new String[] { words[words.length - 1].replace(".", ""), last.toString() };
        }
        // Prénom Nom usuel
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < words.length - 1; i++) {
            if (i > 0) first.append(" ");
            first.append(words[i]);
        }
        return new String[] { first.toString(), words[words.length - 1] };
    }

    private static String capitalize(String text) {
        String t = text.trim();
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static LocalDate oldestEndDate(Map<String, String> parameters, LocalDate today) {
        String years = parameters.getOrDefault("yearsAfterEnd", "5");
        try {
            return today.minusYears(Integer.parseInt(years.trim()));
        } catch (NumberFormatException e) {
            return today.minusYears(5);
        }
    }
}

