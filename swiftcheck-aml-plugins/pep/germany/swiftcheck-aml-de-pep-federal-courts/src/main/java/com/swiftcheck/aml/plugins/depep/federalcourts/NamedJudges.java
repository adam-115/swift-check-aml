package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Noms complets des juges fédéraux lus dans des publications officielles, pour compléter les plans de répartition qui
 * ne donnent que le nom de famille. Trois formes sont lues :
 * <ul>
 * <li>les communiqués d'élection du ministère fédéral de la Justice et du ministère fédéral du Travail : un titre de
 * cour (« Bundesgerichtshof », « Für das BAG wurden gewählt ») suivi de la liste des élus (« Dr. Ole Böger »,
 * « Herr Dr. Markus Weingarth, Richter am Arbeitsgericht ») ;</li>
 * <li>les phrases de nomination des communiqués et rapports annuels des cours : « Der Bundespräsident hat Richterin am
 * Oberlandesgericht Annett Schulze zur Richterin am Bundesgerichtshof ernannt », « Antje Weihs ist heute vom
 * Bundespräsidenten zur Richterin am Bundesfinanzhof ernannt worden » ;</li>
 * <li>les listes abrégées des rapports annuels : « VRiBVerwG Dr. Robert Keller », « Ri’nBVerwG Kerstin Schipper ».</li>
 * </ul>
 * Un nom lu n'est qu'une suite de jetons (prénoms puis nom) : le nom de famille n'est fixé qu'au rapprochement avec le
 * plan ({@link JudgeRoster}), ce qui évite de prendre « von Flughäfen » (« Dr. Robert Keller von Flughäfen, … ») pour
 * une particule.
 */
final class NamedJudges {

    /** Cour fédérale : clé de configuration, nom officiel et sigle. */
    record Court(String key, String name, String acronym) {
    }

    /** Nom lu : cour, jetons du nom (prénoms puis nom de famille, sans titre), titre, source. */
    record NamedJudge(String court, List<String> tokens, String title, String sourceUrl) {

        String text() {
            return String.join(" ", tokens);
        }
    }

    private static final Pattern PERSON_PREFIX = Pattern.compile("^(?:[-–•·]\\s*)?(?:Herrn?|Frau)\\s+");
    static final Pattern PARTICLE = Pattern.compile("^(von|van|de|zu|vom|der|den|dem)$");
    private static final Pattern TITLE_TOKEN = Pattern.compile("^(Prof\\.|Dr\\.|PD|h\\.\\s?c\\.|mult\\.|LL\\.M\\.)$");
    private static final Pattern NAME_TOKEN = Pattern.compile("^\\p{Lu}[\\p{L}'’]+(?:-\\p{Lu}[\\p{L}'’]+)*$");
    private static final Pattern NOBILITY = Pattern.compile("^(?:Reichs)?(?:Graf|Gräfin)|Freiherr|Freifrau|Prinz|Prinzessin$");

    private NamedJudges() {
    }

    /** Noms lus dans une page HTML (communiqué). */
    static List<NamedJudge> fromHtml(String html, String sourceUrl, List<Court> courts) {
        return fromLines(PageText.lines(html), sourceUrl, courts, false);
    }

    /** Noms lus dans un texte (rapport annuel en PDF). */
    static List<NamedJudge> fromText(String text, String sourceUrl, List<Court> courts) {
        return fromLines(List.of(text.split("\\R")), sourceUrl, courts, true);
    }

    /**
     * @param report rapport annuel : la forme développée « Richterin am Bundessozialgericht Nicola Behrend » y est une
     *               liste des juges ; dans un communiqué, elle peut désigner une autre personne citée (« … durch die
     *               Ernennung der Vorsitzenden Richterin am Bundesgerichtshof Monika Harms zur Generalbundesanwältin »).
     */
    static List<NamedJudge> fromLines(List<String> rawLines, String sourceUrl, List<Court> courts, boolean report) {
        List<String> lines = rawLines.stream().map(NamedJudges::repair).filter(l -> !l.isBlank()).toList();
        String text = repair(String.join(" ", lines));
        List<NamedJudge> out = new ArrayList<>();
        if (text.contains("gewählt")) {
            out.addAll(electionLists(lines, sourceUrl, courts));
        }
        if (text.contains("ernannt")) {
            out.addAll(appointments(text, sourceUrl, courts));
        }
        out.addAll(abbreviated(text, sourceUrl, courts, report));
        return out;
    }

    /**
     * Défauts du texte extrait des PDF : coupure de ligne (« Rich- ter »), trait d'union suivi d'une espace
     * (« Stengelhofen- Weiß »), initiale détachée (« Hans-J örg », « Held-D aab ») et point illisible (le rapport du BSG
     * donne « Dr� » : caractère de remplacement U+FFFD).
     */
    static String repair(String s) {
        return s.replace('�', '.')
                .replaceAll("(\\p{Ll})- (\\p{Ll})", "$1$2")
                .replaceAll("(\\p{L})- (\\p{Lu})", "$1-$2")
                .replaceAll("(?<=[\\s-])(\\p{Lu}) (\\p{Ll}{2,})", "$1$2")
                .replaceAll("[ \\t]+", " ").trim();
    }

