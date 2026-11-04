package com.swiftcheck.aml.plugins.depep.internationalorgs;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un extracteur par organisation internationale établie en Allemagne dont la page de direction est lisible (ESO, EMBL,
 * ITLOS). L'EPO, EUMETSAT, l'OCCAR et la CCNUCC ne sont pas couverts : leurs pages répondent 404, ne contiennent aucun nom
 * ou sont protégées. Aucun nom n'est embarqué dans le plugin.
 */
final class OrgPages {

    private OrgPages() {
    }

    /** Un dirigeant lu sur une page. */
    record Member(String fullName, String title, String cleanName, String role, LocalDate start, LocalDate end) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    private static final String[] MONTHS_EN = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};

    static {
        EXTRACTORS.put("eso", OrgPages::eso);
        EXTRACTORS.put("embl", OrgPages::embl);
        EXTRACTORS.put("itlos", OrgPages::itlos);
    }

    /**
     * ESO : « Director General » / nom / biographie (« On 1 September 2026, X became ESO's Director General »), puis la liste
     * des anciens directeurs généraux « nom » / « 2017–2026 ». Le précédent directeur finit la veille de l'arrivée du
     * nouveau lorsque les deux années coïncident.
     */
    static List<Member> eso(List<String> lines) {
        List<Member> out = new ArrayList<>();
        int a = lines.indexOf("Director General");
        if (a < 0 || a + 1 >= lines.size()) {
            return out;
        }
        PageText.Candidate current = PageText.person(lines.get(a + 1));
        if (current == null) {
            return out;
        }
        LocalDate start = null;
        Pattern became = Pattern.compile("On (\\d{1,2} \\p{L}+ \\d{4}), .*? became ESO.s Director General");
        for (String l : lines) {
            Matcher m = became.matcher(l);
            if (m.find()) {
                start = englishDate(m.group(1));
                break;
            }
        }
        out.add(new Member(current.name(), current.title(), current.cleanName(), "Generaldirektor der ESO", start, null));
        int p = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals("Past ESO Directors General")) {
                p = i;
                break;
            }
        }
        Pattern years = Pattern.compile("^(\\d{4})\\s*[–-]\\s*(\\d{4})$");
        List<Member> past = new ArrayList<>();
        for (int i = p + 1; p >= 0 && i + 1 < lines.size(); i++) {
            Matcher y = years.matcher(lines.get(i + 1));
            PageText.Candidate c = PageText.person(lines.get(i));
            if (c != null && y.matches()) {
                past.add(new Member(c.name(), c.title(), c.cleanName(), "Ehemaliger Generaldirektor der ESO",
                        LocalDate.of(Integer.parseInt(y.group(1)), 1, 1), LocalDate.of(Integer.parseInt(y.group(2)), 12, 31)));
            }
        }
        // le plus récent des anciens directeurs finit la veille de l'arrivée du nouveau quand les deux années coïncident
        for (int k = 0; k < past.size(); k++) {
            final Member original = past.get(k);
            Member m = original;
            if (start != null && m.end().getYear() == start.getYear() && past.stream().noneMatch(x -> x.end().isAfter(original.end()))) {
                m = new Member(m.fullName(), m.title(), m.cleanName(), m.role(), m.start(), start.minusDays(1));
            }
            out.add(m);
        }
        return out;
    }

    /** EMBL : « 31 March 2026 » puis « Tony Hyman starts as EMBL Director General » dans la liste des actualités. */
    static List<Member> embl(List<String> lines) {
        Pattern p = Pattern.compile("^(?<name>.+?) starts as EMBL Director General$");
        List<Member> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            Matcher m = p.matcher(lines.get(i));
            PageText.Candidate c = m.matches() ? PageText.person(m.group("name")) : null;
            if (c != null) {
                LocalDate start = lines.get(i - 1).matches("^\\d{1,2} \\p{L}+ \\d{4}$") ? englishDate(lines.get(i - 1)) : null;
                out.add(new Member(c.name(), c.title(), c.cleanName(), "Generaldirektor des EMBL", start, null));
                break; // l'actualité la plus récente
            }
        }
        return out;
    }

    /** ITLOS : « President » / nom / « (Pays) », « Vice-President » / nom / « (Pays) », puis les autres juges nom / « (Pays) ». */
    static List<Member> itlos(List<String> lines) {
        List<Member> out = new ArrayList<>();
        int a = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("The current composition of the Tribunal")) {
                a = i;
                break;
            }
        }
        if (a < 0) {
            return out;
        }
        String role = "Richter am Internationalen Seegerichtshof";
        for (int i = a + 1; i + 1 < lines.size(); i++) {
            String l = lines.get(i);
            if (l.equals("President") || l.equals("Vice-President")) {
                role = l.equals("President") ? "Präsident des Internationalen Seegerichtshofs" : "Vizepräsident des Internationalen Seegerichtshofs";
                continue;
            }
            PageText.Candidate c = PageText.person(l);
            if (c != null && lines.get(i + 1).matches("^\\([^)]+\\)$")) {
                out.add(new Member(c.name(), c.title(), c.cleanName(), role, null, null));
                role = "Richter am Internationalen Seegerichtshof";
                i++;
            } else if (!out.isEmpty() && c == null && !l.matches("^\\([^)]+\\)$")) {
                break; // fin de la liste
            }
        }
        return out;
    }

    private static LocalDate englishDate(String text) {
        Matcher m = Pattern.compile("^(\\d{1,2}) (\\p{L}+) (\\d{4})$").matcher(text.trim());
        if (!m.matches()) {
            return null;
        }
        for (int i = 0; i < MONTHS_EN.length; i++) {
            if (MONTHS_EN[i].equalsIgnoreCase(m.group(2))) {
                return LocalDate.of(Integer.parseInt(m.group(3)), i + 1, Integer.parseInt(m.group(1)));
            }
        }
        return null;
    }
}
