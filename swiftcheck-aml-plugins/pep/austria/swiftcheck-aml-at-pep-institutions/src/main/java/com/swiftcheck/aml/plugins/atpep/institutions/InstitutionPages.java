package com.swiftcheck.aml.plugins.atpep.institutions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracteurs des pages officielles des institutions autrichiennes. Un extracteur par site : aucun nom n'est embarqué
 * dans le plugin, les noms sont toujours lus dans la page officielle téléchargée à l'exécution.
 */
final class InstitutionPages {

    private InstitutionPages() {
    }

    /** Un titulaire lu sur une page ; {@code role} est la fonction écrite sur la page, les dates sont ISO ou {@code null}. */
    record Member(String name, String firstName, String lastName, String role, String start, String end) {
    }

    /** Extracteurs par clé d'institution ; le paramètre est le HTML de la page. */
    static final Map<String, Function<String, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    static {
        EXTRACTORS.put("vfgh", InstitutionPages::vfgh);
        EXTRACTORS.put("vfgh_substitutes", InstitutionPages::vfgh);
        EXTRACTORS.put("ogh", InstitutionPages::ogh);
        EXTRACTORS.put("vwgh", InstitutionPages::vwgh);
        EXTRACTORS.put("oenb", InstitutionPages::oenb);
        EXTRACTORS.put("rechnungshof", InstitutionPages::rechnungshof);
        register();
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // ------------------------------------------------------------------ VfGH

    private static final Pattern VFGH_ROLE = Pattern.compile(
            "^(Präsident(?:in)?|Vizepräsident(?:in)?|Verfassungsrichter(?:in)?|Ersatzmitglied)$");
    private static final Pattern CAPS_NAME = Pattern.compile(
            "^(\\p{Lu}[\\p{L}'’-]*(?: \\p{Lu}[\\p{L}'’-]*)*) ([\\p{Lu}][\\p{Lu}'’-]+(?: [\\p{Lu}][\\p{Lu}'’-]+)*)$");

    /**
     * Pages « Mitglieder » et « Ersatzmitglieder » du VfGH : après le titre « Inhalt », une ligne de fonction, puis le
     * nom « Prénom NOM ».
     * <pre>
     * Präsident | Christoph GRABENWARTER | Vizepräsidentin | Verena MADNER | Verfassungsrichter | Johannes SCHNIZER | …
     * </pre>
     */
    static List<Member> vfgh(String html) {
        List<String> lines = PageText.lines(html);
        int from = lines.indexOf("Inhalt");
        List<Member> out = new ArrayList<>();
        for (int i = Math.max(from, 0); i + 1 < lines.size(); i++) {
            if (lines.get(i).startsWith("Fußleiste")) {
                break;
            }
            Matcher role = VFGH_ROLE.matcher(lines.get(i));
            Matcher name = CAPS_NAME.matcher(lines.get(i + 1));
            if (role.matches() && name.matches()) {
                out.add(person(name.group(1) + " " + name.group(2), role.group(1)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- OGH

    private static final Pattern OGH_ROLE = Pattern.compile("^(.+ des OGH)$");

    /**
     * Page « Richterinnen und Richter » de l'OGH : « 1. | Univ.-Prof. Dr. Georg KODEK, LL.M. | Präsident des OGH ».
     */
    static List<Member> ogh(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 2 < lines.size(); i++) {
            if (!lines.get(i).matches("\\d+\\.")) {
                continue;
            }
            int n = i + 1;
            while (n < lines.size() && lines.get(n).startsWith("–")) {
                n++;
            }
            if (n + 1 >= lines.size()) {
                break;
            }
            String name = lines.get(n);
            int r = n + 1;
            while (r < lines.size() && lines.get(r).startsWith("–")) {
                r++;
            }
            if (r < lines.size() && OGH_ROLE.matcher(lines.get(r)).matches()) {
                Member m = fromCaps(name, lines.get(r));
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ VwGH

    private static final Pattern VWGH_BLOCK = Pattern.compile("(?is)<h2[^>]*>(.*?)</h2>\\s*<p>(.*?)</p>");

    /**
     * Page « Richterinnen und Richter » du VwGH : un titre « Hofräte des VwGH » puis la liste « NOM Dr. Prénom » séparée
     * par des {@code <br/>}.
     */
    static List<Member> vwgh(String html) {
        List<Member> out = new ArrayList<>();
        Matcher block = VWGH_BLOCK.matcher(html);
        while (block.find()) {
            String heading = PageText.lines(block.group(1)).stream().reduce((a, b) -> a + " " + b).orElse("");
            if (!heading.contains("des VwGH")) {
                continue;
            }
            String role = heading.replaceAll("\\s+", " ").trim();
            for (String entry : block.group(2).split("(?i)<br\\s*/?>")) {
                String text = String.join(" ", PageText.lines(entry)).trim();
                if (text.isEmpty()) {
                    continue;
                }
                Member m = fromSurnameFirst(text, role);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ OeNB

    private static final Pattern OENB_ROLE = Pattern.compile(
            "^((?:Vize-)?Gouverneur(?:in)?|Direktor(?:in)?) der Oesterreichischen Nationalbank$");
    private static final Pattern PERIOD = Pattern.compile(
            "^Funktionsperiode:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s+bis\\s+(\\d{2}\\.\\d{2}\\.\\d{4})$");

    /**
     * Page « Direktorium » de l'OeNB : « Univ.-Prof. Mag. Dr. Martin Kocher | Gouverneur der Oesterreichischen
     * Nationalbank | Funktionsperiode: 01.09.2025 bis 31.08.2031 ». La fin de la période n'est reprise que si elle est
     * passée.
     */
    static List<Member> oenb(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i + 2 < lines.size(); i++) {
            Matcher role = OENB_ROLE.matcher(lines.get(i + 1));
            Matcher period = PERIOD.matcher(lines.get(i + 2));
            if (!role.matches() || !period.matches()) {
                continue;
            }
            String name = stripTitles(lines.get(i));
            String end = iso(period.group(2));
            out.add(simple(name, role.group(1) + " der OeNB", iso(period.group(1)),
                    end != null && LocalDate.parse(end).isBefore(today) ? end : null));
        }
        return out;
    }

    // ----------------------------------------------------------- Rechnungshof

    private static final Pattern RH_PRESIDENT = Pattern.compile(
            "headed by President (\\p{Lu}[\\p{L}'’ .-]+?)\\.");
    private static final Pattern RH_SINCE = Pattern.compile("Since (\\d{1,2}) (\\p{L}+) (\\d{4})");

    /** Page « The President » du Rechnungshof : « headed by President Margit Kraker. … Since 1 July 2016 ». */
    static List<Member> rechnungshof(String html) {
        String text = String.join(" ", PageText.lines(html));
        Matcher president = RH_PRESIDENT.matcher(text);
        if (!president.find()) {
            return List.of();
        }
        String start = null;
        Matcher since = RH_SINCE.matcher(text);
        if (since.find()) {
            try {
                start = LocalDate.parse(since.group(1) + " " + since.group(2) + " " + since.group(3),
                        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)).toString();
            } catch (DateTimeParseException e) {
                start = null;
            }
        }
        return List.of(simple(president.group(1).trim(), "Präsident(in) des Rechnungshofs", start, null));
    }


    // ---------------------------------------------------- gouvernements des Länder

    private static final String LAND_ROLE = "(LH-Stellvertreterin|LH-Stellvertreter|Landeshauptmann-Stellvertreterin|Landeshauptmann-Stellvertreter|Landeshauptfrau-Stellvertreterin"
            + "|Landeshauptfrau-Stellvertreter|Landeshauptmann|Landeshauptfrau|Landesrätin|Landesrat|Landesstatthalter(?:in)?"
            + "|Bürgermeister(?:in)?|Vizebürgermeister(?:in)?)";
    private static final Pattern LAND_ROLE_ONLY = Pattern.compile("^" + LAND_ROLE + "$");
    private static final Pattern LAND_INLINE = Pattern.compile("^" + LAND_ROLE + "\\s+(.+)$");
    private static final Pattern LAND_PARTY = Pattern.compile(LAND_ROLE + "\\s+((?:[\\p{L}.,'’-]+\\s+){1,7}?)\\((?:ÖVP|FPÖ|SPÖ|GRÜNE|GRÜNEN|NEOS|KPÖ)\\)");

    // ------------------------------------------------------------------- ÖBAG

    private static final Pattern HEADING = Pattern.compile("(?is)<h[2-5][^>]*>(.*?)</h[2-5]>");
    private static final Pattern PLAIN_NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’.-]+(?: \\p{Lu}[\\p{L}'’.-]+){1,3}$");

    /** Titres h2 à h5 de la page, en texte. */
    private static List<String> headings(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = HEADING.matcher(html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " "));
        while (m.find()) {
            String t = PageText.lines(m.group(1)).stream().reduce((a, b) -> a + " " + b).orElse("").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Page « Das Team » de l'ÖBAG : après « Unser Management », les noms du directoire jusqu'à la première phrase. */
    static List<Member> oebagBoard(String html) {
        List<String> h = headings(html);
        int from = h.indexOf("Unser Management");
        List<Member> out = new ArrayList<>();
        for (int i = from + 1; from >= 0 && i < h.size(); i++) {
            String t = h.get(i);
            if (!PLAIN_NAME.matcher(t).matches() || t.contains("Aufsicht")) {
                break;
            }
            out.add(simple(t, "Vorstand der ÖBAG", null, null));
        }
        return out;
    }

    /** Page « Unser Aufsichtsrat » de l'ÖBAG : les noms qui suivent « Mitglieder des Aufsichtsrats ». */
    static List<Member> oebagSupervisory(String html) {
        List<String> h = headings(html);
        int from = h.indexOf("Mitglieder des Aufsichtsrats");
        List<Member> out = new ArrayList<>();
        for (int i = from + 1; from >= 0 && i < h.size(); i++) {
            String t = h.get(i);
            if (t.startsWith("Aufsichtsratsfunktionen")) {
                continue;
            }
            if (!PLAIN_NAME.matcher(t).matches()) {
                break;
            }
            out.add(simple(t, "Mitglied des Aufsichtsrats der ÖBAG", null, null));
        }
        return out;
    }

    /**
     * Vienne (Land et commune à la fois) : « Bei der Wahl von Bürgermeister | Michael Ludwig | und der Wahl… » puis, par
     * groupe de compétences, « Stadträtin: | Bettina Emmerling » (le nom est un lien, donc sur la ligne suivante).
     */
    static List<Member> landWien(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("Bei der Wahl von Bürgermeister")) {
                String inline = line.substring("Bei der Wahl von Bürgermeister".length()).trim();
                addLand(out, inline.isEmpty() ? lines.get(i + 1) : inline.split(" und der Wahl")[0], "Bürgermeister und Landeshauptmann", "Wien");
            } else if (line.equals("Stadträtin:") || line.equals("Stadtrat:")) {
                addLand(out, lines.get(i + 1), line.substring(0, line.length() - 1), "Wien");
            } else if (line.startsWith("Stadträtin: ") || line.startsWith("Stadtrat: ")) {
                addLand(out, line.substring(line.indexOf(':') + 1).trim(), line.substring(0, line.indexOf(':')), "Wien");
            }
        }
        return out;
    }

    static void register() {
        EXTRACTORS.put("land_wien", InstitutionPages::landWien);
        EXTRACTORS.put("oebag_board", InstitutionPages::oebagBoard);
        EXTRACTORS.put("oebag_supervisory", InstitutionPages::oebagSupervisory);
        EXTRACTORS.put("land_noe", h -> landNameThenRole(h, "NÖ"));
        EXTRACTORS.put("land_salzburg", h -> landNameThenRole(h, "Salzburg"));
        EXTRACTORS.put("land_tirol", h -> landInline(h, "Tirol"));
        EXTRACTORS.put("land_vorarlberg", h -> landInline(h, "Vorarlberg"));
        EXTRACTORS.put("land_ooe", InstitutionPages::landWithParty);
    }

    /** « Mag. Karoline Edtstadler | Landeshauptfrau | Telefon… » : le nom précède la fonction. */
    static List<Member> landNameThenRole(String html, String land) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Matcher role = LAND_ROLE_ONLY.matcher(lines.get(i));
            if (role.matches()) {
                addLand(out, lines.get(i - 1), role.group(1), land);
            }
        }
        return out;
    }

    /** « Landesrat Mario Gerber » : la fonction précède le nom, sur la même ligne. */
    static List<Member> landInline(String html, String land) {
        List<Member> out = new ArrayList<>();
        for (String line : PageText.lines(html)) {
            Matcher m = LAND_INLINE.matcher(line);
            if (m.matches() && !m.group(2).contains("Zuständig") && m.group(2).length() < 60) {
                addLand(out, m.group(2), m.group(1), land);
            }
        }
        return out;
    }

    /** OÖ : « Landesrat Dr. Christian Dörfel (ÖVP) », titres dans des balises séparées : on lit le texte continu. */
    static List<Member> landWithParty(String html) {
        String text = String.join(" ", PageText.lines(html));
        Matcher m = LAND_PARTY.matcher(text);
        List<Member> out = new ArrayList<>();
        while (m.find()) {
            addLand(out, m.group(2), m.group(1), "OÖ");
        }
        return out;
    }

    private static void addLand(List<Member> out, String rawName, String role, String land) {
        String name = stripTitles(rawName.trim()).replaceAll("\\s*\\(.*$", "").trim();
        String[] tokens = name.split("\\s+");
        if (tokens.length < 2 || tokens.length > 5 || !name.matches("\\p{Lu}[\\p{L}'’ -]+")
                || out.stream().anyMatch(x -> x.name().equals(name))) {
            return;
        }
        out.add(simple(name, role + " (" + land + ")", null, null));
    }
    // ----------------------------------------------------------------- noms

    /** « Dr. Peter Pilz, LL.M. » → « Peter Pilz » : titres (jetons contenant un point, « PD ») retirés, suffixe après la virgule coupé. */
    static String stripTitles(String name) {
        String[] tokens = name.split(",")[0].trim().split("\\s+");
        int from = 0;
        while (from < tokens.length - 1 && isTitle(tokens[from])) {
            from++;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, from, tokens.length));
    }

    private static boolean isTitle(String token) {
        return token.contains(".") || token.equals("PD") || token.equals("KommR") || token.equals("DI") || token.startsWith("(");
    }

    /** « Prénom NOM » avec ou sans titres (OGH) : le nom de famille est écrit en majuscules, il suit le prénom. */
    private static Member fromCaps(String raw, String role) {
        String clean = stripTitles(raw);
        Matcher m = CAPS_NAME.matcher(clean);
        if (!m.matches()) {
            return null;
        }
        return person(m.group(1) + " " + m.group(2), role);
    }

    /** « NOM Dr. Prénom, LL.M. » (VwGH) : nom de famille en majuscules d'abord, puis titres, puis prénom. */
    private static Member fromSurnameFirst(String raw, String role) {
        String[] tokens = raw.split(",")[0].trim().split("\\s+");
        int s = 0;
        while (s < tokens.length && tokens[s].length() > 1 && tokens[s].equals(tokens[s].toUpperCase(Locale.ROOT))
                && !tokens[s].contains(".")) {
            s++;
        }
        if (s == 0 || s >= tokens.length) {
            return null;
        }
        String surname = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, s));
        List<String> first = new ArrayList<>();
        for (int i = s; i < tokens.length; i++) {
            if (!isTitle(tokens[i])) {
                first.add(tokens[i]);
            }
        }
        if (first.isEmpty()) {
            return null;
        }
        return person(String.join(" ", first) + " " + surname, role);
    }

    /** « Christoph GRABENWARTER » → prénom « Christoph », nom « Grabenwarter » (casse normale). */
    private static Member person(String firstAndCapsSurname, String role) {
        String[] tokens = firstAndCapsSurname.split("\\s+");
        List<String> first = new ArrayList<>();
        List<String> last = new ArrayList<>();
        for (String t : tokens) {
            boolean caps = t.length() > 1 && t.equals(t.toUpperCase(Locale.ROOT)) && !t.contains(".");
            (caps ? last : first).add(caps ? title(t) : t);
        }
        if (last.isEmpty()) {
            last.add(first.remove(first.size() - 1));
        }
        String f = String.join(" ", first);
        String l = String.join(" ", last);
        return new Member((f + " " + l).trim(), f.isEmpty() ? null : f, l, role, null, null);
    }

    private static Member simple(String name, String role, String start, String end) {
        String[] tokens = name.split("\\s+");
        String last = tokens.length > 1 ? tokens[tokens.length - 1] : null;
        String first = tokens.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, tokens.length - 1)) : null;
        return new Member(name, first, last, role, start, end);
    }

    /** « LEITL-STAUDINGER » → « Leitl-Staudinger ». */
    private static String title(String caps) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : caps.toCharArray()) {
            sb.append(up ? c : Character.toLowerCase(c));
            up = c == '-' || c == '\'' || c == '’';
        }
        return sb.toString();
    }

    private static String iso(String dmy) {
        try {
            return LocalDate.parse(dmy, DMY).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