    /** Listes d'élus sous un titre de cour. */
    static List<NamedJudge> electionLists(List<String> lines, String sourceUrl, List<Court> courts) {
        List<NamedJudge> out = new ArrayList<>();
        String court = null;
        for (String line : lines) {
            String header = courtHeader(line, courts);
            if (header != null) {
                court = header;
                continue;
            }
            if (court == null) {
                continue;
            }
            NamedJudge j = nameLine(line, court, sourceUrl);
            if (j != null) {
                out.add(j);
            } else if (line.length() > 120) {
                court = null; // fin de la liste : un paragraphe de texte
            }
        }
        return out;
    }

    /** Clé de la cour quand la ligne est un titre de liste (« Bundesgerichtshof », « Für das BSG wurden gewählt »). */
    private static String courtHeader(String line, List<Court> courts) {
        String l = line.replaceAll("[:*]", "").trim();
        if (l.length() > 60) {
            return null;
        }
        for (Court c : courts) {
            for (String name : List.of(c.name(), c.acronym())) {
                if (l.equals(name) || l.matches("(?i)(und\\s+)?für\\s+(das|den|die)\\s+" + Pattern.quote(name) + "(\\s+wurden?)?(\\s+gewählt)?")) {
                    return c.key();
                }
            }
        }
        return null;
    }

    /** « Dr. Ole Böger » ou « Herr Dr. Markus Weingarth, Richter am Arbeitsgericht, » : un élu ; sinon null. */
    static NamedJudge nameLine(String line, String court, String sourceUrl) {
        String s = PERSON_PREFIX.matcher(line.trim()).replaceFirst("");
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        s = s.replaceAll("\\s+(sowie|und)$", "").trim();
        if (s.length() > 70 || s.matches(".*\\d.*")) {
            return null;
        }
        return fromTokens(s.split("\\s+"), court, sourceUrl, true);
    }

    /**
     * Titres puis jetons de nom. Avec {@code strict}, tous les jetons doivent être des noms (ligne d'élu) ; sinon la
     * lecture s'arrête au premier jeton qui n'en est pas un (liste abrégée suivie des matières du senat).
     */
    private static NamedJudge fromTokens(String[] t, String court, String sourceUrl, boolean strict) {
        int i = 0;
        List<String> titles = new ArrayList<>();
        while (i < t.length && TITLE_TOKEN.matcher(t[i]).matches()) {
            titles.add(t[i++]);
        }
        List<String> names = new ArrayList<>();
        for (; i < t.length; i++) {
            String w = t[i].replaceAll("[,;:]$", "");
            if (NOBILITY.matcher(w).matches()) {
                titles.add(w);
            } else if ((NAME_TOKEN.matcher(w).matches() && !PageText.isStop(w)) || PARTICLE.matcher(w).matches()) {
                names.add(w);
            } else if (strict) {
                return null;
            } else {
                break;
            }
            if (!strict && t[i].matches(".*[,;:]$")) {
                break;
            }
        }
        while (!names.isEmpty() && PARTICLE.matcher(names.get(names.size() - 1)).matches()) {
            names.remove(names.size() - 1);
        }
        if (names.size() < 2 || PARTICLE.matcher(names.get(0)).matches()) {
            return null;
        }
        return new NamedJudge(court, List.copyOf(names), titles.isEmpty() ? null : String.join(" ", titles), sourceUrl);
    }

    /**
     * Phrases de nomination : le nom est la suite de jetons de nom qui précède « zum », « zur » (ou « ist … zur »),
     * après le dernier libellé de fonction.
     */
    static List<NamedJudge> appointments(String text, String sourceUrl, List<Court> courts) {
        List<NamedJudge> out = new ArrayList<>();
        for (Court c : courts) {
            String names = Pattern.quote(c.name()) + "|" + Pattern.quote(c.acronym());
            String role = "(?:Vorsitzenden\\s+|neuen\\s+)?Richter(?:in|innen|n)?\\s+am\\s+(?:" + names + ")\\b";
            Pattern direct = Pattern.compile("([^.;]{0,200}?)\\s+zu[mr]?\\s+" + role);
            Pattern passive = Pattern.compile("([^.;]{0,120}?)\\s+(?:ist|sind|wurde|wurden)\\s+(?:[^;]{0,120}?)\\s+zu[mr]?\\s+" + role);
            // titre de communiqué : « Frau Dr. Ursula Rinck neue Vorsitzende Richterin am Bundesarbeitsgericht »
            Pattern headline = Pattern.compile("([^.;]{0,80}?)\\s+(?:ist\\s+)?neue[rn]?\\s+(?:Vorsitzende[r]?\\s+)?Richter(?:in)?\\s+am\\s+(?:"
                    + names + ")\\b");
            for (Pattern p : List.of(direct, passive, headline)) {
                Matcher m = p.matcher(text);
                while (m.find()) {
                    NamedJudge j = trailingName(m.group(1), c.key(), sourceUrl);
                    if (j != null && out.stream().noneMatch(o -> o.text().equals(j.text()))) {
                        out.add(j);
                    }
                }
            }
        }
        return out;
    }

