package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Juges d'une cour fédérale lus dans son plan de répartition officiel (Geschäftsverteilungsplan). Les plans ne donnent
 * que le nom de famille, précédé du titre et de la fonction : « Vorsitzender Richter am Bundesgerichtshof Prof. Dr.
 * Koch », « Richterin am BSG Behrend », « Richter (stellv. Vorsitzender) Dr. Hammer » (BVerwG), « Richter am
 * Bundesarbeitsgericht P e s s i n g e r » (BAG, lettres espacées). Un juge cité plusieurs fois (senats, suppléances)
 * n'est retenu qu'une fois, avec la fonction la plus haute.
 * <p>
 * Le texte doit venir de PDFBox avec le tri par position ({@link OfficialPlans#text(byte[])}) : sans lui, les plans
 * en colonnes (BGH) séparent la fonction du nom.
 */
final class PlanJudges {

    /** Juge du plan : nom de famille, initiale éventuelle du prénom (« B. Grüneberg »), titre et fonction. */
    record PlanJudge(String lastName, String initial, String title, boolean female, boolean presiding) {

        String displayName() {
            return ((title == null ? "" : title + " ") + (initial == null ? "" : initial + " ") + lastName).trim();
        }
    }

    private static final String TITLES = "(?:(?:Prof\\.|Dr\\.|PD|h\\.\\s?c\\.|LL\\.\\s?M\\.|mult\\.)\\s*)*";
    private static final String NAME = "(?:(\\p{Lu}\\.)\\s)?((?:(?:von|van|de|zu|vom)\\s(?:der\\s|dem\\s|den\\s)?)?\\p{Lu}[\\p{L}'’]+(?:-\\p{Lu}[\\p{L}'’]+)?)";
    /** Lettres espacées du BAG (« P e s s i n g e r ») : au moins trois lettres isolées. */
    private static final Pattern SPACED = Pattern.compile("(?<![\\p{L}])(\\p{L}(?: \\p{L}){2,}(?: - \\p{L}(?: \\p{L})+)?)(?![\\p{L}])");
    /** Titre de noblesse placé avant la particule : « Reichsgräfin von (stv. Vorsitzende) Schmettau ». */
    private static final Pattern NOBILITY = Pattern.compile("(?:Reichs)?(?:Graf|Gräfin)|Freiherr|Freifrau|Prinz|Prinzessin");
    private static final Pattern AFTER_NOBILITY = Pattern.compile("\\s*(von|zu|vom)\\s+(?:\\([^)]*\\)\\s*)?(\\p{Lu}[\\p{L}'’]+(?:-\\p{Lu}[\\p{L}'’]+)?)");
    /** Mots communs qu'une particule ne doit pas faire passer pour un nom (« Richter von der Mitwirkung »). */
    private static final Pattern COMMON_NOUN = Pattern.compile(".*(ung|heit|keit|schaft|ion|tät)$");
    /** Mots en majuscule qui suivent un libellé de fonction sans être un nom. */
    private static final Set<String> NOT_NAMES = Set.of("Vorsitzende", "Vorsitzender", "Vorsitzenden", "Richter", "Richterin",
            "Regelmäßige", "Regelmäßiger", "Vertreter", "Vertreterin", "Vertreterinnen", "Weitere", "Mitglieder", "Beisitzer",
            "Beisitzerin", "Senat", "Senats", "Bei", "Die", "Der", "Das", "Den", "Dem", "Im", "In", "Ist", "NN", "N.N.", "Für",
            "Präsident", "Präsidentin", "Vizepräsident", "Vizepräsidentin", "Stellvertreter", "Stellvertreterin", "Großer",
            "Gemeinsamer", "Sachen", "Verfahren", "Wehrdienstsenat", "Fachsenat", "Bundesrichter", "Bundesrichterin", "Und", "Oder",
            "Dr", "Prof", "Vors", "Versicherte", "Versicherten", "Arbeitgeber", "Arbeitnehmer", "Kreis", "Kreisen");

    private PlanJudges() {
    }

    /**
     * Juges du plan d'une cour.
     *
     * @param text       texte du plan
     * @param courtNames noms qui suivent « am » dans le plan, séparés par {@code |} (« Bundesgerichtshof|BGH »)
     */
    static List<PlanJudge> parse(String text, String courtNames) {
        String flat = unspace(text).replaceAll("\\s+", " ");
        String court = courtNames == null || courtNames.isBlank() ? "" : "(?:am\\s+(?:" + courtNames + ")\\s+)?";
        Pattern judge = Pattern.compile("(Vorsitzende[r]?\\s+)?(?<!ehrenamtliche\\s|ehrenamtlichen\\s|ehrenamtlicher\\s)\\b(Richter(?:in)?)\\s+(?:\\((?:stellv|stv)\\.?\\s*Vorsitzende[r]?\\)\\s+)?"
                + court + "(" + TITLES + ")" + NAME);
        Map<String, PlanJudge> byName = new LinkedHashMap<>();
        Matcher m = judge.matcher(flat);
        int from = 0;
        while (m.find(from)) {
            String lastName = m.group(5);
            from = m.end();
            String title = m.group(3).trim().replaceAll("\\s+", " ");
            if (NOBILITY.matcher(lastName).matches()) {
                Matcher n = AFTER_NOBILITY.matcher(flat).region(m.end(), flat.length());
                if (!n.lookingAt()) {
                    continue;
                }
                title = (title + " " + lastName).trim();
                lastName = n.group(1) + " " + n.group(2);
                from = n.end();
            }
            String base = lastName.substring(lastName.lastIndexOf(' ') + 1);
            if (NOT_NAMES.contains(base) || base.length() < 2 || (!base.equals(lastName) && COMMON_NOUN.matcher(base).matches())) {
                // « Vorsitzender Richter am BGH » suivi d'un autre libellé : la recherche repart sur ce libellé
                from = m.start(5);
                continue;
            }
            PlanJudge j = new PlanJudge(lastName, m.group(4), title.isEmpty() ? null : title, m.group(2).endsWith("in"),
                    m.group(1) != null);
            String key = key(j.lastName(), j.initial());
            PlanJudge known = byName.get(key);
            if (known == null || (!known.presiding() && j.presiding()) || (known.title() == null && j.title() != null)) {
                byName.put(key, known == null ? j : merge(known, j));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static PlanJudge merge(PlanJudge a, PlanJudge b) {
        return new PlanJudge(a.lastName(), a.initial() != null ? a.initial() : b.initial(),
                b.title() != null ? b.title() : a.title(), a.female() || b.female(), a.presiding() || b.presiding());
    }

    static String key(String lastName, String initial) {
        return lastName.toLowerCase(Locale.ROOT) + (initial == null ? "" : "|" + initial.toLowerCase(Locale.ROOT));
    }

    /** « P e s s i n g e r » devient « Pessinger ». */
    static String unspace(String text) {
        Matcher m = SPACED.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).replace(" ", "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
