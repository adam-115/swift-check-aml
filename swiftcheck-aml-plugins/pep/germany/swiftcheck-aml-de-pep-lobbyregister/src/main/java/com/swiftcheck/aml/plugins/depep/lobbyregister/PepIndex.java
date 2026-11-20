package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugins.depep.lobbyregister.GovFunctions.PepFunction;
import com.swiftcheck.aml.plugins.depep.lobbyregister.GovFunctions.Status;

/**
 * Base PEP SwiftCheck remise par le core (paramètre {@code pepIndexFile}, une PEP par ligne) et rapprochement des
 * personnes du Lobbyregister avec elle (Story 5, étape 1 : pantouflage / cumul).
 * <p>
 * <b>Rapprochement</b> : nom normalisé identique (titres retirés, umlauts translittérés), ou Jaro-Winkler ≥ 0,93
 * sur « nom prénom » (le nom d’abord : un prénom identique ne fait pas monter le score) parmi les PEP au nom de
 * famille proche.
 * <p>
 * <b>Contrôle du contexte</b> : une correspondance n'est <i>confirmée</i> (la personne devient PEP, et pivot si sa
 * fonction est en cours ou en refroidissement) que si
 * <ul>
 *   <li>le nom est identique, porté par une seule PEP de la base, d’un nom de famille rare (au plus
 *       {@value #RARE_SURNAME_MAX} PEP), et la PEP est allemande ou européenne, encore PEP et pas titulaire d’une
 *       fonction à plein temps incompatible avec un mandat privé (juge fédéral, diplomate, militaire) ; ou</li>
 *   <li>le contexte du registre recoupe celui de la PEP : un mot significatif commun entre la fonction ou l'entité
 *       déclarée au registre et la fonction de la PEP (ex. « Bundestag », « Bundesverband … »).</li>
 * </ul>
 * Une correspondance approchée exige toujours le contexte. Les autres restent des <i>candidats</i> : comptés et
 * cités dans le bilan, jamais publiés comme PEP.
 */
final class PepIndex {

    static final double JARO_WINKLER_MIN = 0.93;

    /** Une PEP de la base. */
    record Pep(String dataset, String id, List<String> names, String firstName, String lastName, String birthDate,
               List<String> positions, List<String> countries, String sourceUrl, String status, String endDate) {

        String label() {
            return positions.isEmpty() ? "PEP (" + dataset + ")" : positions.get(0);
        }

        /** Fonction équivalente à une fonction déclarée au registre (statut repris de la base). */
        PepFunction function(LocalDate today) {
            Status s = switch (status == null ? "" : status) {
                case "COOLING" -> Status.COOLING;
                case "EXPIRED" -> Status.EXPIRED;
                default -> Status.ACTIVE_PEP;
            };
            String until = endDate != null ? endOf(endDate).plusMonths(12).toString() : null;
            if (s == Status.COOLING && until != null && today.isAfter(LocalDate.parse(until))) s = Status.EXPIRED;
            return new PepFunction(label(), "GwG § 1 Abs. 12 (SwiftCheck-PEP-Bestand : " + dataset + ")", s,
                    s == Status.ACTIVE_PEP ? null : (endDate != null ? endDate : "?"), until != null ? until : "?");
        }
    }

    /** Correspondance trouvée pour une personne du registre. */
    record Match(Pep pep, double score, boolean exact, boolean confirmed, String evidence) {

