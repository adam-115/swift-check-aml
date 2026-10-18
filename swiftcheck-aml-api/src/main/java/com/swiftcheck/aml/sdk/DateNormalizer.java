package com.swiftcheck.aml.sdk;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalise une date au format préfixe ISO 8601 accepté par FollowTheMoney :
 * {@code yyyy}, {@code yyyy-MM} ou {@code yyyy-MM-dd}. Les valeurs non
 * interprétables sont rejetées ({@code null}).
 */
public final class DateNormalizer {

    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 2100;

    private static final Pattern ISO = Pattern
            .compile("^(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?(?:[T ].*|Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern YMD_SLASH = Pattern.compile("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})$");
    private static final Pattern DMY = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})$");
    private static final Pattern MY = Pattern.compile("^(\\d{1,2})[/.-](\\d{4})$");

    private DateNormalizer() {
    }

    /**
     * @return la date normalisée, ou {@code null} si la valeur n'est pas une date
     *         valide.
     */
    public static String toIsoDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = ISO.matcher(s);
        if (m.matches()) {
            return build(m.group(1), m.group(2), m.group(3));
        }
        m = YMD_SLASH.matcher(s);
        if (m.matches()) {
            return build(m.group(1), m.group(2), m.group(3));
        }
        m = DMY.matcher(s);
        if (m.matches()) {
            return build(m.group(3), m.group(2), m.group(1));
        }
        m = MY.matcher(s);
        if (m.matches()) {
            return build(m.group(2), m.group(1), null);
        }
        return null;
    }

    /**
     * Comme {@link #toIsoDate}, mais une date présente et illisible est une <b>erreur</b>. À utiliser pour les
     * dates qui changent le sens d'une fiche : une date de fin de mandat perdue transformerait un ancien
     * mandat en mandat « en cours ».
     *
     * @param where emplacement de la valeur, pour le message d'erreur (ex. {@code ligne 12, colonne END_DATE})
     * @return la date ISO, ou {@code null} si la valeur est vide
     * @throws IllegalStateException si la valeur n'est pas une date lisible
     */
    public static String toIsoDateOrFail(String raw, String where) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String iso = toIsoDate(raw);
        if (iso == null) {
            throw new IllegalStateException("Date illisible « " + raw + " » (" + where + ")");
        }
        return iso;
    }

    private static String build(String y, String mo, String d) {
        try {
            int year = Integer.parseInt(y);
            if (year < MIN_YEAR || year > MAX_YEAR) {
                return null;
            }
            if (mo == null) {
                return String.format("%04d", year);
            }
            int month = Integer.parseInt(mo);
            if (d == null) {
                return YearMonth.of(year, month).toString();
            }
            return LocalDate.of(year, month, Integer.parseInt(d)).toString();
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }
}
