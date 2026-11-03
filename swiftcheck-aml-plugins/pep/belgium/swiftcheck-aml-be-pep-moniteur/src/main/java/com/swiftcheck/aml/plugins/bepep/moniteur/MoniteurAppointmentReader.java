package com.swiftcheck.aml.plugins.bepep.moniteur;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Lit les nominations, renouvellements, démissions et mises à la retraite dans le texte HTML d'un acte publié au
 * Moniteur belge ({@code ejustice.just.fgov.be}).
 *
 * <p>Tournures officielles prises en charge :
 * <ul>
 *   <li>Nomination ou désignation individuelle :
 *       {@code Par arrêté royal du ..., M. / Mme / Monsieur / Madame X[, qualité,] est nommé(e) / désigné(e) ...} ;</li>
 *   <li>Renouvellement ou prolongation :
 *       {@code la désignation / nomination de M. / Mme X[, qualité,] aux fonctions de / au mandat de / en tant que ... est renouvelée / prolongée ...} ;</li>
 *   <li>Maintien / reconduction :
 *       {@code M. / Mme X, fonction, est désigné(e) / autorisé(e) pour continuer à exercer ses fonctions de ...} ;</li>
 *   <li>Démission ou retraite :
 *       {@code démission honorable de ses fonctions de ... est accordée à M. / Mme X},
 *       {@code est acceptée la démission [honorable] de M. / Mme X, de ses fonctions de ...},
 *       {@code M. / Mme X, fonction, est admis(e) à la retraite} ;</li>
 *   <li>Nomination collective par liste à tirets :
 *       {@code Sont nommés membres du Conseil d'administration de ... : - Mme X ; - M. Y.}</li>
 * </ul>
 */
final class MoniteurAppointmentReader {

    enum Kind { NOMINATION, RENEWAL, RESIGNATION }

    record Appointment(
            Kind kind,
            String civility,
            String fullName,
            String function,
            LocalDate effective,
            LocalDate until) {
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("février", 2), Map.entry("fevrier", 2),
            Map.entry("mars", 3), Map.entry("avril", 4), Map.entry("mai", 5),
            Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("août", 8), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11),
            Map.entry("décembre", 12), Map.entry("decembre", 12));

    private static final Map<String, Integer> NUMBERS = Map.ofEntries(
            Map.entry("un", 1), Map.entry("une", 1), Map.entry("deux", 2), Map.entry("trois", 3),
            Map.entry("quatre", 4), Map.entry("cinq", 5), Map.entry("six", 6), Map.entry("sept", 7),
            Map.entry("huit", 8), Map.entry("neuf", 9), Map.entry("dix", 10));

    private static final String DATE = "(\\d{1,2})(?:er|re)?\\s+(janvier|f[ée]vrier|mars|avril|mai|juin|juillet"
            + "|ao[ûu]t|septembre|octobre|novembre|d[ée]cembre)\\s+(\\d{4})";

    private static final String CIVILITY = "(Madame|Monsieur|Mme|M\\.)";

    private static final String PERSON_NAME = "([A-ZÀ-ÖØ-Ý][A-Za-zÀ-ÖØ-öø-ÿ'’\\s.-]{1,60}?)";

    private static final String FUNCTION_END = "(?=,?\\s*(?:avec effet|à partir du|à compter du|prenant cours le"
            + "|à la date du|pour un terme|pour une période|pour une durée|jusqu'au|jusqu'à ce|en remplacement de"
            + "|à sa demande|à leur demande"
            + "|\\.\\s|\\.$|;|$))";

    /**
     * « M. / Mme / Monsieur / Madame X[, qualité,] est nommé(e) / désigné(e) [à titre définitif] [pour exercer, à partir du ...,]
     * [les fonctions de / aux fonctions de / à la fonction de / au mandat de / en qualité de / comme] <fonction> ».
     */
    private static final Pattern NOMINATION = Pattern.compile(
            CIVILITY + "\\s+" + PERSON_NAME
                    + "(?:,\\s*[^,]+?,)?\\s+(?:est|a été)\\s+(?:nommée?|désignée?)"
                    + "(?:\\s+à titre définitif)?"
                    + "(?:\\s+pour exercer(?:,\\s*à partir du\\s+" + DATE + ",)?)?"
                    + "\\s+(?:les fonctions de\\s+|aux fonctions de\\s+|à la fonction de\\s+|au mandat(?: adjoint)? de\\s+"
                    + "|dans le grade de\\s+|en qualité de\\s+|en tant que\\s+|comme\\s+)?"
                    + "(.+?)" + FUNCTION_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « Conformément à l'article ..., l'intéressé est nommé <fonction> » (suite immédiate d'une phrase de nomination).
     */
    private static final Pattern SECONDARY_NOMINATION = Pattern.compile(
            "l'intéressée?\\s+est\\s+(?:nommée?|désignée?)\\s+(.+?)" + FUNCTION_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « la désignation / nomination / le mandat de M. / Mme X[, qualité,] [aux fonctions de / à la fonction de / au mandat de /
     * en tant que / comme <fonction>,] est renouvelé(e) / prolongé(e) ».
     */
    private static final Pattern RENEWAL = Pattern.compile(
            "(?:la\\s+)?(?:désignation|nomination|mandat)\\s+de\\s+" + CIVILITY + "\\s+" + PERSON_NAME
                    + "(?:,\\s*(.+?))?,?\\s+(?:est|a été)\\s+(?:renouvelée?|prolongée?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « M. / Mme X, <fonction>, est désigné(e) / autorisé(e) pour continuer à exercer / à continuer d'exercer ses fonctions ... ».
     */
    private static final Pattern CONTINUED = Pattern.compile(
            CIVILITY + "\\s+" + PERSON_NAME + ",\\s*([^,]+?),\\s+(?:est|a été)\\s+(?:désignée?|autorisée?)\\s+"
                    + "(?:pour continuer à exercer|à continuer d'exercer)\\s+ses fonctions(?:\\s+de\\s+([^,]+?))?"
                    + "(?=,|\\s+au-delà|\\s+après|\\s+pour\\s+|\\.|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « démission [honorable] de ses fonctions de <fonction> est accordée[, à sa demande,] à M. / Mme X ».
     */
    private static final Pattern RESIGNATION_ACCORDED = Pattern.compile(
            "démission(?:\\s+honorable)?\\s+de\\s+(?:sa|ses)\\s+fonctions?\\s+de\\s+(.+?)\\s+(?:est|a été)\\s+accordée"
                    + "(?:,\\s*[^,]+,)?\\s+à\\s+" + CIVILITY + "\\s+" + PERSON_NAME + "(?=\\s*\\.|,|;|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « [entrant en vigueur le ...,] est acceptée[, à sa demande,] la démission [honorable] de M. / Mme X, de ses fonctions de <fonction> ».
     */
    private static final Pattern RESIGNATION_ACCEPTED = Pattern.compile(
            "est\\s+acceptée(?:,\\s*[^,]+,)?\\s+la\\s+démission(?:\\s+honorable)?\\s+de\\s+"
                    + CIVILITY + "\\s+" + PERSON_NAME + ",?\\s+de\\s+(?:sa|ses)\\s+fonctions?\\s+de\\s+(.+?)"
                    + FUNCTION_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * « [entrant en vigueur le ...,] M. / Mme X, <fonction>, est admis(e) à la retraite ».
     */
    private static final Pattern RETIREMENT = Pattern.compile(
            CIVILITY + "\\s+" + PERSON_NAME + ",\\s*(.+?),\\s+(?:est|a été)\\s+admise?\\s+à\\s+la\\s+retraite"
                    + "(?:\\s+à\\s+(?:sa|leur)\\s+demande)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Nomination collective : « Sont nommés/désignés <fonction> : » suivie de lignes « - M. / Mme X ; ».
     */
    private static final Pattern COLLECTIVE_HEADER = Pattern.compile(
            "sont\\s+(?:nommés?|désignés?)\\s+(.+?)\\s*:",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern COLLECTIVE_ITEM = Pattern.compile(
            "^[-•*]\\s*" + CIVILITY + "\\s+" + PERSON_NAME + "\\s*[;.,]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DECREE_DATE_HEADER = Pattern.compile(
            "(?:par\\s+(?:arrêtés?\\s+(?:royal|royaux|ministériel|du gouvernement[^,\\n]*)|ordonnance)\\s+du|bruxelles,\\s+le|namur,\\s+le)\\s+"
                    + DATE,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EFFECTIVE_DATE = Pattern.compile(
            "(?:avec effet au|à partir du|à compter du|prenant cours le|entrant en vigueur le|à la date du"
                    + "|entre en vigueur le)\\s+" + DATE,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern UNTIL_DATE = Pattern.compile(
            "jusqu'au\\s+" + DATE,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern DURATION = Pattern.compile(
            "pour\\s+un(?:e)?\\s+(?:terme|période|durée)(?:\\s+renouvelable)?\\s+d(?:e\\s+|')"
                    + "(\\d+|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix)\\s+ans?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private MoniteurAppointmentReader() {
    }

    /**
     * Extrait le texte utile de la page HTML d'un acte du Moniteur belge ({@code <main class="... article-text">}).
     */
    static String extractHtmlText(Path htmlFile) throws Exception {
        byte[] bytes = Files.readAllBytes(htmlFile);
        String rawUtf8 = new String(bytes, StandardCharsets.UTF_8);
        Charset charset = rawUtf8.contains("charset=iso-8859-1") || rawUtf8.contains("charset=\"iso-8859-1\"")
                || rawUtf8.contains("\uFFFD")
                ? Charset.forName("windows-1252")
                : StandardCharsets.UTF_8;
        Document doc = Jsoup.parse(new String(bytes, charset));
        Element main = doc.selectFirst("main.article-text, div.article-text, body");
        if (main == null) {
            return "";
        }
        // Remplacer br, p, li, tr par des sauts de ligne explicites dans le DOM avant extraction de texte
        for (Element sup : main.select("sup")) {
            sup.replaceWith(new org.jsoup.nodes.TextNode(sup.text()));
        }
        for (Element el : main.select("br")) {
            el.replaceWith(new org.jsoup.nodes.TextNode("\n"));
        }
        for (Element el : main.select("p, div, li, tr")) {
            el.prepend("\n");
            el.append("\n");
        }
        String full = main.wholeText();
        StringBuilder sb = new StringBuilder();
        for (String raw : full.split("\n")) {
            String line = normalize(raw);
            if (!line.isEmpty()) {
                // Découper sur les puces belges fréquentes : « - entrant en vigueur », « - la désignation »
                line = line.replaceAll("\\s+-\\s+(?=(?:entrant|la désignation|la nomination|le mandat|M\\.|Mme|Monsieur|Madame|est acceptée))", "\n- ");
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** Lit tous les événements de nomination, renouvellement, démission ou retraite d'un texte d'acte. */
    static List<Appointment> read(String text) {
        List<Appointment> found = new ArrayList<>();
        String normalized = normalize(text);
        LocalDate globalEffective = date(EFFECTIVE_DATE, normalized);

        String collectiveFunction = null;
        String lastCivility = null;
        String lastName = null;

        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("Vu ") || line.startsWith("Considérant ")) {
                continue; // préambule juridique : cite les arrêtés antérieurs
            }

            // 1. En-tête de nomination collective (« Sont nommés membres du Conseil d'administration de ... : »)
            Matcher collHeader = COLLECTIVE_HEADER.matcher(line);
            if (collHeader.find()) {
                collectiveFunction = cleanFunction(collHeader.group(1));
                continue;
            }
            if (collectiveFunction != null) {
                Matcher item = COLLECTIVE_ITEM.matcher(line);
                if (item.find()) {
                    found.add(new Appointment(
                            Kind.NOMINATION,
                            item.group(1),
                            cleanPersonName(item.group(2)),
                            collectiveFunction,
                            globalEffective,
                            null));
                    continue;
                } else if (!line.startsWith("-")) {
                    collectiveFunction = null;
                }
            }

            LocalDate lineEffective = date(EFFECTIVE_DATE, line);
            LocalDate effective = lineEffective != null ? lineEffective : globalEffective;

            // 2. Retraite (« entrant en vigueur le ..., M. Vandermeersch D., avocat général près la Cour de cassation, est admis à la retraite »)
            Matcher ret = RETIREMENT.matcher(line);
            while (ret.find()) {
                found.add(new Appointment(
                        Kind.RESIGNATION,
                        ret.group(1),
                        cleanPersonName(ret.group(2)),
                        cleanFunction(ret.group(3)),
                        effective,
                        null));
            }

            // 3. Démission acceptée (« est acceptée la démission de Mme X, de ses fonctions de ... »)
            Matcher resAcc = RESIGNATION_ACCEPTED.matcher(line);
            while (resAcc.find()) {
                found.add(new Appointment(
                        Kind.RESIGNATION,
                        resAcc.group(1),
                        cleanPersonName(resAcc.group(2)),
                        cleanFunction(resAcc.group(3)),
                        effective,
                        null));
            }

            // 4. Démission accordée (« démission honorable de ses fonctions de ... est accordée à M. X »)
            Matcher res = RESIGNATION_ACCORDED.matcher(line);
            while (res.find()) {
                found.add(new Appointment(
                        Kind.RESIGNATION,
                        res.group(2),
                        cleanPersonName(res.group(3)),
                        cleanFunction(res.group(1)),
                        effective,
                        null));
            }

            // 5. Renouvellement / prolongation (« la désignation de M. X, ..., au mandat de ..., est renouvelée »)
            Matcher ren = RENEWAL.matcher(line);
            while (ren.find()) {
                String clause = line.substring(ren.end());
                LocalDate eff = date(EFFECTIVE_DATE, clause) != null ? date(EFFECTIVE_DATE, clause) : effective;
                String fn = extractRenewedFunction(ren.group(3), line);
                if (fn != null && !fn.isBlank()) {
                    found.add(new Appointment(
                            Kind.RENEWAL,
                            ren.group(1),
                            cleanPersonName(ren.group(2)),
                            cleanFunction(fn),
                            eff,
                            until(clause, eff)));
                }
            }

            // 6. Maintien en fonction (« M. Staudt E., magistrat suppléant près la cour d'appel de Liège, est désigné pour continuer à exercer... »)
            Matcher cont = CONTINUED.matcher(line);
            boolean matchedContinued = false;
            while (cont.find()) {
                matchedContinued = true;
                String clause = line.substring(cont.end());
                LocalDate eff = date(EFFECTIVE_DATE, clause) != null ? date(EFFECTIVE_DATE, clause) : effective;
                String fn = cont.group(4) != null && !cont.group(4).isBlank() ? cont.group(4) : cont.group(3);
                found.add(new Appointment(
                        Kind.RENEWAL,
                        cont.group(1),
                        cleanPersonName(cont.group(2)),
                        cleanFunction(fn),
                        eff,
                        until(clause, eff)));
            }
            if (matchedContinued) {
                continue;
            }

            // 7. Nomination ou désignation individuelle
            Matcher nom = NOMINATION.matcher(line);
            while (nom.find()) {
                String civ = nom.group(1);
                String person = cleanPersonName(nom.group(2));
                LocalDate inlineEff = nom.group(3) != null
                        ? LocalDate.of(Integer.parseInt(nom.group(5)), MONTHS.get(nom.group(4).toLowerCase(Locale.FRENCH)),
                                Integer.parseInt(nom.group(3)))
                        : null;
                String fn = cleanFunction(nom.group(6));
                String clause = line.substring(nom.end());
                LocalDate eff = inlineEff != null ? inlineEff
                        : (date(EFFECTIVE_DATE, clause) != null ? date(EFFECTIVE_DATE, clause) : effective);
                if (!fn.isEmpty() && !fn.startsWith("pour continuer")) {
                    found.add(new Appointment(Kind.NOMINATION, civ, person, fn, eff, until(clause, eff)));
                    lastCivility = civ;
                    lastName = person;
                }
            }

            // 8. Nomination secondaire (« l'intéressé est nommé substitut du procureur général près la cour d'appel de Gand »)
            if (lastName != null) {
                Matcher sec = SECONDARY_NOMINATION.matcher(line);
                while (sec.find()) {
                    String fn = cleanFunction(sec.group(1));
                    if (!fn.isEmpty()) {
                        found.add(new Appointment(Kind.NOMINATION, lastCivility, lastName, fn, effective, null));
                    }
                }
            }
        }
        return found;
    }

    /** Date du premier arrêté ou ordonnance mentionné dans le texte, ou {@code null}. */
    static LocalDate decreeDate(String text) {
        Matcher m = DECREE_DATE_HEADER.matcher(normalize(text));
        return m.find() ? toDate(m) : null;
    }

    private static String extractRenewedFunction(String middleClause, String fullLine) {
        String source = middleClause != null ? middleClause : fullLine;
        for (String marker : List.of("aux fonctions de ", "à la fonction de ", "au mandat adjoint de ",
                "au mandat de ", "en tant que ", "en qualité de ", "comme ")) {
            int idx = source.toLowerCase(Locale.FRENCH).lastIndexOf(marker);
            if (idx >= 0) {
                return source.substring(idx + marker.length()).trim();
            }
        }
        return middleClause;
    }

    private static String cleanPersonName(String raw) {
        return raw.replaceAll("\\s+", " ").replaceAll("[,;.]+$", "").trim();
    }

    private static String cleanFunction(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("^(?:les fonctions de|aux fonctions de|à la fonction de|au mandat(?: adjoint)? de"
                + "|dans le grade de|en qualité de|en tant que|comme)\\s+", "")
                .replaceAll("[,;.]+$", "")
                .trim();
        return s;
    }

    private static LocalDate until(String clause, LocalDate effective) {
        LocalDate u = date(UNTIL_DATE, clause);
        if (u != null) {
            return u;
        }
        Matcher d = DURATION.matcher(clause);
        if (d.find() && effective != null) {
            String val = d.group(1).toLowerCase(Locale.FRENCH);
            int years = NUMBERS.getOrDefault(val, 0);
            if (years == 0) {
                try {
                    years = Integer.parseInt(val);
                } catch (NumberFormatException ignored) {
                }
            }
            if (years > 0) {
                return effective.plusYears(years).minusDays(1);
            }
        }
        return null;
    }

    private static LocalDate date(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? toDate(m) : null;
    }

    private static LocalDate toDate(Matcher m) {
        int n = m.groupCount();
        int day = Integer.parseInt(m.group(n - 2));
        Integer month = MONTHS.get(m.group(n - 1).toLowerCase(Locale.FRENCH));
        int year = Integer.parseInt(m.group(n));
        return LocalDate.of(year, month != null ? month : 1, day);
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('’', '\'')
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("(\\d)\\s+er\\b", "$1er")
                .replaceAll("(\\d)\\s+re\\b", "$1re")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }
}