        String note() {
            return "pep_match: " + pep.dataset() + " / " + pep.id() + " (" + (exact ? "exakter Name" :
                    String.format(Locale.ROOT, "Jaro-Winkler %.3f", score)) + ") ; Kontext : " + evidence;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TITLES = Pattern.compile(
            "\\b(dr|prof|professor|med|jur|rer|nat|pol|phil|oec|dent|vet|ing|dipl|mag|mba|llm|ll|m|h|c|habil|em|a|d|e|b|sc|ma|ba)\\b");
    /** Noms de famille les plus fréquents en Allemagne : un nom identique ne suffit pas. */
    private static final Set<String> COMMON_SURNAMES = Set.of("mueller", "schmidt", "schneider", "fischer", "weber",
            "meyer", "meier", "wagner", "becker", "schulz", "hoffmann", "schaefer", "koch", "bauer", "richter",
            "klein", "wolf", "schroeder", "neumann", "schwarz", "zimmermann", "braun", "krueger", "hofmann",
            "hartmann", "lange", "schmitt", "werner", "schmitz", "krause", "lehmann", "schmid", "schulze",
            "maier", "koehler", "herrmann", "koenig", "walter", "mayer", "huber", "kaiser", "fuchs", "peters", "lang",
            "scholz", "moeller", "weiss", "jung", "hahn", "schubert", "vogel", "friedrich", "keller", "guenther",
            "frank", "berger", "winkler", "roth", "beck", "lorenz", "baumann", "franke", "albrecht", "schuster",
            "simon", "ludwig", "boehm", "winter", "kraus", "martin", "schumacher", "kraemer", "vogt", "stein",
            "jaeger", "otto", "sommer", "gross", "seidel", "heinrich", "brandt", "haas");
    /** Mots trop généraux pour prouver un contexte commun. */
    private static final Set<String> STOP = Set.of("mitglied", "deutschen", "deutsche", "deutscher", "deutschland",
            "bundesrepublik", "membre", "member", "praesident", "praesidentin", "president", "direktor", "direktorin",
            "vorsitzender", "vorsitzende", "vorstand", "vorstands", "geschaeftsfuehrer", "geschaeftsfuehrerin",
            "leiter", "leiterin", "stellvertretender", "stellvertretende", "gesellschaft", "verband", "verein",
            "abteilung", "mitarbeiter", "referent", "referentin", "berater", "beraterin", "partner", "gmbh", "national",
            "politische", "person", "interessenvertreter", "natuerliche", "betraute", "vertretungsberechtigte",
            "europaeischen", "europaeische", "union", "germany", "allemagne", "federal", "fonction", "vorstandsmitglied",
            "vorstandsvorsitzender", "vorstandsvorsitzende", "vizepraesident", "vizepraesidentin", "geschaeftsfuehrung",
            "personal", "gemeinschaft", "saint", "umwelt", "richter", "richterin", "aufsichtsrat", "aufsichtsrats",
            "verwaltung", "bereich", "sprecher", "sprecherin", "generalsekretaer", "generalsekretaerin", "institut",
            "stiftung", "zentrum", "international", "group", "holding", "services", "management", "consulting",
            // Noms de Länder et de régions : trop larges pour prouver à eux seuls qu’il s’agit de la même personne.
            "baden", "wuerttemberg", "bayern", "berlin", "brandenburg", "bremen", "hamburg", "hessen", "mecklenburg",
            "vorpommern", "niedersachsen", "nordrhein", "westfalen", "rheinland", "pfalz", "saarland", "sachsen",
            "anhalt", "schleswig", "holstein", "thueringen", "general", "manager", "europa", "recht", "systems",
            "director", "officer", "chief", "executive", "board");
    /** Un nom de famille porté par plus de PEP que cela dans la base n’est pas assez rare pour se passer de contexte. */
    static final int RARE_SURNAME_MAX = 5;
    /**
     * Fonctions à plein temps incompatibles avec un mandat dans une entité privée (juges fédéraux, diplomates en
     * poste, militaires) : une homonymie est bien plus probable qu’un cumul, le contexte est exigé.
     */
    private static final Pattern FULL_TIME_FUNCTION = Pattern.compile(
            "^(?:[a-z]{2}_pep_(?:federal_courts|high_courts|diplomats|defence[a-z_]*|army))$");

    private final Map<String, List<Pep>> byName = new HashMap<>();
    private final Map<String, List<Pep>> byBlock = new HashMap<>();
    private final Map<String, Integer> surnameCount = new HashMap<>();
    private final int size;

    private PepIndex(List<Pep> peps) {
        for (Pep p : peps) {
            Set<String> keys = new LinkedHashSet<>();
            for (String n : p.names()) keys.add(normalize(n));
            String fl = firstLast(p.firstName(), p.lastName());
            if (fl != null) keys.add(fl);
            for (String n : p.names()) {
                String[] t = normalize(n).split(" ");
                if (t.length > 2) keys.add(t[0] + " " + t[t.length - 1]);
            }
            String sn = p.lastName() != null ? lastToken(normalize(p.lastName()))
                    : p.names().isEmpty() ? "" : lastToken(normalize(p.names().get(0)));
            if (!sn.isEmpty()) surnameCount.merge(sn, 1, Integer::sum);
            for (String k : keys) {
                if (k.isBlank()) continue;
                byName.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
                String b = block(k);
                if (b != null) byBlock.computeIfAbsent(b, x -> new ArrayList<>()).add(p);
            }
        }
        this.size = peps.size();
    }

    /** Index vide (aucun fichier remis par le core). */
    static PepIndex empty() {
        return new PepIndex(List.of());
    }

    static PepIndex load(Path file) throws IOException {
        List<Pep> peps = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = JSON.readTree(line);
                peps.add(new Pep(n.path("dataset").asText(), n.path("id").asText(), list(n.path("names")),
                        text(n, "firstName"), text(n, "lastName"), text(n, "birthDate"), list(n.path("position")),
                        list(n.path("country")), text(n, "sourceUrl"), text(n, "status"), text(n, "endDate")));
            }
        }
        return new PepIndex(peps);
    }

