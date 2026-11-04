package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un extracteur par cour fédérale. Seules sont lues les pages publiques de la présidence (et, au BVerfG, la liste
 * des 16 juges) : les juges des autres cours (BGH, BVerwG, BFH, BAG, BSG) ne sont publiés que dans des plans de
 * répartition par chambre, non lus pour l'instant. Aucun nom n'est embarqué dans le plugin.
 */
final class FederalPages {

    private FederalPages() {
    }

    /** Un titulaire lu sur une page. */
    record Member(String fullName, String title, String cleanName, String role, LocalDate start, LocalDate end) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    private static final String[] MONTHS = {"Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September",
            "Oktober", "November", "Dezember"};
    private static final Pattern LONG_DATE = Pattern.compile("(\\d{1,2})\\. (\\p{L}+) (\\d{4})");
    private static final Pattern SHORT_DATE = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");

    static {
        EXTRACTORS.put("bverfg", FederalPages::bverfg);
        EXTRACTORS.put("bgh", FederalPages::bgh);
        EXTRACTORS.put("bverwg", lines -> sentence(lines, "^(?<role>Vizepräsident(?:in)?) ist (?<name>.+?)\\s?\\.$", null));
        EXTRACTORS.put("bag", FederalPages::bag);
        EXTRACTORS.put("bsg", FederalPages::bsg);
        EXTRACTORS.put("bfh", lines -> sentence(lines,
                "^(?<role>(?:Vize)?[Pp]räsident(?:in)?) des Bundesfinanzhofs ist seit (?<date>\\d{2}\\.\\d{2}\\.\\d{4}) (?<name>.+?)\\.$", "date"));
    }

    // ------------------------------------------------------------------ BVerfG

    /** Les huit membres de chaque sénat, sous les intitulés « Erster Senat » et « Zweiter Senat ». */
    static List<Member> bverfg(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (String heading : new String[] {"Erster Senat", "Zweiter Senat"}) {
            int a = lines.indexOf(heading);
            if (a < 0) {
                continue;
            }
            int taken = 0;
            for (int i = a + 1; i < lines.size() && taken < 8; i++) {
                if (lines.get(i).startsWith("Die Mitglieder des")) {
                    continue;
                }
                PageText.Candidate c = PageText.person(lines.get(i));
                if (c == null) {
                    if (taken > 0) {
                        break;
                    }
                    continue;
                }
                out.add(new Member(c.name(), c.title(), c.cleanName(), "Richter(in) am Bundesverfassungsgericht (" + heading + ")", null, null));
                taken++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ BGH

    /** Présidents : « Nom » puis « seit 1. September 2026 » (en fonction) ou « 1. Juli 2014 - 31. August 2026 » (sorti). */
    static List<Member> bgh(List<String> lines) {
        List<Member> out = new ArrayList<>();
        int a = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^Präsident(in)? des Bundesgerichtshofs$")) {
                a = i;
                break;
            }
        }
        if (a < 0) {
            return out;
        }
        Pattern since = Pattern.compile("^seit (\\d{1,2}\\. \\p{L}+ \\d{4})$");
        Pattern range = Pattern.compile("^(\\d{1,2}\\. \\p{L}+ \\d{4}) - (\\d{1,2}\\. \\p{L}+ \\d{4})$");
        for (int i = a + 1; i + 1 < lines.size(); i++) {
            if (lines.get(i).equals("nach oben")) {
                break;
            }
            PageText.Candidate c = PageText.person(lines.get(i).replaceFirst(",.*$", ""));
            if (c == null) {
                continue;
            }
            Matcher s = since.matcher(lines.get(i + 1));
            Matcher r = range.matcher(lines.get(i + 1));
            if (s.matches()) {
                out.add(new Member(c.name(), c.title(), c.cleanName(), "Präsident(in) des Bundesgerichtshofs", longDate(s.group(1)), null));
            } else if (r.matches()) {
                out.add(new Member(c.name(), c.title(), c.cleanName(), "Ehemalige(r) Präsident(in) des Bundesgerichtshofs",
                        longDate(r.group(1)), longDate(r.group(2))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ BAG / BSG / phrases

    /** « Die Präsidentin » / « Inken Gallner » puis « Der Vizepräsident » / « Prof. Dr. Heinrich Kiel ». */
    static List<Member> bag(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String l = lines.get(i);
            String role = null;
            if (l.matches("^(Die Präsidentin|Der Präsident)$")) {
                role = "Präsident(in) des Bundesarbeitsgerichts";
            } else if (l.matches("^(Die Vizepräsidentin|Der Vizepräsident)$")) {
                role = "Vizepräsident(in) des Bundesarbeitsgerichts";
            }
            PageText.Candidate c = role == null ? null : PageText.person(lines.get(i + 1));
            if (c != null) {
                out.add(new Member(c.name(), c.title(), c.cleanName(), role, null, null));
            }
        }
        return out;
    }

    /** « Präsidentin Dr. Christine Fuchsloch » et « Vizepräsident Andreas Heinz ». */
    static List<Member> bsg(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (String l : lines) {
            Matcher m = Pattern.compile("^(?<role>Vizepräsident(?:in)?|Präsident(?:in)?) (?<name>\\p{Lu}.+)$").matcher(l);
            if (m.matches() && !m.group("name").startsWith("des ") && !m.group("name").startsWith("und ")) {
                PageText.Candidate c = PageText.person(m.group("name"));
                if (c != null && out.stream().noneMatch(x -> x.cleanName().equals(c.cleanName()))) {
                    out.add(new Member(c.name(), c.title(), c.cleanName(), m.group("role") + " des Bundessozialgerichts", null, null));
                }
            }
        }
        return out;
    }

    /** Phrase « Vizepräsidentin ist Dr. Susanne Rublack . » ; {@code dateGroup} nomme le groupe de date jj.mm.aaaa, ou null. */
    static List<Member> sentence(List<String> lines, String regex, String dateGroup) {
        Pattern p = Pattern.compile(regex);
        List<Member> out = new ArrayList<>();
        for (String l : lines) {
            Matcher m = p.matcher(l);
            if (m.matches()) {
                PageText.Candidate c = PageText.person(m.group("name"));
                if (c != null && out.stream().noneMatch(x -> x.cleanName().equals(c.cleanName()))) {
                    LocalDate start = dateGroup == null ? null : shortDate(m.group(dateGroup));
                    out.add(new Member(c.name(), c.title(), c.cleanName(), m.group("role"), start, null));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ outils

    static LocalDate longDate(String text) {
        Matcher m = LONG_DATE.matcher(text);
        if (!m.matches()) {
            return null;
        }
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(m.group(2))) {
                return LocalDate.of(Integer.parseInt(m.group(3)), i + 1, Integer.parseInt(m.group(1)));
            }
        }
        return null;
    }

    static LocalDate shortDate(String text) {
        Matcher m = SHORT_DATE.matcher(text);
        return m.matches() ? LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))) : null;
    }
}