    /** Derniers jetons de nom d'un fragment (« … am Oberlandesgericht Dr. Jana Zapf »). */
    private static NamedJudge trailingName(String fragment, String court, String sourceUrl) {
        String[] t = fragment.trim().split("\\s+");
        int start = t.length;
        while (start > 0 && (NAME_TOKEN.matcher(t[start - 1]).matches() || PARTICLE.matcher(t[start - 1]).matches()
                || TITLE_TOKEN.matcher(t[start - 1]).matches() || NOBILITY.matcher(t[start - 1]).matches())
                && !PageText.isStop(t[start - 1]) && !t[start - 1].matches("Der|Die|Das|Herr|Herrn|Frau|Am|Im|Mit")) {
            start--;
        }
        while (start < t.length && PARTICLE.matcher(t[start]).matches()) {
            start++;
        }
        // « Richter am Oberlandesgericht Hamm Heinz Wöstmann » : la ville du tribunal n'est pas un prénom
        if (start > 0 && start < t.length - 2 && t[start - 1].toLowerCase(java.util.Locale.ROOT).matches(".*gerichts?")
                && COURT_SEATS.contains(t[start])) {
            start++;
        }
        return fromTokens(Arrays.copyOfRange(t, start, t.length), court, sourceUrl, true);
    }

    /** Sièges des Oberlandesgerichte et des cours des Länder, qui suivent le nom du tribunal. */
    private static final java.util.Set<String> COURT_SEATS = java.util.Set.of("Bamberg", "Berlin", "Brandenburg", "Braunschweig",
            "Bremen", "Celle", "Dresden", "Düsseldorf", "Frankfurt", "Hamburg", "Hamm", "Jena", "Karlsruhe", "Koblenz", "Köln",
            "München", "Naumburg", "Nürnberg", "Oldenburg", "Rostock", "Saarbrücken", "Schleswig", "Stuttgart", "Zweibrücken",
            "Mannheim", "Kassel", "Lüneburg", "Münster", "Bautzen", "Greifswald", "Magdeburg", "Weimar", "Freiburg", "Darmstadt",
            "Mainz", "Bonn", "Hannover", "Kiel", "Potsdam", "Leipzig", "Halle", "Erfurt", "Gera", "Chemnitz", "Augsburg", "Regensburg",
            "Würzburg", "Landshut", "Passau", "Kempten", "Ulm", "Heidelberg", "Tübingen", "Konstanz", "Essen", "Dortmund", "Bochum",
            "Duisburg", "Bielefeld", "Wuppertal", "Aachen", "Trier", "Kaiserslautern", "Gießen", "Wiesbaden", "Fulda", "Göttingen",
            "Osnabrück", "Lübeck", "Flensburg", "Itzehoe", "Schwerin", "Neubrandenburg", "Stralsund", "Cottbus", "Dessau");

    /**
     * Listes des rapports annuels : abrégées (« VRiBVerwG Dr. Robert Keller », « Ri’nBVerwG Kerstin Schipper ») ou
     * développées (« Richterin am Bundessozialgericht Nicola Behrend »). Il faut au moins un prénom et un nom : le plan,
     * qui ne donne que le nom de famille, ne produit rien ici.
     */
    static List<NamedJudge> abbreviated(String text, String sourceUrl, List<Court> courts, boolean report) {
        List<NamedJudge> out = new ArrayList<>();
        for (Court c : courts) {
            String developed = report ? "|(?:Vorsitzende[r]?\\s+)?Richter(?:in)?\\s+am\\s+" + Pattern.quote(c.name()) : "";
            Matcher m = Pattern.compile("(?:\\b(?:V?Ri|VPräs|Präs)(?:[’']?in|[’']?n)?\\s?" + Pattern.quote(c.acronym())
                    + developed + ")\\s+([^—–|()]{3,90})")
                    .matcher(text);
            int from = 0;
            while (m.find(from)) {
                // les entrées se suivent : la suivante peut commencer dans le texte capturé
                from = m.start(1);
                NamedJudge j = fromTokens(m.group(1).trim().split("\\s+"), c.key(), sourceUrl, false);
                if (j != null && out.stream().noneMatch(o -> o.text().equals(j.text()))) {
                    out.add(j);
                }
            }
        }
        return out;
    }
}