    int size() {
        return size;
    }

    /**
     * Meilleure correspondance d'une personne du registre, ou null.
     *
     * @param context textes du registre qui situent la personne (fonction, entité, fonction publique déclarée)
     */
    Match match(String firstName, String lastName, List<String> context) {
        String key = firstLast(firstName, lastName);
        if (key == null) return null;
        String fullKey = normalize((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName));
        Set<String> ctx = tokens(context);
        String surname = normalize(lastName);

        List<Pep> exact = new ArrayList<>(byName.getOrDefault(fullKey, List.of()));
        for (Pep p : byName.getOrDefault(key, List.of())) if (!exact.contains(p)) exact.add(p);
        if (!exact.isEmpty()) {
            Match best = null;
            String sn = lastToken(surname);
            boolean uniqueName = distinctPeople(exact) == 1 && !COMMON_SURNAMES.contains(sn)
                    && surnameCount.getOrDefault(sn, 0) <= RARE_SURNAME_MAX;
            for (Pep p : exact) {
                String overlap = overlap(ctx, p);
                // Un nom unique suffit pour une PEP allemande ou européenne encore PEP ; une PEP étrangère ou dont
                // la fonction a pris fin depuis plus de 12 mois exige un contexte commun (homonymes plus probables).
                boolean unique = uniqueName && germanOrEu(p) && !"EXPIRED".equals(p.status())
                        && !FULL_TIME_FUNCTION.matcher(p.dataset()).matches();
                boolean confirmed = overlap != null || unique;
                String evidence = overlap != null ? "gemeinsamer Begriff « " + overlap + " »"
                        : unique ? "einziger Träger dieses Namens im PEP-Bestand"
                        : uniqueName ? "nicht bestätigt (ausländische, ehemalige oder hauptamtliche PEP ohne gemeinsamen Kontext)"
                        : "nicht bestätigt (häufiger oder mehrdeutiger Name)";
                Match m = new Match(p, 1.0, true, confirmed, evidence);
                if (best == null || rank(m) > rank(best)) best = m;
            }
            return best;
        }

        String b = block(key);
        if (b == null) return null;
        Match best = null;
        for (Pep p : byBlock.getOrDefault(b, List.of())) {
            double score = 0;
            for (String n : candidateKeys(p)) score = Math.max(score, jaroWinkler(surnameFirst(key), surnameFirst(n)));
            if (score < JARO_WINKLER_MIN) continue;
            String overlap = overlap(ctx, p);
            Match m = new Match(p, score, false, overlap != null, overlap != null
                    ? "gemeinsamer Begriff « " + overlap + " »" : "nicht bestätigt (ähnlicher Name ohne Kontext)");
            if (best == null || rank(m) > rank(best)) best = m;
        }
        return best;
    }

    /** PEP allemande (jeu de_… ou nationalité de) ou d’une institution de l’Union. */
    static boolean germanOrEu(Pep p) {
        String d = p.dataset() == null ? "" : p.dataset();
        return d.startsWith("de_") || d.endsWith("_europarl") || d.endsWith("_eu_commission")
                || d.endsWith("_eu_institutions") || p.countries().contains("de") || p.countries().contains("eu");
    }

    private static double rank(Match m) {
        return (m.confirmed() ? 10 : 0) + m.score()
                + ("ACTIVE_PEP".equals(m.pep().status()) ? 0.002 : "COOLING".equals(m.pep().status()) ? 0.001 : 0);
    }

