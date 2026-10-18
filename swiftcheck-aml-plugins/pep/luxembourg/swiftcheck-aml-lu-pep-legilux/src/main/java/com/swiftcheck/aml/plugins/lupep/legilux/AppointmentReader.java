package com.swiftcheck.aml.plugins.lupep.legilux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit les nominations, renouvellements et démissions dans le texte d'un acte. Les publications de Legilux
 * suivent trois tournures :
 * <pre>
 * Nomination      : Par arrêté grand-ducal du 24 juillet 2026, Monsieur Stephen DE RON, maître en droit,
 *                   est nommé membre du Conseil d’État, avec effet au 15 septembre 2026.
 * Renouvellement  : Par arrêté grand-ducal du 10 juillet 2026, la nomination de Monsieur Jean-Pierre FABER en tant
 *                   que Directeur à la Commission de surveillance du secteur financier, a été renouvelée pour une
 *                   période de cinq ans à partir du 1 er septembre 2026.
 * Démission       : Par arrêté grand-ducal du 29 juillet 2026, démission honorable de ses fonctions de membre du
 *                   Conseil d’État est accordée à Madame Deidre DU BOIS, avec effet au 15 septembre 2026.
 * </pre>
 * Une même phrase peut contenir une nomination et une démission. Une tournure non reconnue (ex. plusieurs
 * personnes nommées ensemble, « sont nommés ») ne donne rien : l'acte est alors signalé comme atypique par
 * le parser s'il concerne une haute fonction.
 */
final class AppointmentReader {

    enum Kind { NOMINATION, RENEWAL, RESIGNATION }

