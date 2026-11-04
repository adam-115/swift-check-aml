package com.swiftcheck.aml.plugins.espep.boe;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture des titres de la section II.A du BOE. Les décrets de nomination suivent une formule stable :
 * « Real Decreto 870/2025, de 30 de septiembre, por el que se nombra Director General de X a don Y. » et
 * « …por el que se dispone el cese de don Y como Director General de X. ». Un titre qui ne suit pas la formule (par
 * exemple « se nombran Magistrados… ») ne donne aucun événement.
 */
final class BoeTitles {

    enum Kind {
        /** Nomination, désignation ou promotion : début de fonction. */
        APPOINT,
        /** Cessation : fin de fonction. */
        END
    }

    /** Un événement lu dans un titre ; {@code date} est la date du décret, à défaut celle de publication. */
    record Event(Kind kind, String name, String cargo, LocalDate date, String id, String title, String department) {
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abril", 4),
            Map.entry("mayo", 5), Map.entry("junio", 6), Map.entry("julio", 7), Map.entry("agosto", 8),
            Map.entry("septiembre", 9), Map.entry("setiembre", 9), Map.entry("octubre", 10),
            Map.entry("noviembre", 11), Map.entry("diciembre", 12));

    private static final Pattern DOC_DATE = Pattern.compile(
            "(?iu),\\s+de\\s+(\\d{1,2})\\s+de\\s+([a-záéíóú]+)(?:\\s+de\\s+(\\d{4}))?,");
    private static final Pattern BODY = Pattern.compile("(?iu)\\bpor (?:el|la) que se\\s+(.+)$");

    private static final String HONORIFIC = "(?:don|doña|D\\.|Dña\\.)";

    private static final Pattern END = Pattern.compile(
            "(?iu)^(?:dispone el cese de|declara el cese de|declara cesado a|cesa a|cesa de)\\s+" + HONORIFIC
                    + "\\s+(.+?)\\s+(?:como|en el cargo de|en su cargo de)\\s+(.+?)\\.?$");

    /** Nomination : fonction, puis « a / al / a la » (et un grade éventuel), puis « don / doña » et le nom. */
    private static final Pattern APPOINT = Pattern.compile(
            "(?u)^(?:nombra|designa)\\s+(?:a\\s+)?(.+)\\s+(?:a|al|a la)\\s+(?:(?:[A-ZÁÉÍÓÚÑ][\\wáéíóúñ.\\-]*|de|del|la|el)\\s+){0,5}?"
                    + HONORIFIC + "\\s+(.+?)\\.?$");

    private static final Pattern PROMOTE = Pattern.compile(
            "(?u)^(?:promueve|asciende) al empleo de\\s+(.+)\\s+(?:a|al|a la)\\s+(?:(?:[A-ZÁÉÍÓÚÑ][\\wáéíóúñ.\\-]*|de|del|la|el)\\s+){0,5}?"
                    + HONORIFIC + "\\s+(.+?)\\.?$");

    /** « …del Cuerpo General del Ejército de Tierra » : le corps ne fait pas partie du grade. */
    private static final Pattern RANK_TAIL = Pattern.compile("(?iu)\\s+(?:del Cuerpo|de la Escala|del Ejército|de la Armada|del Aire)\\b.*$");

    private BoeTitles() {
    }

    /** Événements d'un titre ; une liste vide si le titre ne suit pas la formule. */
    static List<Event> read(String id, String title, String department, LocalDate publication) {
        List<Event> events = new ArrayList<>();
        if (title == null) {
            return events;
        }
        Matcher body = BODY.matcher(title);
        if (!body.find()) {
            return events;
        }
        String action = body.group(1).trim();
        LocalDate date = documentDate(title, publication);

        Matcher end = END.matcher(action);
        if (end.matches()) {
            events.add(new Event(Kind.END, clean(end.group(1)), clean(end.group(2)), date, id, title, department));
            return events;
        }
        Matcher promote = PROMOTE.matcher(action);
        if (promote.matches()) {
            String rank = RANK_TAIL.matcher(clean(promote.group(1))).replaceFirst("");
            events.add(new Event(Kind.APPOINT, clean(promote.group(2)), rank, date, id, title, department));
            return events;
        }
        Matcher appoint = APPOINT.matcher(action);
        if (appoint.matches()) {
            events.add(new Event(Kind.APPOINT, clean(appoint.group(2)), clean(appoint.group(1)), date, id, title,
                    department));
        }
        return events;
    }

    /** Date du texte (« de 30 de septiembre » [de 2025]) ; à défaut, la date de publication. */
    static LocalDate documentDate(String title, LocalDate publication) {
        Matcher m = DOC_DATE.matcher(title);
        if (m.find()) {
            Integer month = MONTHS.get(m.group(2).toLowerCase(Locale.ROOT));
            if (month != null) {
                int year = m.group(3) != null ? Integer.parseInt(m.group(3)) : publication.getYear();
                try {
                    LocalDate date = LocalDate.of(year, month, Integer.parseInt(m.group(1)));
                    // « de 30 de diciembre » publié en janvier sans année : le texte date de l'année précédente
                    return m.group(3) == null && date.isAfter(publication) ? date.minusYears(1) : date;
                } catch (java.time.DateTimeException e) {
                    return publication;
                }
            }
        }
        return publication;
    }

    /** Clé de comparaison : sans accents, sans majuscules, sans genre (« Directora » = « Director »). */
    static String key(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\b(director|embajador|gobernador|subgobernador)a\\b", "$1")
                .replaceAll("\\b(president|vicepresident)(?:a|e)\\b", "$1e")
                .replaceAll("\\b(ministr|secretari|subsecretari|delegad|consejer|magistrad)(?:a|o)\\b", "$1o")
                .replaceAll("\\bfiscal jefa\\b", "fiscal jefe");
        return s.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").replaceAll("[\\s.,;]+$", "").trim();
    }
}