    /**
     * Nombre de personnes distinctes parmi les fiches du même nom : une même personne publiée par plusieurs jeux
     * compte une fois, sauf années de naissance différentes ou deux fiches du même nom dans un même jeu.
     */
    private static int distinctPeople(List<Pep> peps) {
        Set<String> births = new LinkedHashSet<>();
        Set<String> datasets = new LinkedHashSet<>();
        for (Pep p : peps) {
            if (!datasets.add(p.dataset())) return 2;
            if (p.birthDate() != null) births.add(p.birthDate().substring(0, Math.min(4, p.birthDate().length())));
        }
        return Math.max(1, births.size());
    }

    private List<String> candidateKeys(Pep p) {
        List<String> keys = new ArrayList<>();
        String fl = firstLast(p.firstName(), p.lastName());
        if (fl != null) keys.add(fl);
        for (String n : p.names()) {
            String[] t = normalize(n).split(" ");
            if (t.length >= 2) keys.add(t[0] + " " + t[t.length - 1]);
        }
        return keys;
    }

    /** Premier mot significatif commun entre le contexte du registre et la fonction de la PEP, ou null. */
    private static String overlap(Set<String> ctx, Pep p) {
        if (ctx.isEmpty()) return null;
        for (String t : tokens(p.positions())) {
            if (ctx.contains(t)) return t;
        }
        return null;
    }

    static Set<String> tokens(List<String> texts) {
        Set<String> result = new LinkedHashSet<>();
        if (texts == null) return result;
        for (String s : texts) {
            if (s == null) continue;
            for (String t : normalize(s).split(" ")) {
                if (t.length() >= 5 && !STOP.contains(t)) result.add(t);
            }
        }
        return result;
    }

    /** Minuscules, umlauts translittérés, accents et ponctuation retirés, titres académiques retirés. */
    static String normalize(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.replaceAll("[^a-z ]", " ");
        n = TITLES.matcher(n).replaceAll(" ");
        return n.trim().replaceAll("\\s+", " ");
    }

    /** « premier prénom + dernier mot du nom », ou null. */
    static String firstLast(String firstName, String lastName) {
        String f = normalize(firstName);
        String l = normalize(lastName);
        if (f.isEmpty() || l.isEmpty()) return null;
        return f.split(" ")[0] + " " + lastToken(l);
    }

    /** « prénom nom » → « nom prénom ». */
    static String surnameFirst(String firstLastKey) {
        int i = firstLastKey.indexOf(' ');
        return i < 0 ? firstLastKey : firstLastKey.substring(i + 1) + " " + firstLastKey.substring(0, i);
    }

    private static String lastToken(String s) {
        String[] t = s.split(" ");
        return t[t.length - 1];
    }

    /** Bloc de comparaison : initiale du prénom + trois premières lettres du nom. */
    private static String block(String firstLastKey) {
        String[] t = firstLastKey.split(" ");
        if (t.length < 2 || t[0].isEmpty() || t[t.length - 1].length() < 2) return null;
        String l = t[t.length - 1];
        return t[0].charAt(0) + ":" + l.substring(0, Math.min(3, l.length()));
    }

    static double jaroWinkler(String a, String b) {
        if (a.equals(b)) return 1.0;
        int la = a.length();
        int lb = b.length();
        if (la == 0 || lb == 0) return 0;
        int window = Math.max(0, Math.max(la, lb) / 2 - 1);
        boolean[] ma = new boolean[la];
        boolean[] mb = new boolean[lb];
        int matches = 0;
        for (int i = 0; i < la; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(lb - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) {
                    ma[i] = mb[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) return 0;
        int t = 0;
        for (int i = 0, k = 0; i < la; i++) {
            if (!ma[i]) continue;
            while (!mb[k]) k++;
            if (a.charAt(i) != b.charAt(k)) t++;
            k++;
        }
        double m = matches;
        double jaro = (m / la + m / lb + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)) && a.charAt(i) == b.charAt(i); i++) prefix++;
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    static LocalDate endOf(String raw) {
        String s = raw.trim();
        try {
            if (s.matches("\\d{4}")) return LocalDate.of(Integer.parseInt(s), 12, 31);
            if (s.matches("\\d{4}-\\d{2}")) return java.time.YearMonth.parse(s).atEndOfMonth();
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (RuntimeException e) {
            return LocalDate.of(1900, 1, 1);
        }
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return v == null || v.isBlank() ? null : v;
    }

    private static List<String> list(JsonNode array) {
        List<String> l = new ArrayList<>();
        for (JsonNode v : array) l.add(v.asText());
        return l;
    }

}
