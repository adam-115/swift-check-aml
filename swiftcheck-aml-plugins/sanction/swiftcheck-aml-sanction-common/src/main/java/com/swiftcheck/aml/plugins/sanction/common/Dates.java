package com.swiftcheck.aml.plugins.sanction.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.swiftcheck.aml.sdk.DateNormalizer;

/**
 * Dates écrites en toutes lettres dans les listes de sanctions (OFAC, Royaume-Uni, Australie…) :
 * « 12 Mar 1965 », « March 1965 », « circa 1960 », « 1955 to 1958 », « 12/03/1965 ».
 *
 * <p>Rend une ou plusieurs dates ISO, partielles si besoin ({@code 1965}, {@code 1965-03}, {@code 1965-03-12}).
 * Une valeur illisible est rendue telle quelle : la conversion commune la garde alors en note.
 */
public final class Dates {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12),
            Map.entry("janv", 1), Map.entry("fév", 2), Map.entry("fev", 2), Map.entry("mars", 3), Map.entry("avr", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juil", 7), Map.entry("aoû", 8), Map.entry("aou", 8),
            Map.entry("déc", 12));

    private static final Pattern DAY_MONTH_YEAR = Pattern.compile("(\\d{1,2})(?:st|nd|rd|th)?[ .\\-/]+([A-Za-zéû]{3,9})\\.?[ .\\-/,]+(\\d{4})");
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile("([A-Za-zéû]{3,9})\\.? (\\d{1,2}),? (\\d{4})");
    private static final Pattern MONTH_YEAR = Pattern.compile("([A-Za-zéû]{3,9})\\.?[ \\-/]+(\\d{4})");
    private static final Pattern YEAR = Pattern.compile("(1[89]\\d{2}|20\\d{2})");

    private Dates() {
    }

    /** Toutes les dates trouvées dans le texte (une plage « 1955 to 1958 » donne deux dates). */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String text = raw.trim();
        for (String part : text.split("(?i)\\s+(?:to|-|–|and|or|ou|et)\\s+|;|,\\s*(?=\\d{1,2} [A-Za-z])")) {
            String one = one(part.trim());
            if (one != null && !out.contains(one)) {
                out.add(one);
            }
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    /** Une seule date ; {@code null} si illisible. */
    public static String one(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replaceFirst("(?i)^(circa|ca\\.?|approx(imately)?\\.?|about|vers|environ|c\\.)\\s*", "");
        String iso = DateNormalizer.toIsoDate(text);
        if (iso != null) {
            return iso;
        }
        Matcher m = DAY_MONTH_YEAR.matcher(text);
        if (m.find() && month(m.group(2)) != null) {
            return String.format("%s-%02d-%02d", m.group(3), month(m.group(2)), Integer.parseInt(m.group(1)));
        }
        m = MONTH_DAY_YEAR.matcher(text);
        if (m.find() && month(m.group(1)) != null) {
            return String.format("%s-%02d-%02d", m.group(3), month(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = MONTH_YEAR.matcher(text);
        if (m.find() && month(m.group(1)) != null) {
            return String.format("%s-%02d", m.group(2), month(m.group(1)));
        }
        m = YEAR.matcher(text);
        if (m.matches() || (m.find() && text.length() <= 12)) {
            return m.group(1);
        }
        return null;
    }

    private static Integer month(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        Integer m = MONTHS.get(w);
        if (m == null && w.length() >= 3) {
            m = MONTHS.get(w.substring(0, 3));
        }
        if (m == null && w.length() >= 4) {
            m = MONTHS.get(w.substring(0, 4));
        }
        return m;
    }
}
