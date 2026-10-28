package com.swiftcheck.aml.plugins.frpep.army;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture de la biographie d'un chef d'état-major sur defense.gouv.fr. Les pages n'ont pas de structure commune ;
 * trois tournures du texte, relevées sur les pages réelles, suffisent :
 * <pre>
 * « Le général d'armée aérienne Jérôme BELLANGER devient chef d'état-major … »   → grade et nom
 * « Fabien Mandon est né le 19 octobre 1969 à Montmorency (95). »                → naissance
 * « Le 26 juillet 2026, il est élevé … et nommé chef d'état-major de l'armée de Terre. » → prise de fonctions
 * </pre>
 */
final class ChiefPage {

    /** Ce que donne la page ; dates ISO, {@code null} si absentes. */
    record Chief(String rank, String name, String birthDate, String birthPlace, String startDate) {
    }

    /**
     * Grade suivi du nom : mots à majuscule initiale, avec les particules « de », « du », « d' ». Le nom s'arrête
     * avant un mot de grade ou de fonction (« … BELLANGER Général de division … »).
     */
    private static final Pattern RANK_NAME = Pattern.compile(
            "(?iu)\\b(général d['’]armée(?: aérienne)?|amiral)\\s+"
            + "((?-i:\\p{Lu}[\\p{L}'’-]+)(?:\\s+(?:(?:de|du|d['’])\\s*)?"
            + "(?!(?-i:Général|Amiral|Chef|Le|La|Les)\\b)(?-i:\\p{Lu}[\\p{L}'’-]+))*)");
    private static final Pattern BIRTH = Pattern.compile(
            "(?iu)\\bnée? le (\\d{1,2})(?: ?er)? (\\p{L}+) (\\d{4})(?: (?:à|au|aux) ([^(.,]+?))?\\s*(?:\\(|\\.|,|$)");
    private static final Pattern START = Pattern.compile(
            "(?iu)\\ble (\\d{1,2})(?: ?er)? (\\p{L}+) (\\d{4}),[^.]{0,250}?\\b(?:nommée?|devient|prend ses fonctions de) chef");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("fevrier", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));

    private ChiefPage() {
    }

    /**
     * @throws IllegalStateException si aucun grade suivi d'un nom n'est trouvé (page changée ou mauvaise adresse)
     */
    static Chief read(String html) {
        String text = Html.text(html.replaceAll("(?si)<(script|style)[^>]*>.*?</\\1>", " "));
        Matcher name = RANK_NAME.matcher(text);
        if (!name.find()) {
            throw new IllegalStateException("Aucun « général d'armée » ou « amiral » suivi d'un nom sur la page : "
                    + "la page a peut-être changé.");
        }
        Matcher birth = BIRTH.matcher(text);
        String birthDate = null;
        String birthPlace = null;
        if (birth.find()) {
            birthDate = date(birth.group(1), birth.group(2), birth.group(3));
            birthPlace = birth.group(4) == null ? null : birth.group(4).trim();
        }
        Matcher start = START.matcher(text);
        return new Chief(name.group(1).toLowerCase(Locale.ROOT).replace('’', '\''), name.group(2).trim(), birthDate, birthPlace,
                start.find() ? date(start.group(1), start.group(2), start.group(3)) : null);
    }

    private static String date(String day, String month, String year) {
        Integer m = MONTHS.get(Normalizer.normalize(month, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT));
        return m == null ? null : String.format("%s-%02d-%02d", year, m, Integer.parseInt(day));
    }
}
