package com.swiftcheck.aml.plugins.depep.landconstitutionalcourts;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un extracteur par cour : les 16 pages n'ont ni la même structure ni la même technique, et un extracteur générique
 * produit de faux noms. Chacun lit les lignes de texte de la page téléchargée entre deux repères et ne garde que les
 * lignes qui sont des noms de personnes ({@link PageText#person}). Aucun nom n'est embarqué.
 */
final class LandCourts {

    private LandCourts() {
    }

    /** Un membre lu sur la page. */
    record Member(String fullName, String title, String cleanName, String role, LocalDate endDate, String note) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    private static final Pattern HEADING_PRESIDENT = Pattern.compile(
            "^(Vize-?)?[Pp]räsident(in)?( des (Nds\\. )?(Verfassungsgerichtshofs|Staatsgerichtshofs|Landesverfassungsgerichts|Verfassungsgerichts)( .*)?)?$");
    private static final Pattern QUALIFIED = Pattern.compile("^(Vize-?)?[Pp]räsident(in)? des (Nds\\. )?(Verfassungsgerichtshofs|Staatsgerichtshofs|Landesverfassungsgerichts|Verfassungsgerichts).*$");
    private static final Pattern PREFIX_ROLE = Pattern.compile("^((Vize-?)?[Pp]räsident(in)?)\\s+(?!des |der )\\p{Lu}.*$");
    private static final Pattern DATE = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");

    static {
        EXTRACTORS.put("vgh-bw", lines -> scan(after(lines, "^Der Verfassungsgerichtshof besteht"), false, false, true));
        EXTRACTORS.put("vgh-by", lines -> scan(between(lines, "^Verzeichnis der Richterinnen und Richter$", "^Stellvertreter der weiteren", false), false, false));
        EXTRACTORS.put("vgh-be", lines -> scan(between(lines, "^Präsident(in)? \\p{Lu}", "^Geschäftsleiter", false), false, false));
        EXTRACTORS.put("vgh-bb", lines -> scan(between(lines, "^Präsident \\p{Lu}", "^Seite drucken", false), false, false));
        EXTRACTORS.put("stgh-hb", LandCourts::bremen);
        EXTRACTORS.put("vgh-hh", lines -> scan(between(lines, "^Präsidentin$", "^Vertreterinnen und Vertreter", false), false, false));
        EXTRACTORS.put("stgh-he", lines -> scan(between(lines, "^Präsident des Staatsgerichtshofs$", "^Geschäftsleiter", false), false, false));
        EXTRACTORS.put("vgh-mv", lines -> everyOther(between(lines, "^Stellvertreter$", "^(Kontakt|Impressum|Seite drucken)", false), 0));
        EXTRACTORS.put("stgh-ni", lines -> scan(between(lines, "^Die Mitglieder des Staatsgerichtshofs$", "^Staatsgerichtshof$", false), false, false));
        EXTRACTORS.put("vgh-nw", lines -> scan(between(lines, "^Präsidentin$", "^Stellvertretende Mitglieder", false), true, false));
        EXTRACTORS.put("vgh-rp", LandCourts::rhinelandPalatinate);
        EXTRACTORS.put("vgh-sl", lines -> scan(between(lines, "^Mitglieder$", "^Stellvertretende Mitglieder", false), false, true));
        EXTRACTORS.put("vgh-sn", lines -> scan(between(lines, "^Präsident des Verfassungsgerichtshofs", "^Stellvertreter", false), false, false));
        EXTRACTORS.put("vgh-st", LandCourts::saxonyAnhalt);
        EXTRACTORS.put("vgh-sh", lines -> scan(between(lines, "^Mitglieder$", "^Hinweis zur Verwendung", false), false, false));
        EXTRACTORS.put("vgh-th", lines -> scan(between(lines, "^Präsident$", "^Diese Seite teilen", false), false, false));
    }

    // ------------------------------------------------------------------ repères de section

    static List<String> after(List<String> lines, String startRegex) {
        return between(lines, startRegex, null, false);
    }

    /** Lignes comprises entre le premier repère de début (inclus ou non) et le premier repère de fin qui le suit. */
    static List<String> between(List<String> lines, String startRegex, String endRegex, boolean excludeStart) {
        Pattern start = Pattern.compile(startRegex);
        Pattern end = endRegex == null ? null : Pattern.compile(endRegex);
        int a = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (start.matcher(lines.get(i)).find()) {
                a = i;
                break;
            }
        }
        if (a < 0) {
            return List.of();
        }
        int b = lines.size();
        if (end != null) {
            for (int i = a + 1; i < lines.size(); i++) {
                if (end.matcher(lines.get(i)).find()) {
                    b = i;
                    break;
                }
            }
        }
        return new ArrayList<>(lines.subList(excludeStart ? a + 1 : a, b));
    }

    // ------------------------------------------------------------------ lecture générique d'une section

    /** Toute ligne qui est un nom valide devient un membre ; la fonction se lit dans la ligne ou le titre qui la précède. */
    static List<Member> scan(List<String> section, boolean allowSurnameOnly, boolean termEnd) {
        return scan(section, allowSurnameOnly, termEnd, false);
    }

    static List<Member> scan(List<String> section, boolean allowSurnameOnly, boolean termEnd, boolean suffixRole) {
        Map<String, Member> byName = new LinkedHashMap<>();
        for (int i = 0; i < section.size(); i++) {
            String line = section.get(i);
            if (line.startsWith("–") || line.startsWith("-")) {
                continue;
            }
            PageText.Candidate c = candidate(line, allowSurnameOnly);
            if (c == null) {
                continue;
            }
            String role = role(section, i, false, suffixRole);
            LocalDate end = termEnd ? termEnd(section, i) : null;
            byName.putIfAbsent(c.cleanName(), new Member(c.name(), c.title(), c.cleanName(), role, end,
                    allowSurnameOnly && c.cleanName().split(" ").length == 1 ? "Prénom absent de la page officielle." : null));
        }
        return new ArrayList<>(byName.values());
    }

    private static PageText.Candidate candidate(String line, boolean allowSurnameOnly) {
        if (line.matches("^N\\.?\\s?N\\.?$")) {
            return null; // siège vacant
        }
        PageText.Candidate c = PageText.person(line, allowSurnameOnly);
        if (c == null) {
            return null;
        }
        for (String part : c.cleanName().split(" ")) {
            if (part.length() < 2 && !part.endsWith(".")) {
                return null;
            }
        }
        return c;
    }

    /** Fonction : début de ligne, titre qui précède la ligne, ou mention entre parenthèses ; « Mitglied » à défaut. */
    static String role(List<String> section, int i, boolean nextHeading) {
        return role(section, i, nextHeading, false);
    }

    static String role(List<String> section, int i, boolean nextHeading, boolean suffixRole) {
        String line = section.get(i);
        String lower = line.replaceAll("\\([^)]*\\)", "");
        Matcher m = PREFIX_ROLE.matcher(lower);
        if (m.matches()) {
            return normalize(m.group(1));
        }
        if (line.matches(".*\\((Vize-?)?[Pp]räsident(in)?\\).*")) {
            return normalize(line.replaceAll(".*\\(((Vize-?)?[Pp]räsident(in)?)\\).*", "$1"));
        }
        if (QUALIFIED.matcher(lower).find() && !lower.contains("Landesarbeits") && !lower.contains("Landgericht")) {
            Matcher q = Pattern.compile("^((Vize-?)?[Pp]räsident(in)?)").matcher(lower);
            if (q.find()) {
                return normalize(q.group(1));
            }
        }
        if (suffixRole && lower.matches(".*\\s(Vize-?)?[Pp]räsident(in)?$")) {
            return normalize(lower.replaceAll(".*\\s((Vize-?)?[Pp]räsident(in)?)$", "$1"));
        }
        for (int back = 1; back <= 1 && i - back >= 0; back++) {
            String h = section.get(i - back);
            if (candidate(h, false) == null && HEADING_PRESIDENT.matcher(h).matches() && (h.matches("^(Vize-?)?[Pp]räsident(in)?$") || QUALIFIED.matcher(h).matches()
                    || h.startsWith("Präsident des Verfassungsgerichtshofs"))) {
                return normalize(h.replaceAll("^((Vize-?)?[Pp]räsident(in)?).*$", "$1"));
            }
        }
        if (nextHeading && i + 1 < section.size() && QUALIFIED.matcher(section.get(i + 1)).matches()) {
            return normalize(section.get(i + 1).replaceAll("^((Vize-?)?[Pp]räsident(in)?).*$", "$1"));
        }
        return "Mitglied";
    }

    private static String normalize(String r) {
        String x = r.replace("Vize-", "Vize").replace("VizeP", "Vizep");
        return x.substring(0, 1).toUpperCase() + x.substring(1);
    }

    private static LocalDate termEnd(List<String> section, int i) {
        for (int k = i + 1; k < Math.min(section.size(), i + 5); k++) {
            String l = section.get(k);
            if (l.startsWith("Ende der Amtszeit")) {
                Matcher d = DATE.matcher(l);
                if (d.find()) {
                    return LocalDate.parse(d.group(), DateTimeFormatter.ofPattern("dd.MM.yyyy"));
                }
            }
            if (candidate(l, false) != null) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ cas particuliers

    /** Mecklembourg : colonnes « Mitglieder » et « Stellvertreter » imprimées en paires membre / suppléant. */
    static List<Member> everyOther(List<String> section, int parity) {
        List<Member> all = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < section.size(); i++) {
            PageText.Candidate c = candidate(section.get(i), false);
            if (c != null) {
                all.add(new Member(c.name(), c.title(), c.cleanName(), role(section, i, false), null, null));
                kept.add(c.cleanName());
            }
        }
        List<Member> out = new ArrayList<>();
        for (int i = parity; i < all.size(); i += 2) {
            out.add(all.get(i));
        }
        return out;
    }

    /** Brême : chaque membre a un lien « Profil Nom » ; la fonction est la ligne qui suit le nom. */
    static List<Member> bremen(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Profil ")) {
                PageText.Candidate c = candidate(lines.get(i), false);
                if (c != null && out.stream().noneMatch(m -> m.cleanName().equals(c.cleanName()))) {
                    int nameLine = -1;
                    for (int k = i - 1; k >= Math.max(0, i - 4); k--) {
                        PageText.Candidate prev = candidate(lines.get(k), false);
                        if (prev != null && prev.cleanName().equals(c.cleanName())) {
                            nameLine = k;
                            break;
                        }
                    }
                    out.add(new Member(c.name(), c.title(), c.cleanName(), nameLine >= 0 ? role(lines, nameLine, true) : "Mitglied", null, null));
                }
            }
        }
        return out;
    }

    /** Rhénanie-Palatinat : « n. / fonction / nom » ; la première occurrence du numéro est le membre, la seconde son suppléant. */
    static List<Member> rhinelandPalatinate(List<String> lines) {
        List<String> section = after(lines, "^Stellvertretende Mitglieder$");
        List<Member> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String number = null;
        for (int i = 0; i < section.size(); i++) {
            String l = section.get(i);
            if (l.matches("^\\d+\\.$")) {
                number = l;
                continue;
            }
            boolean vacant = l.matches("^N\\.?\\s?N\\.?$");
            PageText.Candidate c = vacant ? null : candidate(l, false);
            if ((c != null || vacant) && number != null) {
                boolean first = seen.add(number);
                if (first && c != null) {
                    String role = i >= 2 && section.get(i - 2).startsWith("Präsident des Verfassungsgerichtshofs") ? "Präsident" : "Mitglied";
                    out.add(new Member(c.name(), c.title(), c.cleanName(), role, null, null));
                }
                number = null;
            }
        }
        return out;
    }

    /** Saxe-Anhalt : tableau « membre / suppléant personnel » ; les noms alternent, une † signale une personne décédée. */
    static List<Member> saxonyAnhalt(List<String> lines) {
        List<String> section = between(lines, "^persönliche\\(r\\) Stellvertreter\\(in\\)$", "^Kammerbesetzung", true);
        List<Member> slots = new ArrayList<>();
        List<Boolean> deceased = new ArrayList<>();
        for (String raw : section) {
            boolean dead = raw.contains("†");
            PageText.Candidate c = PageText.person(raw.replace("†", " ").trim().replaceAll(":", ""), false);
            if (c != null) {
                slots.add(new Member(c.name(), c.title(), c.cleanName(), role(List.of(raw), 0, false), null, null));
                deceased.add(dead);
            }
        }
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < slots.size(); i += 2) {
            if (!deceased.get(i)) {
                out.add(slots.get(i));
            }
        }
        return out;
    }
}
