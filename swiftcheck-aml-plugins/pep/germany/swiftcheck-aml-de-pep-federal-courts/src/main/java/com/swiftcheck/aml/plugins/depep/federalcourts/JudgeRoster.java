package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rapprochement des juges d'un plan officiel (nom de famille) et des noms complets lus dans les publications officielles.
 * Le nom de famille du plan est cherché dans les jetons de chaque nom lu, après au moins un prénom : « Robert Keller von
 * Flughäfen » donne le prénom « Robert » à « Keller ». Un juge n'est complété que si un seul prénom correspond (même
 * cour, même nom et, si le plan donne une initiale comme « Dr. C. Fischer », même initiale). Sinon il est publié avec son
 * nom de famille seul.
 */
final class JudgeRoster {

    /** Juge à publier : ligne du plan, prénoms trouvés (ou null), source du nom et raison quand il manque. */
    record Judge(PlanJudges.PlanJudge plan, String firstName, String nameSource, String missing) {

        String fullName() {
            return firstName != null ? firstName + " " + plan.lastName() : plan.lastName();
        }
    }

    private JudgeRoster() {
    }

    static List<Judge> match(String court, List<PlanJudges.PlanJudge> plan, List<NamedJudges.NamedJudge> named) {
        List<Judge> out = new ArrayList<>();
        for (PlanJudges.PlanJudge p : plan) {
            List<String> firsts = new ArrayList<>();
            String source = null;
            for (NamedJudges.NamedJudge n : named) {
                if (!court.equals(n.court())) {
                    continue;
                }
                String first = firstNameFor(p.lastName(), n.tokens());
                if (first != null && (p.initial() == null || first.startsWith(p.initial().substring(0, 1))) && !firsts.contains(first)) {
                    // « Hans-Jörg » et « Hans-Jörg Michael » : le plus long garde les deux formes
                    String shorter = firsts.stream().filter(f -> first.startsWith(f + " ")).findFirst().orElse(null);
                    String longer = firsts.stream().filter(f -> f.startsWith(first + " ")).findFirst().orElse(null);
                    if (longer != null) {
                        continue;
                    }
                    if (shorter != null) {
                        firsts.remove(shorter);
                    }
                    firsts.add(first);
                    source = n.sourceUrl();
                }
            }
            if (firsts.size() == 1) {
                out.add(new Judge(p, firsts.get(0), source, null));
            } else {
                out.add(new Judge(p, null, null, firsts.isEmpty() ? "aucune publication officielle avec le prénom"
                        : "plusieurs prénoms possibles : " + String.join(", ", firsts)));
            }
        }
        // faute de frappe du plan (« Scheffzcyk » à côté de « Scheffczyk ») : le nom sans prénom est un doublon
        List<Judge> kept = new ArrayList<>();
        for (Judge j : out) {
            boolean typo = j.firstName() == null && out.stream().anyMatch(o -> o != j && o.firstName() != null
                    && distance(normalized(o.plan().lastName()), normalized(j.plan().lastName())) == 1
                    && normalized(o.plan().lastName()).length() >= 6);
            if (!typo) {
                kept.add(j);
            }
        }
        return kept;
    }

    /** Distance d'édition où l'inversion de deux lettres voisines compte pour une seule faute (« cz » / « zc »). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Prénoms qui précèdent le nom de famille dans les jetons, ou null si le nom n'y est pas après au moins un prénom. */
    static String firstNameFor(String lastName, List<String> tokens) {
        String[] last = lastName.split("\\s+");
        for (int k = 1; k + last.length <= tokens.size(); k++) {
            boolean same = true;
            for (int i = 0; i < last.length && same; i++) {
                same = normalized(last[i]).equals(normalized(tokens.get(k + i)));
            }
            if (same) {
                List<String> first = tokens.subList(0, k);
                if (first.stream().anyMatch(t -> NamedJudges.PARTICLE.matcher(t).matches())) {
                    return null;
                }
                return String.join(" ", first);
            }
        }
        return null;
    }

    private static String normalized(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
    }
}
