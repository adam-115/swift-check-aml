package com.swiftcheck.aml.plugins.depep.stateenterprises;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un extracteur par page de direction d'entreprise. Chaque page a sa forme (phrase « X est depuis le … », nom suivi de
 * sa fonction, nom suivi de « (CEO) », liste sous un intitulé) ; aucun nom n'est embarqué dans le plugin.
 */
final class EnterprisePages {

    private EnterprisePages() {
    }

    /** Un dirigeant lu sur une page. */
    record Person(String fullName, String title, String cleanName, String role, LocalDate start) {
    }

    /** Clé « entreprise/source » -> extracteur. */
    static final Map<String, Function<List<String>, List<Person>>> EXTRACTORS = new LinkedHashMap<>();

    private static final String[] DE_MONTHS = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September",
            "Oktober", "November", "Dezember"};
    private static final String[] EN_MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};

    static {
        EXTRACTORS.put("deutsche-bahn/vorstand", EnterprisePages::deutscheBahn);
        EXTRACTORS.put("kfw/vorstand", EnterprisePages::kfw);
        EXTRACTORS.put("deutsche-telekom/vorstand", lines -> nameThenRole(lines, "^Vorstand.*", "Vorstand"));
        EXTRACTORS.put("deutsche-telekom/aufsichtsrat", EnterprisePages::telekomSupervisoryBoard);
        EXTRACTORS.put("uniper/vorstand", EnterprisePages::uniper);
        EXTRACTORS.put("dfs/geschaeftsfuehrung", EnterprisePages::dfs);
        EXTRACTORS.put("bundesdruckerei/geschaeftsfuehrung", EnterprisePages::bundesdruckerei);
        // DHL Group : la page n'a pas pu être lue depuis le poste de développement (connexion refusée) : pas d'extracteur.
    }

    // ------------------------------------------------------------------ Deutsche Bahn

    /** « Evelyn Palla ist seit dem 1. Oktober 2025 Vorstandsvorsitzende der Deutschen Bahn AG. » */
    static List<Person> deutscheBahn(List<String> lines) {
        Pattern p = Pattern.compile("^(?<name>.+?) ist seit dem (?<date>\\d{1,2}\\. \\p{L}+ \\d{4}) (?<role>Vorstand[^.]*?) der Deutschen Bahn AG\\.$");
        List<Person> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                add(out, m.group("name"), m.group("role"), date(m.group("date"), DE_MONTHS));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ KfW

    /** « Stefan Wintels (Vorsitzender) » puis « Im KfW-Vorstand ist X u. a. verantwortlich pour … ». */
    static List<Person> kfw(List<String> lines) {
        Pattern member = Pattern.compile("^Im KfW-Vorstand ist (?<name>.+?) u\\. a\\. verantwortlich.*$");
        Pattern chair = Pattern.compile("^(?<name>.+?) \\(Vorsitzende[rn]?\\)$");
        List<Person> out = new ArrayList<>();
        for (String line : lines) {
            Matcher c = chair.matcher(line);
            if (c.matches()) {
                add(out, c.group("name"), "Vorstandsvorsitzender", null);
                continue;
            }
            Matcher m = member.matcher(line);
            if (m.matches()) {
                add(out, m.group("name"), "Mitglied des Vorstands", null);
            }
        }
        // le président est aussi nommé dans une phrase « Im KfW-Vorstand ist … » : on garde la première forme
        return out;
    }

    // ------------------------------------------------------------------ Telekom

    /** Ligne de nom suivie d'une ligne de fonction qui correspond au motif. */
    static List<Person> nameThenRole(List<String> lines, String roleRegex, String defaultRole) {
        Pattern rolePattern = Pattern.compile(roleRegex);
        List<Person> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            PageText.Candidate c = PageText.person(lines.get(i));
            if (c != null && rolePattern.matcher(lines.get(i + 1)).matches()) {
                String role = lines.get(i + 1);
                add(out, lines.get(i), role.length() > 90 ? defaultRole : role, null);
            }
        }
        return out;
    }

    /** « Dr. Frank Appel » puis « Mitglied des Aufsichtsrats seit 7. April 2022, …, Vorsitzender des Aufsichtsrats seit … ». */
    static List<Person> telekomSupervisoryBoard(List<String> lines) {
        Pattern p = Pattern.compile("^Mitglied des Aufsichtsrats seit (?<date>\\d{1,2}\\. \\p{L}+ \\d{4})(?<rest>.*)$");
        List<Person> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher m = p.matcher(lines.get(i + 1));
            if (m.matches() && PageText.person(lines.get(i)) != null) {
                String rest = m.group("rest");
                String role = "Mitglied des Aufsichtsrats";
                if (rest.contains("stellvertretender Vorsitzender des Aufsichtsrats seit") || rest.contains("stellvertretende Vorsitzende des Aufsichtsrats seit")) {
                    role = "Stellvertretender Aufsichtsratsvorsitzender";
                } else if (rest.contains("Vorsitzender des Aufsichtsrats seit") || rest.contains("Vorsitzende des Aufsichtsrats seit")) {
                    role = "Aufsichtsratsvorsitzender";
                }
                add(out, lines.get(i), role, date(m.group("date"), DE_MONTHS));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Uniper

    /** « Michael D. Lewis » puis « Chief Executive Officer (CEO) since 1 June 2023 ». */
    static List<Person> uniper(List<String> lines) {
        Pattern role = Pattern.compile("^(?<role>Chief [A-Za-z ]+ Officer(?: \\([A-Z]{3}\\))?)(?: since (?<date>\\d{1,2} \\p{L}+ \\d{4}))?.*$");
        List<Person> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher m = role.matcher(lines.get(i + 1));
            if (m.matches() && PageText.person(lines.get(i)) != null && lines.get(i).split(" ").length <= 4) {
                add(out, lines.get(i), m.group("role"), m.group("date") == null ? null : englishDate(m.group("date")));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ DFS

    /** Mentions légales : « Vorsitzende des Aufsichtsrats / Nom » puis « Geschäftsführer / Nom (Vorsitzender) / … ». */
    static List<Person> dfs(List<String> lines) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).matches("^Vorsitzende[rn]? des Aufsichtsrats$")) {
                add(out, lines.get(i + 1), "Aufsichtsratsvorsitzende(r)", null);
            }
            if (lines.get(i).equals("Geschäftsführer")) {
                for (int k = i + 1; k < lines.size() && k <= i + 8; k++) {
                    String l = lines.get(k).replaceAll("\\s*\\(Vorsitzende[rn]?\\)$", "");
                    if (PageText.person(l) == null) {
                        break;
                    }
                    add(out, l, lines.get(k).contains("(Vorsitzende") ? "Vorsitzender der Geschäftsführung" : "Geschäftsführer", null);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Bundesdruckerei

    /** « Christian Helfrich (CEO) » */
    static List<Person> bundesdruckerei(List<String> lines) {
        Pattern p = Pattern.compile("^(?<name>.+?) \\((?<role>C[A-Z]O)\\)$");
        List<Person> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                add(out, m.group("name"), "Geschäftsführung (" + m.group("role") + ")", null);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ outils

    private static void add(List<Person> out, String rawName, String role, LocalDate start) {
        PageText.Candidate c = PageText.person(rawName);
        if (c == null || out.stream().anyMatch(p -> p.cleanName().equals(c.cleanName()))) {
            return;
        }
        out.add(new Person(c.name(), c.title(), c.cleanName(), role, start));
    }

    private static LocalDate date(String text, String[] months) {
        Matcher m = Pattern.compile("^(\\d{1,2})\\.? (\\p{L}+) (\\d{4})$").matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        for (int i = 0; i < months.length; i++) {
            if (months[i].equalsIgnoreCase(m.group(2))) {
                return LocalDate.of(Integer.parseInt(m.group(3)), i + 1, Integer.parseInt(m.group(1)));
            }
        }
        return null;
    }

    private static LocalDate englishDate(String text) {
        return date(text, EN_MONTHS);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
