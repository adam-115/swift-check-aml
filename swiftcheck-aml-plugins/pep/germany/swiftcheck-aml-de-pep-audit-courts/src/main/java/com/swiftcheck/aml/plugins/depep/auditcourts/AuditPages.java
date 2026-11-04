package com.swiftcheck.aml.plugins.depep.auditcourts;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracteurs des pages du Bundesrechnungshof. Aucune page lisible n'existe pour les 16 cours des comptes des Länder :
 * elles ne sont pas couvertes. Aucun nom n'est embarqué dans le plugin.
 */
final class AuditPages {

    private AuditPages() {
    }

    /** Un titulaire lu sur une page. */
    record Member(String fullName, String title, String cleanName, String role, LocalDate start, LocalDate end) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    private static final String[] MONTHS = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September",
            "Oktober", "November", "Dezember"};

    static {
        EXTRACTORS.put("brh", AuditPages::leadership);
        EXTRACTORS.put("brh-succession", AuditPages::succession);
    }

    /** « Präsident des Bundesrechnungshofes ist Ansgar Heveling. » et « Vizepräsidentin des Bundesrechnungshofes ist Klara Geywitz. » */
    static List<Member> leadership(List<String> lines) {
        Pattern p = Pattern.compile("(?<role>(?:Vize)?[Pp]räsident(?:in)?) des Bundesrechnungshofes ist (?<name>[^.]+?)\\.?$");
        List<Member> out = new ArrayList<>();
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) {
                PageText.Candidate c = PageText.person(m.group("name"));
                if (c != null && out.stream().noneMatch(x -> x.cleanName().equals(c.cleanName()))) {
                    out.add(new Member(c.name(), c.title(), c.cleanName(), m.group("role") + " des Bundesrechnungshofes", null, null));
                }
            }
        }
        return out;
    }

    /**
     * Communiqué de changement de présidence : « … übernimmt X das Amt des Präsidenten … Nachfolge von Y an, der … im Mai
     * 2026 aus dem Amt ausgeschieden war ». Donne le président sorti et le mois de sa sortie.
     */
    static List<Member> succession(List<String> lines) {
        Pattern p = Pattern.compile("Nachfolge von (?<name>.+?) an, der .*? im (?<month>\\p{L}+) (?<year>\\d{4}) aus dem Amt ausgeschieden");
        List<Member> out = new ArrayList<>();
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.find()) {
                PageText.Candidate c = PageText.person(m.group("name"));
                LocalDate end = endOfMonth(m.group("month"), Integer.parseInt(m.group("year")));
                if (c != null && end != null) {
                    out.add(new Member(c.name(), c.title(), c.cleanName(), "Ancien président du Bundesrechnungshof", null, end));
                }
            }
        }
        return out;
    }

    private static LocalDate endOfMonth(String month, int year) {
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(month)) {
                return YearMonth.of(year, i + 1).atEndOfMonth();
            }
        }
        return null;
    }
}
