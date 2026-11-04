package com.swiftcheck.aml.plugins.bepep.walloonparliament;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anciens députés wallons, d'après la page « Les anciens membres » du Parlement de Wallonie
 * ({@code pwpages?p=composition_mandataires}) et leur fiche ({@code composition_dep_det&id=…&t=old}).
 *
 * <p>La liste ne donne que des années (« 2019-2024 », « 2024-2029, 2014-2019 »). Seuls les anciens dont une période
 * se termine après la date limite de rétention sont donc ouverts : leur fiche donne la date exacte
 * (« Député wallon : du 25/06/2024 au 03/09/2025 »).
 */
final class WalloonFormer {

    /** Un ancien député tel que la liste le montre. */
    record Entry(String id, String fullName, String firstName, String lastName, String party, String area,
            int lastYear) {
    }

    /** Un ancien dont la fiche a donné les dates du mandat. */
    record Mandate(Entry entry, LocalDate start, LocalDate end) {
    }

    private static final Pattern BLOCK = Pattern.compile("mini_fiche_mand");
    private static final Pattern HEADING = Pattern.compile(
            "composition_dep_det&(?:amp;)?id=(\\d+)&(?:amp;)?t=old\">([^<]+)</a>(?:<br\\s*/?>\\s*<span[^>]*>([^<]*)</span>)?");
    private static final Pattern AREA = Pattern.compile("<small>(.*?)</small>", Pattern.DOTALL);
    private static final Pattern YEARS = Pattern.compile("text-pw-date\">([^<]*)<");
    private static final Pattern YEAR = Pattern.compile("(\\d{4})\\s*-\\s*(\\d{4})");
    private static final Pattern DEPUTY = Pattern.compile(
            "Députée? wallon(?:ne)? : du (\\d{2})/(\\d{2})/(\\d{4}) au (\\d{2})/(\\d{2})/(\\d{4})");

    private WalloonFormer() {
    }

    /** Tous les anciens de la liste (dernière année de leurs périodes comprise). */
    static List<Entry> entries(String listHtml) {
        List<Entry> list = new ArrayList<>();
        String[] blocks = BLOCK.split(listHtml);
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            Matcher h = HEADING.matcher(block);
            if (!h.find()) {
                continue;
            }
            String full = clean(h.group(2));
            String party = h.group(3) == null ? null : clean(h.group(3));
            Matcher a = AREA.matcher(block);
            String area = a.find() ? clean(a.group(1).replaceAll("<br\\s*/?>", ", ")) : null;
            Matcher y = YEARS.matcher(block);
            int last = 0;
            if (y.find()) {
                Matcher years = YEAR.matcher(y.group(1));
                while (years.find()) {
                    last = Math.max(last, Integer.parseInt(years.group(2)));
                }
            }
            String[] names = splitName(full);
            list.add(new Entry(h.group(1), full, names[0], names[1], party, area, last));
        }
        return list;
    }

    /** Les anciens à ouvrir : une période se termine l'année de la date limite, ou après. */
    static List<Entry> candidates(List<Entry> entries, LocalDate cutoff) {
        return entries.stream().filter(e -> e.lastYear() >= cutoff.getYear()).toList();
    }

    /** Dernière période « Député wallon : du … au … » de la fiche, ou null. */
    static Mandate mandate(Entry entry, String detailHtml) {
        String text = detailHtml.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ");
        LocalDate bestStart = null;
        LocalDate bestEnd = null;
        Matcher m = DEPUTY.matcher(text);
        while (m.find()) {
            LocalDate start = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1)));
            LocalDate end = LocalDate.of(Integer.parseInt(m.group(6)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(4)));
            if (bestEnd == null || end.isAfter(bestEnd)) {
                bestStart = start;
                bestEnd = end;
            }
        }
        return bestEnd == null ? null : new Mandate(entry, bestStart, bestEnd);
    }

    /** « ALBERT Victor dit Vicky » → {« Victor dit Vicky », « ALBERT »} : le nom de famille est en majuscules. */
    static String[] splitName(String full) {
        String[] tokens = full.trim().split("\\s+");
        int i = 0;
        while (i < tokens.length - 1 && tokens[i].equals(tokens[i].toUpperCase()) && tokens[i].matches(".*\\p{L}.*")) {
            i++;
        }
        if (i == 0) {
            i = 1;
        }
        String last = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, i));
        String first = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length));
        return new String[] { first, last };
    }

    private static String clean(String s) {
        return s.replace("&amp;", "&").replace("&#039;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }
}
