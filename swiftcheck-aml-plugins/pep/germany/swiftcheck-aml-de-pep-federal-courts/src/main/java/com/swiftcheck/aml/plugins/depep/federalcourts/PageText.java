package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture d'une page web en lignes de texte et reconnaissance stricte des noms de personnes.
 * <p>
 * Aucune liste de noms n'est embarquée : les noms viennent uniquement de la page téléchargée. Un nom n'est accepté que
 * si la ligne a la forme « [libellé de fonction] Nom [libellé de fonction] » ; tout le reste (adresses, menus,
 * légendes, titres de rubrique) est refusé.
 */
final class PageText {

    private PageText() {
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(Map.entry("nbsp", " "), Map.entry("amp", "&"),
            Map.entry("quot", "\""), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("auml", "ä"), Map.entry("ouml", "ö"),
            Map.entry("uuml", "ü"), Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"), Map.entry("Uuml", "Ü"), Map.entry("szlig", "ß"),
            Map.entry("eacute", "é"), Map.entry("egrave", "è"), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("middot", "·"), Map.entry("hellip", "…"), Map.entry("copy", "©"), Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("apos", "'"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"), Map.entry("shy", ""));

    /** Texte d'une page en lignes non vides, scripts, styles, menus et pieds de page écartés. */
    static List<String> lines(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<!--.*?-->|<noscript.*?</noscript>|<nav.*?</nav>|<footer.*?</footer>", " ");
        s = s.replaceAll("(?i)</(p|div|li|h\\d|tr|td|th|section|article|ul|ol|table|figcaption|dd|dt)>|<br\\s*/?>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = decode(s);
        List<String> out = new ArrayList<>();
        for (String raw : s.split("\\R")) {
            String t = raw.replace("­", "").replace(' ', ' ').replaceAll("[ \\t]+", " ").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static String decode(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            if (e.startsWith("#x")) {
                rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            } else if (e.startsWith("#")) {
                rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } else {
                rep = ENTITIES.getOrDefault(e, m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ noms de personnes

    private static final Pattern TITLE = Pattern.compile("^(Prof\\.?|Dr\\.?|Dr\\.-Ing\\.|h\\.c\\.|hc|mult\\.|Dres\\.|Ass\\.|jur\\.|\\(NLU|Charkiw\\))$");
    private static final Pattern CAP_TOKEN = Pattern.compile("^\\p{Lu}[\\p{L}'’\\-]*\\.?$");
    private static final java.util.Set<String> PARTICLES = java.util.Set.of("von", "van", "de", "der", "den", "zu", "zur", "vom",
            "ter", "ten", "dos", "da", "di", "del", "la", "le");
    /** Mots de fonction et de libellé qui peuvent encadrer un nom sur une ligne. */
    private static final java.util.Set<String> FUNCTION_WORDS = java.util.Set.of("des", "der", "die", "das", "dem", "den", "am",
            "an", "im", "in", "bei", "beim", "vom", "zum", "zur", "und", "a.D.", "a.", "D.", "a.D", "i.R.", "–", "-", "sowie", "als", "mit", "ohne");
    private static final Pattern STOP_SUFFIX = Pattern.compile("^.*(gericht|gerichts|gerichtshof|gerichtshofs|amt|amts|amtes|ministerium|universität|hochschule|rechnungshof|"
            + "professor|professorin|direktor|direktorin|leiter|leiterin|referent|referentin|sekretär|sekretärin|beauftragte|beauftragter|anwalt|anwältin|"
            + "notar|notarin|richter|richterin|richterinnen|präsident|präsidentin|vizepräsident|vizepräsidentin|vorsitzende|vorsitzender|mitglied|mitglieder|"
            + "stellvertreter|stellvertreterin|stellvertretende|beigeordnete|beigeordneter|landkreistag|verband|stiftung|institut|akademie|fakultät|regierungsrat|"
            + "ministerialrat|staatsrat|kirchenrat|ordinariatsrat|verwaltungsdirektor|verwaltungsdirektorin|staatsanwalt|staatsanwältin|senat|senats|vorstand)$");
    private static final Pattern STOP_PREFIX = Pattern.compile("^(vizepräsident|präsident|bundes|landes|verfassungs|staats|regierungs|geschäfts|aufsicht|verwaltungs|"
            + "kirchen|ordinariat|kreisverwaltungs|abteilungs|ehemalige|frühere|berufsricht|nichtberuf|stellvertret|beigeordnet|zivil|straf|oberlandes|"
            + "oberverwaltungs|oberstaats|oberregierungs).*$");
    private static final java.util.Set<String> STOP_EXACT = java.util.Set.of("freistaates", "freistaat", "freien", "hansestadt", "baden-württemberg",
            "bayern", "bayerischen", "bayerischer", "bayer.", "berlin", "brandenburg", "bremen", "hamburg", "hamburgischen", "hessen", "hessischen",
            "mecklenburg-vorpommern", "niedersachsen", "niedersächsischen", "nordrhein-westfalen", "rheinland-pfalz", "saarland", "saarlandes",
            "sachsen", "sachsen-anhalt", "sächsischen", "schleswig-holstein", "thüringen", "thüringer", "deutschland", "nds.", "m-v", "lverfg", "stgh",
            "vgh", "ostdeutschland", "recht", "rechts", "profil", "bild", "quelle", "datum", "weitere", "andere", "verzeichnis", "richterinnen",
            "vors.", "stv.", "st.", "dir.", "befähigung", "öffentliches", "öffentliche", "wirtschaft", "kanzlei", "bank", "agentur",
            "behörde", "schule", "rat");

    record Candidate(String name, String title, String cleanName) {
    }

    /** Premier nom de personne valide d'une ligne, ou null. */
    static Candidate person(String line) {
        return person(line, false);
    }

    static Candidate person(String line, boolean allowSurnameOnly) {
        if (line == null || line.contains("†") || line.contains(":") || line.matches(".*\\d.*") || line.length() > 140) {
            return null;
        }
        String s = line.replaceAll("\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceFirst("^Profil\\s+", "");
        int comma = s.indexOf(',');
        if (comma > 0) {
            String head = s.substring(0, comma).trim();
            // « Nachname, Titel » ou « Nom , fonction » : on garde la partie qui précède la virgule
            s = head;
        }
        s = s.replaceAll("\\s+[–-]\\s+.*$", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        String[] t = s.split(" ");
        // jetons candidats au nom
        boolean[] nameLike = new boolean[t.length];
        for (int i = 0; i < t.length; i++) {
            nameLike[i] = isNameToken(t, i);
        }
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < t.length) {
            if (!nameLike[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j < t.length && nameLike[j]) {
                j++;
            }
            runs.add(new int[] {i, j});
            i = j;
        }
        Candidate found = null;
        for (int[] r : runs) {
            // supprimer les particules / titres de tête ou de queue isolés
            int a = r[0];
            int b = r[1];
            while (a < b && PARTICLES.contains(t[a])) {
                a++;
            }
            while (b > a && PARTICLES.contains(t[b - 1])) {
                b--;
            }
            int plain = 0;
            int titles = 0;
            for (int k = a; k < b; k++) {
                if (TITLE.matcher(t[k]).matches()) {
                    titles++;
                } else if (!PARTICLES.contains(t[k])) {
                    plain++;
                }
            }
            boolean enough = plain >= 2 || (allowSurnameOnly && plain == 1 && titles >= 1);
            if (!enough || plain > 5) {
                continue;
            }
            if (!roleOnly(t, 0, a) || !roleOnly(t, b, t.length)) {
                continue;
            }
            if (found != null) {
                return null; // deux noms possibles sur la même ligne : ambigu, refusé
            }
            String name = String.join(" ", java.util.Arrays.copyOfRange(t, a, b));
            StringBuilder title = new StringBuilder();
            int k = a;
            while (k < b && TITLE.matcher(t[k]).matches()) {
                title.append(title.length() == 0 ? "" : " ").append(t[k]);
                k++;
            }
            String clean = String.join(" ", java.util.Arrays.copyOfRange(t, k, b));
            found = new Candidate(name, title.length() == 0 ? null : title.toString(), clean);
        }
        return found;
    }

    private static boolean isNameToken(String[] t, int i) {
        String w = t[i];
        if (TITLE.matcher(w).matches()) {
            return true;
        }
        if (PARTICLES.contains(w)) {
            return i > 0 && i < t.length - 1;
        }
        if (!CAP_TOKEN.matcher(w).matches()) {
            return false;
        }
        if (w.length() >= 2 && !w.endsWith(".") && w.equals(w.toUpperCase(Locale.ROOT))) {
            return false; // sigle (SE, AG, CEO) : jamais un nom de personne
        }
        if (isStop(w)) {
            // « Frank Richter » : un nom de famille qui est aussi un libellé, en fin de ligne après un prénom
            return i == t.length - 1 && i > 0 && CAP_TOKEN.matcher(t[i - 1]).matches() && !isStop(t[i - 1])
                    && w.equalsIgnoreCase("Richter");
        }
        return true;
    }

    static boolean isStop(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        return STOP_EXACT.contains(l) || STOP_SUFFIX.matcher(l).matches() || STOP_PREFIX.matcher(l).matches();
    }

    /** Les jetons hors nom ne doivent être que des libellés de fonction, de lieu ou des mots de liaison. */
    private static boolean roleOnly(String[] t, int from, int to) {
        for (int i = from; i < to; i++) {
            String w = t[i];
            if (!(isStop(w) || FUNCTION_WORDS.contains(w) || FUNCTION_WORDS.contains(w.toLowerCase(Locale.ROOT)))) {
                return false;
            }
        }
        return true;
    }
}