    /**
     * Un événement lu dans un acte.
     *
     * @param function  fonction telle qu'écrite, ex. « conseiller à la Cour de cassation »
     * @param effective date d'effet (« avec effet au », « à partir du », « à compter du ») ; {@code null} si absente
     * @param until     date de fin prévue (« jusqu'au », ou date d'effet + durée « pour une période de N ans »)
     */
    record Appointment(Kind kind, String civility, String fullName, String function, LocalDate effective,
            LocalDate until) {
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("février", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("août", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11),
            Map.entry("décembre", 12));

    private static final Map<String, Integer> NUMBERS = Map.of(
            "un", 1, "deux", 2, "trois", 3, "quatre", 4, "cinq", 5, "six", 6, "sept", 7, "huit", 8, "neuf", 9,
            "dix", 10);

    private static final String DATE = "(\\d{1,2})(?:er)? (janvier|février|mars|avril|mai|juin|juillet|août"
            + "|septembre|octobre|novembre|décembre) (\\d{4})";

    /**
     * « Madame Prénom NOM » : le nom s'arrête à la virgule, au mot qui suit (« en tant que », « est »…) ou à la fin
     * de la phrase.
     */
    private static final String PERSON = "(Madame|Monsieur) (.+?)(?=,| en tant que | est | a été | avec effet"
            + "|\\.(?: |$)|$)";

    /** Fin d'une fonction écrite : virgule, date, durée, autre événement ou fin de phrase. */
    private static final String FUNCTION_END = "(?=,| avec effet| à partir du| à compter du| pour une| jusqu'au"
            + "| et démission| et (?:Madame|Monsieur) |\\.(?: |$)|;|$)";

    /** « Monsieur X, qualité, est (ou a été) nommé (ou désigné) fonction ». */
    private static final Pattern NOMINATION = Pattern.compile(PERSON + ".*?,? (?:est|a été) (?:nommée?|désignée?)"
            + " (.+?)" + FUNCTION_END);

    /** « la nomination (ou le mandat) de Monsieur X[, qualité,] [en tant que fonction,] a été (ou est) renouvelé(e) ». */
    private static final Pattern RENEWAL = Pattern.compile("(?:nomination|mandat) de " + PERSON + ",? (.+?),?"
            + " (?:a été|est) renouvelée?");

    /** « Monsieur X est (ou a été) continué (ou reconduit) dans sa fonction de Président du Conseil d'État ». */
    private static final Pattern CONTINUED = Pattern.compile(PERSON + ",?(?: [^,]+,)? (?:est|a été)"
            + " (?:continuée?|reconduite?) dans sa fonction de (.+?)" + FUNCTION_END);

    /**
     * « démission (honorable) de ses fonctions de X [, de Y et de Z] est (ou a été) accordée[, sur sa demande,] à
     * Madame W ».
     */
    private static final Pattern RESIGNATION = Pattern.compile("démission (?:honorable )?de (?:sa|ses) fonctions?"
            + " de (.+?) (?:est|a été) accordée(?:, [^,]+,)? à " + PERSON);

    /** « démission honorable de ses fonctions a été accordée à Madame Z, directeur à la CSSF, avec effet… ». */
    private static final Pattern RESIGNATION_AFTER_NAME = Pattern.compile("démission (?:honorable )?de (?:sa|ses)"
            + " fonctions? (?:est|a été) accordée(?:, [^,]+,)? à " + PERSON + ", (.+?)" + FUNCTION_END);

    /** « Monsieur X est démissionné de ses fonctions de conseiller d'État ». */
    private static final Pattern DISMISSED = Pattern.compile(PERSON + ",? (?:est|a été) démissionnée? de (?:sa|ses)"
            + " fonctions? de (.+?)" + FUNCTION_END);

    /**
     * Séparation de plusieurs fonctions : « vice-président de la Cour supérieure de justice, de conseiller à la Cour
     * de cassation et de membre de la Cour constitutionnelle ». On ne coupe que devant un mot de fonction, pour ne
     * pas couper « tribunal de la jeunesse et des tutelles ».
     */
    private static final Pattern SEVERAL_FUNCTIONS = Pattern.compile("(?:,| et)(?: de)? (?=(?:conseiller|membre"
            + "|président|présidente|vice-président|vice-présidente|procureur|directeur|directrice)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Tournure qui introduit la fonction, retirée : « est nommé à la fonction de vice-président… ». */
    private static final Pattern FUNCTION_PREFIX = Pattern.compile("^(?:à la fonction de|aux fonctions de|en tant que"
            + "|comme) ", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EFFECTIVE = Pattern.compile("(?:avec effet (?:au|à partir du)|à partir du"
            + "|à compter du) " + DATE);

    private static final Pattern UNTIL = Pattern.compile("jusqu'au " + DATE);

    private static final Pattern DURATION = Pattern.compile("pour une (?:période|durée)(?: renouvelable)? de"
            + " (\\d+|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix) ans");

    private AppointmentReader() {
    }

    /** Les événements du texte, dans l'ordre des tournures (nominations, renouvellements, démissions). */
    static List<Appointment> read(String text) {
        List<Appointment> found = new ArrayList<>();
        for (String paragraph : normalize(text).split("\n")) {
            if (paragraph.contains(" sont nommé")) {
                continue;                       // plusieurs personnes à la fois : tournure non prise en charge
            }
            Matcher m = NOMINATION.matcher(paragraph);
            while (m.find()) {
                String clause = paragraph.substring(m.end());
                LocalDate effective = date(EFFECTIVE, clause);
                add(found, Kind.NOMINATION, m.group(1), m.group(2), m.group(3), effective, until(clause, effective));
            }
            m = RENEWAL.matcher(paragraph);
            while (m.find()) {
                String clause = paragraph.substring(m.end());
                LocalDate effective = date(EFFECTIVE, clause);
                add(found, Kind.RENEWAL, m.group(1), m.group(2), renewedFunction(m.group(3)), effective,
                        until(clause, effective));
            }
            m = CONTINUED.matcher(paragraph);
            while (m.find()) {
                String clause = paragraph.substring(m.end());
                LocalDate effective = date(EFFECTIVE, clause);
                add(found, Kind.RENEWAL, m.group(1), m.group(2), m.group(3), effective, until(clause, effective));
            }
            m = RESIGNATION.matcher(paragraph);
            while (m.find()) {
                add(found, Kind.RESIGNATION, m.group(2), m.group(3), m.group(1),
                        date(EFFECTIVE, paragraph.substring(m.end())), null);
            }
            m = RESIGNATION_AFTER_NAME.matcher(paragraph);
            while (m.find()) {
                add(found, Kind.RESIGNATION, m.group(1), m.group(2), m.group(3),
                        date(EFFECTIVE, paragraph.substring(m.end())), null);
            }
            m = DISMISSED.matcher(paragraph);
            while (m.find()) {
                add(found, Kind.RESIGNATION, m.group(1), m.group(2), m.group(3),
                        date(EFFECTIVE, paragraph.substring(m.end())), null);
            }
        }
        return found;
    }

    /** Ajoute un événement par fonction écrite (une phrase peut en citer plusieurs). */
    private static void add(List<Appointment> found, Kind kind, String civility, String name, String functions,
            LocalDate effective, LocalDate until) {
        for (String function : SEVERAL_FUNCTIONS.split(functions.trim())) {
            String cleaned = FUNCTION_PREFIX.matcher(function.trim()).replaceFirst("").replaceFirst(",$", "").trim();
            if (!cleaned.isEmpty()) {
                found.add(new Appointment(kind, civility, name.trim(), cleaned, effective, until));
            }
        }
    }

    /**
     * Fonction d'un renouvellement : ce qui suit « en tant que » ou « comme » s'il est présent, sinon la qualité
     * écrite après le nom (« Monsieur Marc GENGLER, Président de la Cour des comptes, est renouvelée »).
     */
    private static String renewedFunction(String text) {
        int asIndex = text.lastIndexOf("en tant que ");
        if (asIndex >= 0) {
            return text.substring(asIndex + "en tant que ".length());
        }
        // « le mandat de Monsieur X, qualité, comme membre du Conseil de la CSSF »
        int likeIndex = text.lastIndexOf("comme ");
        return likeIndex >= 0 ? text.substring(likeIndex + "comme ".length()) : text;
    }

    /** Date de l'arrêté (« Par arrêté grand-ducal du 24 juillet 2026 »), ou {@code null}. */
    static LocalDate decreeDate(String text) {
        Matcher m = Pattern.compile("arrêté grand-ducal du " + DATE).matcher(normalize(text));
        return m.find() ? toDate(m) : null;
    }

    /**
     * Apostrophes typographiques, espaces insécables, « 1 er » → « 1er », espaces multiples : le texte officiel
     * en contient beaucoup.
     */
    static String normalize(String text) {
        return text.replace('’', '\'')
                .replace(' ', ' ')
                .replace(' ', ' ')
                .replaceAll("(\\d) er\\b", "$1er")
                .replaceAll("[ \\t]+", " ");
    }

    /** Fin prévue : « jusqu'au » ; sinon date d'effet + durée (« pour une période de cinq ans »). */
    private static LocalDate until(String clause, LocalDate effective) {
        LocalDate until = date(UNTIL, clause);
        if (until != null) {
            return until;
        }
        Matcher d = DURATION.matcher(clause);
        if (d.find() && effective != null) {
            // « pour une période de cinq ans à partir du 1er septembre 2026 » → jusqu'au 31 août 2031
            return effective.plusYears(years(d.group(1))).minusDays(1);
        }
        return null;
    }

    /** « cinq » ou « 5 » → 5. */
    private static int years(String value) {
        Integer inWords = NUMBERS.get(value);
        return inWords != null ? inWords : Integer.parseInt(value);
    }

    /** Première date de la clause qui suit l'expression ; {@code null} si absente. */
    private static LocalDate date(Pattern pattern, String clause) {
        Matcher m = pattern.matcher(clause);
        return m.find() ? toDate(m) : null;
    }

    /** Groupes (jour, mois en lettres, année) des derniers groupes du motif. */
    private static LocalDate toDate(Matcher m) {
        int n = m.groupCount();
        return LocalDate.of(Integer.parseInt(m.group(n)), MONTHS.get(m.group(n - 1)), Integer.parseInt(m.group(n - 2)));
    }
}
