package com.swiftcheck.aml.plugins.itpep.institutions;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracteurs des pages officielles des institutions italiennes. Un extracteur par site : aucun nom n'est embarqué dans
 * le plugin, les noms sont toujours lus dans la page officielle téléchargée à l'exécution.
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
        EXTRACTORS.put("banca_ditalia", InstitutionPages::bancaDItalia);
        EXTRACTORS.put("garante_privacy", InstitutionPages::garantePrivacy);
        EXTRACTORS.put("agcm", InstitutionPages::agcm);
        EXTRACTORS.put("agcom", InstitutionPages::agcom);
        EXTRACTORS.put("ivass", InstitutionPages::ivass);
        EXTRACTORS.put("difesa_smd", InstitutionPages::difesaSmd);
        EXTRACTORS.put("agcm_president", InstitutionPages::agcmPresident);
        EXTRACTORS.put("agcom_president", InstitutionPages::agcomPresident);
        EXTRACTORS.put("wfp", InstitutionPages::wfp);
        EXTRACTORS.put("ifad", InstitutionPages::ifad);
        EXTRACTORS.put("fao", InstitutionPages::fao);
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("gennaio", 1), Map.entry("febbraio", 2), Map.entry("marzo", 3), Map.entry("aprile", 4),
            Map.entry("maggio", 5), Map.entry("giugno", 6), Map.entry("luglio", 7), Map.entry("agosto", 8),
            Map.entry("settembre", 9), Map.entry("ottobre", 10), Map.entry("novembre", 11), Map.entry("dicembre", 12));

    private static final String NAME = "\\p{Lu}[\\p{L}'’.-]*(?: (?:d[aeio]l?|della|degli|dei|di|de|del|van|von)|(?: \\p{Lu}[\\p{L}'’.-]*))+";
    private static final Pattern PERSON = Pattern.compile("^" + NAME + "$");

    // ------------------------------------------------------- Banca d'Italia

    private static final Pattern BDI = Pattern.compile("^(" + NAME + "), (Governatore|Direttore generale|Direttrice generale"
            + "|Vice Direttore generale|Vice Direttrice generale) \\(dal (\\d{1,2}) (\\p{L}+) (\\d{4})\\)\\.?$");

    /**
     * Page « Direttorio » : « Fabio Panetta, Governatore (dal 1 novembre 2023) », « Paolo Angelini, Direttore generale
     * (dal 1 aprile 2026) », « Chiara Scotti, Vice Direttrice generale (dal 19 febbraio 2024) ».
     */
    static List<Member> bancaDItalia(String html) {
        List<Member> out = new ArrayList<>();
        for (String line : PageText.lines(html)) {
            Matcher m = BDI.matcher(line);
            if (m.matches()) {
                out.add(person(m.group(1), m.group(2), date(m.group(3), m.group(4), m.group(5)), null));
            }
        }
        return out;
    }

    // ------------------------------------------------------- Garante privacy

    private static final Pattern GARANTE_ROLE = Pattern.compile(
            "^\\((Presidente|Vice Presidente|Componente)(?: dal (\\d{1,2}) (\\p{L}+) (\\d{4}) al (\\d{1,2}) (\\p{L}+) (\\d{4}))?\\)$");

    /** Page « Collegio » : « Pasquale Stanzione | (Presidente) » ; « Guido Scorza | (Componente dal … al … ) ». */
    static List<Member> garantePrivacy(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher role = GARANTE_ROLE.matcher(lines.get(i + 1));
            if (role.matches() && PERSON.matcher(lines.get(i)).matches()) {
                String start = role.group(2) == null ? null : date(role.group(2), role.group(3), role.group(4));
                String end = role.group(5) == null ? null : date(role.group(5), role.group(6), role.group(7));
                out.add(person(lines.get(i), role.group(1), start, end));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ AGCM

    /** Page « Componenti » : « Elisabetta Iossa | Componente dell'Autorità garante della concorrenza e del mercato ». */
    static List<Member> agcm(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i + 1).startsWith("Componente dell'Autorità garante") && PERSON.matcher(lines.get(i)).matches()) {
                out.add(person(lines.get(i), "Componente", null, null));
            } else if (lines.get(i + 1).startsWith("Presidente dell'Autorità garante") && PERSON.matcher(lines.get(i)).matches()) {
                out.add(person(lines.get(i), "Presidente", null, null));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- AGCOM

    /** Page « Commissari » : « Laura Aria | Scopri di più | about Laura Aria » (le nom est répété dans le lien). */
    static List<Member> agcom(String html) {
        List<Member> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)<h3 class=\"card-title[^\"]*\">\\s*<a[^>]*>\\s*<span>([^<]+)</span>").matcher(html);
        while (m.find()) {
            String name = PageText.decode(m.group(1)).trim();
            if (PERSON.matcher(name).matches()) {
                out.add(person(name, "Commissario", null, null));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- IVASS

    /** Page « Direttorio integrato » : les noms qui suivent le titre « Direttorio Integrato » jusqu'à « Struttura Organizzativa ». */
    static List<Member> ivass(String html) {
        List<String> lines = PageText.lines(html);
        int end = lines.indexOf("Struttura Organizzativa");
        List<Member> out = new ArrayList<>();
        // les noms sont les lignes qui précèdent « Struttura Organizzativa », jusqu'au titre « Direttorio Integrato »
        for (int i = end - 1; end > 0 && i >= 0 && !lines.get(i).equals("Direttorio Integrato"); i--) {
            if (!PERSON.matcher(lines.get(i)).matches()) {
                break;
            }
            out.add(0, person(lines.get(i), "Membro del Direttorio integrato", null, null));
        }
        return out;
    }

    // ------------------------------------------------- Stato Maggiore della Difesa

    private static final Pattern GENERAL = Pattern.compile("^(?:Generale|Ammiraglio|Gen\\.|Amm\\.)(?: [A-Za-z.()]+)* (" + NAME + ")$");

    /** Page « Il Capo di Stato Maggiore della Difesa » : la ligne qui suit les titres, « Generale Luciano Portolano ». */
    static List<Member> difesaSmd(String html) {
        List<String> lines = PageText.lines(html);
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).equals("Il Capo di Stato Maggiore della Difesa")) {
                Matcher m = GENERAL.matcher(lines.get(i + 1));
                if (m.matches()) {
                    return List.of(person(m.group(1), "Capo di Stato Maggiore della Difesa", null, null));
                }
            }
        }
        Pattern inline = Pattern.compile("Capo di Stato maggiore della Difesa,?\\s+(?:Generale|Ammiraglio|Gen\\.|Amm\\.)(?: [A-Za-z.()]+)*\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
        Matcher im = inline.matcher(html);
        if (im.find()) {
            return List.of(person(im.group(1).trim(), "Capo di Stato Maggiore della Difesa", null, null));
        }
        return List.of();
    }

    // ------------------------------------------------- présidents des autorités

    private static final Pattern AGCM_PRESIDENT = Pattern.compile("href=\"/chi-siamo/collegio/presidente/([a-z][a-z-]+)\"");

    /** Page « Presidente » de l'AGCM : le lien de la fiche porte le nom (« …/presidente/saverio-valentino »). */
    static List<Member> agcmPresident(String html) {
        Matcher m = AGCM_PRESIDENT.matcher(html);
        if (!m.find()) {
            return List.of();
        }
        StringBuilder name = new StringBuilder();
        for (String part : m.group(1).split("-")) {
            name.append(name.length() == 0 ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return List.of(person(name.toString(), "Presidente", null, null));
    }

    private static final Pattern AGCOM_PRESIDENT = Pattern.compile(
            "composta dal Presidente (" + NAME + "), ");

    /** Page « Istituzione » de l'AGCOM : « composta dal Presidente Giacomo Lasorella, Massimiliano Capitanio… ». */
    static List<Member> agcomPresident(String html) {
        Matcher m = AGCOM_PRESIDENT.matcher(String.join(" ", PageText.lines(html)));
        return m.find() ? List.of(person(m.group(1), "Presidente", null, null)) : List.of();
    }

    // ------------------------------------------ organisations internationales

    private static final Pattern WFP = Pattern.compile("^(" + NAME + ") - (Executive Director|Deputy Executive Director[^-]*"
            + "|Assistant Executive Director.*)$");

    /** Page « Governance and leadership » du PAM : « Luke J. Lindberg - Executive Director ». */
    static List<Member> wfp(String html) {
        List<Member> out = new ArrayList<>();
        for (String line : PageText.lines(html)) {
            Matcher m = WFP.matcher(line);
            if (m.matches() && out.stream().noneMatch(x -> x.name().equals(m.group(1)))) {
                out.add(person(m.group(1), m.group(2).trim(), null, null));
            }
        }
        return out;
    }

    private static final Pattern IFAD_ROLE = Pattern.compile("^(President|Vice-President|Chief of Staff.*|Associate Vice-President.*"
            + "|Managing Director.*|Chief .* Officer.*)$");

    /** Page « Leadership » du FIDA : un nom, puis sa fonction sur la ligne suivante. */
    static List<Member> ifad(String html) {
        List<String> lines = PageText.lines(html);
        List<Member> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            final String previous = lines.get(i - 1);
            Matcher role = IFAD_ROLE.matcher(lines.get(i));
            if (role.matches() && PERSON.matcher(previous).matches()
                    && out.stream().noneMatch(x -> x.name().equals(previous))) {
                out.add(person(previous, role.group(1), null, null));
            }
        }
        return out;
    }

    private static final Pattern FAO = Pattern.compile("^Director-General (\\p{Lu}{2,}) (\\p{Lu}\\p{Ll}+)$");

    /** Page « Biography » du directeur général de la FAO : « Director-General QU Dongyu » (nom de famille en majuscules d'abord). */
    static List<Member> fao(String html) {
        for (String line : PageText.lines(html)) {
            Matcher m = FAO.matcher(line);
            if (m.matches()) {
                String surname = m.group(1).charAt(0) + m.group(1).substring(1).toLowerCase(Locale.ROOT);
                return List.of(new Member(surname + " " + m.group(2), m.group(2), surname, "Director-General", null, null));
            }
        }
        return List.of();
    }

    // ----------------------------------------------------------------- noms

    /** Prénom / nom seulement quand le nom a deux mots : « Sergio Nicoletti Altimari » reste sans découpage. */
    private static Member person(String name, String role, String start, String end) {
        String[] tokens = name.split("\\s+");
        String first = tokens.length == 2 ? tokens[0] : null;
        String last = tokens.length == 2 ? tokens[1] : null;
        return new Member(name, first, last, role, start, end);
    }

    /** « 1 », « aprile », « 2026 » → « 2026-04-01 » ; mois inconnu → {@code null}. */
    static String date(String day, String month, String year) {
        Integer m = MONTHS.get(Normalizer.normalize(month, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT));
        if (m == null) {
            return null;
        }
        return String.format(Locale.ROOT, "%s-%02d-%02d", year, m, Integer.parseInt(day));
    }
}
