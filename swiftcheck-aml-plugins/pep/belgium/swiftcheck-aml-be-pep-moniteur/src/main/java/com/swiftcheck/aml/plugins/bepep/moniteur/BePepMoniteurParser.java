package com.swiftcheck.aml.plugins.bepep.moniteur;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.swiftcheck.aml.plugins.bepep.moniteur.JudicialSourcesReader.SourcedAppointment;
import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Appointment;
import com.swiftcheck.aml.plugins.bepep.moniteur.MoniteurAppointmentReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>be_pep_moniteur</b> – nominations, renouvellements et démissions de hautes fonctions publiques belges
 * publiées au Moniteur belge (Journal officiel, {@code ejustice.just.fgov.be}) et sur les sites officiels de la
 * Cour de cassation ({@code cass.be}) et du Collège des cours et tribunaux ({@code tribunaux-rechtbanken.be}) :
 * <ul>
 *   <li><b>BE-d</b> : magistrats du siège et du parquet de la Cour de cassation, premiers présidents, présidents
 *       et conseillers aux cours d'appel et cours du travail (y compris suppléants), membres du Conseil d'État ;</li>
 *   <li><b>BE-f</b> : ambassadeurs, consuls généraux et officiers généraux (général, lieutenant-général, général-major) ;</li>
 *   <li><b>BE-g</b> : commissaires du gouvernement et administrateurs d'organismes publics fédéraux ou régionaux ;</li>
 *   <li><b>BE-a</b> : membres et anciens membres des gouvernements fédéral et régionaux nommés ou démissionnés par arrêté royal.</li>
 * </ul>
 *
 * <p>Type {@code PLUGIN_MANAGED} : le downloader télécharge les sommaires, actes pertinents et compositions judiciaires
 * dans {@code rawDir} ; le parseur lit ces fichiers locaux sans accès réseau lors de la conversion ou des tests.
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
            new PepFunction("cour-cassation", pattern("(?:premier\\s+président|président(?:\\s+de\\s+section)?|conseiller|procureur\\s+général|premier\\s+avocat\\s+général|avocat\\s+général(?:\\s+délégué)?|référendaire).*cour\\s+de\\s+cassation"), "gov.judicial"),
            // BE-d : Cours d'appel et Cours du travail (y compris suppléants)
            new PepFunction("cour-appel", pattern("(?:premier\\s+président|président|conseiller(?:\\s+social)?|magistrat\\s+suppléant|procureur\\s+général|avocat\\s+général|substitut(?:\\s+du\\s+procureur)?\\s+général).*cour\\s+d['’]appel"), "gov.judicial"),
            new PepFunction("cour-travail", pattern("(?:premier\\s+président|président|conseiller(?:\\s+social)?|magistrat\\s+suppléant|procureur\\s+général|avocat\\s+général).*cour\\s+du\\s+travail"), "gov.judicial"),
            // BE-d : Conseil d'État
            new PepFunction("conseil-etat", pattern("(?:premier\\s+président|président|conseiller|auditeur\\s+général|auditeur|auditeur\\s+adjoint|assesseur).*conseil\\s+d['’][ée]tat"), "gov.judicial"),
            // BE-f : Officiers généraux
            new PepFunction("officier-general", pattern("\\b(?:général|lieutenant-général|général-major|amiral|vice-amiral|amiral\\s+de\\s+division)\\b"), "gov.national"),
            // BE-f : Ambassadeurs et consuls généraux
            new PepFunction("diplomate", pattern("\\b(?:ambassadeur|consul\\s+général|chargé\\s+d['’]affaires)\\b"), "gov.national"),
            // BE-g : Commissaires du gouvernement & administrateurs d'organismes d'intérêt public
            new PepFunction("commissaire-gvt", pattern("\\b(?:commissaire\\s+général|commissaire\\s+du\\s+gouvernement)\\b"), "gov.financial"),
            new PepFunction("administrateur-public", pattern("\\b(?:administrateurs?|membres?\\s+du\\s+conseil\\s+d['’]administration)\\s+de\\s+la\\s+(?:société|commission|université|agence|autorité|banque)\\b"), "gov.financial"),
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

        long delayMs = Long.parseLong(p.getOrDefault("delayMs", DEFAULT_DELAY_MS).trim());
        int days = Integer.parseInt(p.getOrDefault("days", DEFAULT_SINCE_DAYS).trim());
        LocalDate until = LocalDate.now();
        LocalDate since = until.minusDays(days);
        String baseUrl = p.get("baseUrl");
        MoniteurDownloader downloader = new MoniteurDownloader(Duration.ofMillis(delayMs));
        downloader.download(baseUrl, since, until, rawDir);
        downloader.downloadJudicialSources(p, rawDir);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(rawDir, p, oldestEndDate(p, LocalDate.now()), LocalDate.now(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Map<String, String> p = ctx.parameters() != null ? ctx.parameters() : Map.of();
        convert(ctx.rawDir(), p, oldestEndDate(p, LocalDate.now()), LocalDate.now(), dataset);
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
        return convert(rawDir, Map.of(), oldestEndDate, today, dataset);
    }

    Report convert(Path rawDir, Map<String, String> params, LocalDate oldestEndDate, LocalDate today,
            FtmDatasetBuilder dataset) throws Exception {
        List<Path> articleFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rawDir, "article-*.html")) {
            for (Path entry : stream) {
                articleFiles.add(entry);
            }
        }

        List<SourcedAppointment> rawAppointments = new ArrayList<>();
        int totalEvents = 0;

        // 1. Sources directes des hautes juridictions (Cour de cassation et Collège des cours et tribunaux)
        List<SourcedAppointment> judicialAppointments = JudicialSourcesReader.readAll(rawDir, params, oldestEndDate);
        totalEvents += judicialAppointments.size();
        rawAppointments.addAll(judicialAppointments);

        // 2. Actes du Moniteur belge
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
                rawAppointments.add(new SourcedAppointment(sourceUrl, decreeDate, app));
            }
        }

        // Indexer les noms complets connus par (nom de famille normalisé + initiale du prénom)
        // afin d'enrichir les mentions du Moniteur réduites à une initiale lorsqu'elles correspondent à un magistrat connu.
        Map<String, String> fullNamesByInitialKey = new LinkedHashMap<>();
        for (SourcedAppointment sa : rawAppointments) {
            String normalizedName = normalizeDisplayName(sa.appointment().fullName());
            if (!isInitialOnlyName(normalizedName)) {
                String ik = initialKey(normalizedName);
                if (ik != null) {
                    fullNamesByInitialKey.putIfAbsent(ik, normalizedName);
                }
            }
        }

        List<Event> events = new ArrayList<>();
        List<String> samples = new ArrayList<>();

        for (SourcedAppointment sa : rawAppointments) {
            Appointment app = sa.appointment();
            PepFunction fn = matchPepFunction(app.function());
            if (fn == null) {
                continue;
            }
            String normalizedName = normalizeDisplayName(app.fullName());
            if (isInitialOnlyName(normalizedName)) {
                String ik = initialKey(normalizedName);
                String resolved = ik != null ? fullNamesByInitialKey.get(ik) : null;
                if (resolved == null) {
                    // Rejeter les noms réduits à une seule initiale (ex. « Algoet I. », « Collinet T. »)
                    continue;
                }
                normalizedName = resolved;
            }
            Appointment normalizedApp = new Appointment(
                    app.kind(), app.civility(), normalizedName, app.function(), app.effective(), app.until());
            events.add(new Event(sa.sourceUrl(), sa.actDate(), normalizedApp, fn));
            if (samples.size() < 10) {
                samples.add(normalizedName + " (" + app.function() + ")");
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
            log.warn("Aucune personne PEP extraite des {} actes du Moniteur belge.", articleFiles.size());
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
                    m.function = a.function();
                    if (a.until() != null) {
                        m.end = a.until();
                        m.open = false;
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
                if (a.until() != null) {
                    m.open = false;
                }
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
        if (parts[0] != null && !parts[0].isBlank()) {
            person.addValue("firstName", parts[0]);
        }
        if (parts[1] != null && !parts[1].isBlank()) {
            person.addValue("lastName", parts[1]);
        }
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
        String posName = capitalize(m.function);
        String posKey = functionKey(m.function);
        person.addValue("position", posName);

        FtmEntity position = dataset.newEntity("Position", posKey);
        position.addValue("name", posName);
        position.addValue("country", "be");
        position.addValue("topics", m.pepFunction.topic());
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", m.personKey, posKey, m.actUrl.replaceAll("[^a-zA-Z0-9_-]", "_"));
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("description", posName);
        if (m.start != null) {
            occupancy.addValue("startDate", m.start.toString());
        }
        if (m.end != null) {
            occupancy.addValue("endDate", m.end.toString());
        }
        String status = m.end == null
                ? (m.start != null ? "current" : "unknown")
                : (m.end.isBefore(today) ? "ended" : "current");
        occupancy.addValue("status", status);
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
        String normalized = normalizeDisplayName(fullName);
        return Normalizer.normalize(normalized.toLowerCase(Locale.FRENCH).trim(), Normalizer.Form.NFD)
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

    /**
     * Normalise l'affichage d'un nom sous la forme « Prénom Nom » lorsque la source utilise « NOM Prénom »
     * (ex. « SOETE Dennis » → « Dennis SOETE », « LEJEUNE DE SCHIERVEL Colienne » → « Colienne LEJEUNE DE SCHIERVEL »).
     */
    static String normalizeDisplayName(String fullName) {
        if (fullName == null) {
            return "";
        }
        String clean = fullName.replaceAll("\\s+", " ").trim();
        String[] parts = splitName(clean);
        if (parts[0] != null && !parts[0].isBlank() && parts[1] != null && !parts[1].isBlank()) {
            return parts[0] + " " + parts[1];
        }
        return clean;
    }

    /**
     * Indique si le prénom est absent ou réduit à une initiale (« Algoet I. », « Collinet T. », « Verschueren J-M. »).
     */
    static boolean isInitialOnlyName(String fullName) {
        String[] parts = splitName(fullName);
        String first = parts[0];
        String last = parts[1];
        if (first == null || first.isBlank() || last == null || last.isBlank()) {
            return true;
        }
        if (first.contains(".")) {
            return true;
        }
        String lastLetters = last.replaceAll("[^\\p{L}]", "");
        if (lastLetters.length() < 2) {
            return true;
        }
        return Arrays.stream(first.split("[\\s-]+"))
                .map(t -> t.replaceAll("[^\\p{L}]", ""))
                .anyMatch(t -> t.length() < 2);
    }

    private static String initialKey(String fullName) {
        String[] parts = splitName(fullName);
        if (parts[0] == null || parts[0].isBlank() || parts[1] == null || parts[1].isBlank()) {
            return null;
        }
        String firstLetters = Normalizer.normalize(parts[0], Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z]", "")
                .toLowerCase(Locale.ROOT);
        String lastLetters = Normalizer.normalize(parts[1], Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z]", "")
                .toLowerCase(Locale.ROOT);
        if (firstLetters.isEmpty() || lastLetters.length() < 2) {
            return null;
        }
        return lastLetters + "|" + firstLetters.charAt(0);
    }

    /**
     * Découpe un nom complet en {@code [firstName, lastName]} en gérant :
     * <ul>
     *   <li>« Nom Initiale. » (ex. « Vandermeersch D. », « Van der Mersch X. ») ;</li>
     *   <li>« NOM COMPOSÉ Prénom » (ex. « SOETE Dennis », « VAN SANTVOORT Lars », « LEJEUNE DE SCHIERVEL Colienne ») ;</li>
     *   <li>« Prénom NOM COMPOSÉ » (ex. « Colienne LEJEUNE DE SCHIERVEL », « Jean-Marc GASPARD ») ;</li>
     *   <li>« Prénom Nom avec particules » (ex. « Eric de Formanoir de la Cazerie », « Pieter De Crem »).</li>
     * </ul>
     */
    static String[] splitName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return new String[] { null, "" };
        }
        String clean = fullName.replaceAll("\\s+", " ").trim();
        String[] words = clean.split(" ");
        if (words.length == 1) {
            return new String[] { null, words[0] };
        }

        // 1. Dernier mot = initiale (« Vandermeersch D. », « Verschueren J-M. »)
        String lastWord = words[words.length - 1];
        if (lastWord.matches("^[A-ZÀ-ÖØ-Ý](?:-[A-ZÀ-ÖØ-Ý])*\\.?$") || lastWord.endsWith(".")) {
            String last = String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1));
            return new String[] { lastWord.replace(".", ""), last };
        }

        // 2. Premier mot = initiale (« D. Vandermeersch »)
        if (words[0].matches("^[A-ZÀ-ÖØ-Ý](?:-[A-ZÀ-ÖØ-Ý])*\\.?$")) {
            String last = String.join(" ", Arrays.copyOfRange(words, 1, words.length));
            return new String[] { words[0].replace(".", ""), last };
        }

        // 3. Format « NOM EN MAJUSCULES Prénom » (ex. « SOETE Dennis », « VAN SANTVOORT Lars », « LEJEUNE DE SCHIERVEL Colienne »)
        if (isAllCapsWord(words[0]) && !isAllCapsWord(lastWord)) {
            int splitIdx = 0;
            while (splitIdx < words.length && isAllCapsWord(words[splitIdx])) {
                splitIdx++;
            }
            if (splitIdx > 0 && splitIdx < words.length) {
                String last = String.join(" ", Arrays.copyOfRange(words, 0, splitIdx));
                String first = String.join(" ", Arrays.copyOfRange(words, splitIdx, words.length));
                return new String[] { first, last };
            }
        }

        // 4. Format « Prénom NOM EN MAJUSCULES » (ex. « Colienne LEJEUNE DE SCHIERVEL », « Jean-Marc GASPARD »)
        if (!isAllCapsWord(words[0]) && isAllCapsWord(lastWord)) {
            int splitIdx = words.length;
            while (splitIdx > 1 && isAllCapsWord(words[splitIdx - 1])) {
                splitIdx--;
            }
            String first = String.join(" ", Arrays.copyOfRange(words, 0, splitIdx));
            String last = String.join(" ", Arrays.copyOfRange(words, splitIdx, words.length));
            return new String[] { first, last };
        }

        // 5. Format usuel belge « Prénom Nom [ à particules / composé ] »
        // (les prénoms composés belges prennent un trait d'union : Jean-Marc, Marie-Noëlle, Marie-Claire)
        String first = words[0];
        String last = String.join(" ", Arrays.copyOfRange(words, 1, words.length));
        return new String[] { first, last };
    }

    private static boolean isAllCapsWord(String token) {
        String letters = token.replaceAll("[^\\p{L}]", "");
        return letters.length() >= 2 && letters.equals(letters.toUpperCase(Locale.FRENCH));
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
