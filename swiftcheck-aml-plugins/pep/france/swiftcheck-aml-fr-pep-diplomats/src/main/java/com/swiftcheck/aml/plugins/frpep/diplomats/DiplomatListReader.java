package com.swiftcheck.aml.plugins.frpep.diplomats;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Lit les deux listes PDF du ministère de l'Europe et des Affaires étrangères.
 *
 * <p><b>Liste des chefs de poste</b> (ambassadeurs et représentants permanents), une ligne par poste :
 * <pre>
 * Allemagne BERLIN M. DELATTRE François 20 septembre 2022 DUE
 * Belgique RP UE BRUXELLES M. LEGLISE-COSTA Philippe 19 novembre 2017 DUE
 * Mali BAMAKO M. FOURRIERE Thibaut (chargé d'affaires) 15 septembre 2025 DAOI
 * Pologne VARSOVIE M. de MONTAIGNE de PONCINS Etienne 20 août 2023 DUE
 * Afghanistan KABOUL AS                         (poste sans chef : vacant)
 * </pre>
 * Pays, poste (en majuscules ; « RP … » pour une représentation permanente), civilité, NOM Prénom, date de prise de
 * fonctions, direction géographique.
 *
 * <p><b>Liste des ambassadeurs thématiques</b> nommés en Conseil des ministres :
 * <pre>
 * Mme AMPROU Anne-Claire 01/11/2022 Ambassadrice pour la santé mondiale
 * M. FARACO Benoît 23/05/2025
 * Ambassadeur chargé des négociations sur le changement climatique pour les
 * énergies renouvelables et la prévention des risques climatiques
 * </pre>
 * La fonction peut continuer sur les lignes suivantes.
 */
final class DiplomatListReader {

    enum Kind { AMBASSADOR, PERMANENT_REPRESENTATIVE, CHARGE_D_AFFAIRES, THEMATIC }

    /**
     * Un diplomate de l'une des listes.
     *
     * @param kind      ambassadeur, représentant permanent, chargé d'affaires ou ambassadeur thématique
     * @param civility  « M. » ou « Mme »
     * @param lastName  nom (« DE RIVIERE », « de MONTAIGNE de PONCINS »)
     * @param firstName prénom(s)
     * @param country   pays (vide pour un poste sans pays : « JERUSALEM (FSLT) ») ; vide pour un thématique
     * @param post      poste tel qu'écrit (« BERLIN », « RP ONU NEW YORK ») ; fonction pour un thématique
     * @param start     date de prise de fonctions (thématique : date de nomination en Conseil des ministres)
     * @param line      ligne d'origine, pour les messages
     */
    record Diplomat(Kind kind, String civility, String lastName, String firstName, String country, String post,
            LocalDate start, String line) {

        String fullName() {
            return firstName.isEmpty() ? lastName : firstName + " " + lastName;
        }
    }

    /** Contenu d'une liste : les diplomates, la date de mise à jour, les postes sans titulaire. */
    record DiplomatList(List<Diplomat> diplomats, LocalDate updated, List<String> vacant, List<String> unread) {
    }

    private static final String MONTHS = "janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre"
            + "|octobre|novembre|décembre|decembre";
    private static final Pattern HEAD_OF_POST = Pattern.compile("^(.*?)\\s*\\b(M\\.|Mme)\\s+(.+?)\\s*"
            + "(\\(chargée? d'affaires\\))?\\s+(\\d{1,2})(?:er)?\\s+(" + MONTHS + ")\\s+(\\d{4})(?:\\s+([A-Z][A-Z-]*))?\\s*$");
    /** Poste sans titulaire : se termine par le code de la direction géographique (« Afghanistan KABOUL AS »). */
    private static final Pattern VACANT = Pattern.compile("^(.+?)\\s+([A-Z]{2,5}(?:-[A-Z]{2,5})?)\\s*$");
    private static final Pattern UPDATED = Pattern.compile("mise à jour\\s*:\\s*(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern THEMATIC = Pattern.compile("^(M\\.|Mme)\\s+(.+?)\\s+(\\d{1,2})/(\\d{1,2})/(\\d{4})\\s*(.*)$");
    private static final Pattern THEMATIC_END = Pattern.compile(
            "^(?:AMBASSADEURS ET AMBASSADRICES|Nom et prénom|\\d{1,2}/\\d{1,2}/\\d{4}$).*");
    private static final Set<String> HEADER_WORDS = Set.of("liste des chefs", "date de mise", "pays poste",
            "date de prise", "de fonctions", "direction", "géographique");
    private static final Set<String> PARTICLES = Set.of("de", "du", "des", "d'", "le", "la", "van", "von", "da", "di");
    private static final Map<String, Integer> MONTH_NUMBERS = Map.ofEntries(Map.entry("janvier", 1),
            Map.entry("février", 2), Map.entry("fevrier", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("août", 8),
            Map.entry("aout", 8), Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11),
            Map.entry("décembre", 12), Map.entry("decembre", 12));

    private DiplomatListReader() {
    }

    /** Texte d'un PDF, une ligne par ligne du document. */
    static List<String> pdfLines(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            List<String> lines = new ArrayList<>();
            for (String line : new PDFTextStripper().getText(doc).split("\\R")) {
                String l = line.replaceAll("[\\p{Z}\\s]+", " ").trim();
                if (!l.isEmpty()) {
                    lines.add(l);
                }
            }
            return lines;
        }
    }

    /** Liste des chefs de poste. */
    static DiplomatList readHeadsOfPost(List<String> lines) {
        List<Diplomat> diplomats = new ArrayList<>();
        List<String> vacant = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        LocalDate updated = null;
        for (String line : lines) {
            Matcher u = UPDATED.matcher(line);
            if (u.find()) {
                updated = date(u.group(1), u.group(2), u.group(3));
                continue;
            }
            Matcher m = HEAD_OF_POST.matcher(line);
            if (m.matches()) {
                String[] place = splitPlace(m.group(1));
                String[] name = splitName(m.group(3));
                Kind kind = m.group(4) != null ? Kind.CHARGE_D_AFFAIRES
                        : place[1].startsWith("RP ") ? Kind.PERMANENT_REPRESENTATIVE : Kind.AMBASSADOR;
                diplomats.add(new Diplomat(kind, m.group(2), name[0], name[1], place[0], place[1],
                        LocalDate.of(Integer.parseInt(m.group(7)), MONTH_NUMBERS.get(m.group(6)),
                                Integer.parseInt(m.group(5))), line));
            } else if (isHeader(line)) {
                continue;
            } else if (VACANT.matcher(line).matches() && !line.matches(".*\\b(?:M\\.|Mme)\\s.*")) {
                vacant.add(line);
            } else {
                unread.add(line);
            }
        }
        return new DiplomatList(diplomats, updated, vacant, unread);
    }

    /** Liste des ambassadeurs thématiques ; la date de mise à jour est la date seule en bas de page. */
    static DiplomatList readThematic(List<String> lines) {
        List<Diplomat> diplomats = new ArrayList<>();
        List<String> unread = new ArrayList<>();
        LocalDate updated = null;
        String[] pending = null;        // civilité, nom, date, fonction en cours
        StringBuilder function = null;
        for (String line : lines) {
            Matcher m = THEMATIC.matcher(line);
            boolean end = THEMATIC_END.matcher(line).matches();
            if (m.matches() || end) {
                if (pending != null) {
                    diplomats.add(thematic(pending, function.toString()));
                    pending = null;
                }
                if (m.matches()) {
                    pending = new String[] { m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), line };
                    function = new StringBuilder(m.group(6));
                } else if (line.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                    String[] d = line.split("/");
                    updated = date(d[0], d[1], d[2]);
                }
            } else if (pending != null) {
                function.append(function.length() > 0 ? " " : "").append(line);
            } else if (!line.startsWith("PAGES")) {
                unread.add(line);
            }
        }
        if (pending != null) {
            diplomats.add(thematic(pending, function.toString()));
        }
        return new DiplomatList(diplomats, updated, List.of(), unread);
    }

    private static Diplomat thematic(String[] p, String function) {
        String[] name = splitName(p[1]);
        return new Diplomat(Kind.THEMATIC, p[0], name[0], name[1], "", function.trim(),
                date(p[2], p[3], p[4]), p[5]);
    }

    /**
     * « Belgique RP UE BRUXELLES » → {« Belgique », « RP UE BRUXELLES »} : le poste commence au premier mot en
     * majuscules. « JERUSALEM (FSLT) » n'a pas de pays.
     */
    static String[] splitPlace(String place) {
        String[] words = place.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            if (isUpperWord(words[i])) {
                return new String[] { String.join(" ", java.util.Arrays.copyOf(words, i)).trim(),
                        String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)) };
            }
        }
        return new String[] { place.trim(), "" };
    }

    /**
     * « DE RIVIERE Nicolas » → {« DE RIVIERE », « Nicolas »} ; « de MONTAIGNE de PONCINS Etienne » →
     * {« de MONTAIGNE de PONCINS », « Etienne »} : le nom est fait des mots en majuscules et des particules qui les
     * précèdent ; le reste est le prénom.
     */
    static String[] splitName(String name) {
        String[] words = name.trim().split("\\s+");
        int i = 0;
        while (i < words.length) {
            String w = words[i];
            boolean particleBeforeUpper = PARTICLES.contains(w.toLowerCase(Locale.FRENCH))
                    && i + 1 < words.length && isUpperWord(words[i + 1]);
            if (!isUpperWord(w) && !particleBeforeUpper) {
                break;
            }
            i++;
        }
        if (i == 0) {
            i = words.length - 1 > 0 ? words.length - 1 : words.length;   // pas de majuscules : dernier mot = nom
            return new String[] { String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)),
                    String.join(" ", java.util.Arrays.copyOf(words, i)) };
        }
        return new String[] { String.join(" ", java.util.Arrays.copyOf(words, i)),
                String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)) };
    }

    /** Mot en majuscules d'au moins deux lettres (« BERLIN », « D'HAUSSONVILLE », « RP »). */
    private static boolean isUpperWord(String word) {
        String letters = word.replaceAll("[^\\p{L}]", "");
        return letters.length() >= 2 && letters.equals(letters.toUpperCase(Locale.FRENCH));
    }

    private static boolean isHeader(String line) {
        String l = line.toLowerCase(Locale.FRENCH);
        return HEADER_WORDS.stream().anyMatch(l::startsWith) || l.startsWith("pages ");
    }

    private static LocalDate date(String day, String month, String year) {
        return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
    }
}
