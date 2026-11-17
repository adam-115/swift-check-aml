package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lecture des listes Wikipédia DE « Liste der Richter am … » (BGH, BVerwG, BFH, BAG, BSG).
 * <p>
 * Les cours ne publient les noms de leurs juges que dans des plans de répartition (noms de famille seuls, sans
 * prénom ni date). Les listes Wikipédia donnent prénom, nom, année de naissance et dates d'entrée, de sortie, de
 * présidence de chambre, de vice-présidence et de présidence. Chaque ligne du tableau suit le modèle :
 * <pre>
 * |-
 * | {{PersonZelle|Prénom|Nom}} (* 1975)
 * | {{DatumZelle|2022-06-01}}      entrée
 * |                                sortie (vide = en fonction)
 * |                                présidence de chambre depuis
 * |                                vice-présidence depuis
 * |                                présidence depuis
 * </pre>
 * Les méthodes sont pures (texte en entrée) pour être testées sur des pages réelles.
 * <p>
 * Complément seulement : la liste des juges vient du plan officiel de la cour, et Wikipédia ne sert qu'à donner le
 * prénom d'un juge du plan qu'aucune publication officielle ne nomme ({@link #asNames}). Une personne absente du plan
 * n'est jamais publiée à partir de Wikipédia.
 */
final class WikipediaJudges {

    static final String SOURCE_PREFIX = "https://de.wikipedia.org/wiki/";

    /** Juges en fonction à la date donnée, sous forme de noms à rapprocher du plan. */
    static List<NamedJudges.NamedJudge> asNames(List<Judge> judges, String court, String page, LocalDate today) {
        List<NamedJudges.NamedJudge> out = new ArrayList<>();
        for (Judge j : judges) {
            if (j.keep(today, 0) && j.firstName() != null && !j.firstName().isBlank()) {
                out.add(new NamedJudges.NamedJudge(court, List.of(j.fullName().split("\\s+")), null, SOURCE_PREFIX + page));
            }
        }
        return out;
    }

    static boolean isWikipedia(String sourceUrl) {
        return sourceUrl != null && sourceUrl.startsWith(SOURCE_PREFIX);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PERSON = Pattern.compile("\\{\\{PersonZelle\\|([^|}]*)\\|([^|}]*)");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern BIRTH = Pattern.compile("\\(\\*\\s*(\\d{4})\\)");
    /** « (1943–2016) » ou « (1915–?) » : la personne est décédée. */
    private static final Pattern LIFE_SPAN = Pattern.compile("\\((\\d{4})\\s*[–-]\\s*(\\d{4}|\\?)\\)");

    private WikipediaJudges() {
    }

    /** Une ligne du tableau. */
    record Judge(String firstName, String lastName, Integer birthYear, boolean deceased, LocalDate start, LocalDate end,
                 LocalDate chairSince, LocalDate vicePresidentSince, LocalDate presidentSince) {

        String fullName() {
            return (firstName + " " + lastName).replaceAll("\\s+", " ").trim();
        }

        /** Fonction la plus haute atteinte, au libellé officiel allemand. */
        String role(String courtName) {
            // génitif : tous les noms de cours fédérales prennent un « s » (Bundesgerichtshofs, Bundesfinanzhofs…)
            if (presidentSince != null) {
                return "Präsident des " + courtName + "s";
            }
            if (vicePresidentSince != null) {
                return "Vizepräsident des " + courtName + "s";
            }
            if (chairSince != null) {
                return "Vorsitzender Richter am " + courtName;
            }
            return "Richter am " + courtName;
        }

        /** En fonction à la date donnée, ou sorti depuis moins de {@code months} mois ; jamais une personne décédée. */
        boolean keep(LocalDate today, int months) {
            if (deceased || start == null || start.isAfter(today)) {
                return false;
            }
            return end == null || !end.isBefore(today.minusMonths(months));
        }
    }

    /** Adresse de l'API MediaWiki qui renvoie le wikitext d'une page. */
    static String apiUrl(String page) {
        return "https://de.wikipedia.org/w/api.php?action=parse&prop=wikitext&format=json&redirects=1&page=" + page;
    }

    /** Extrait le wikitext d'une réponse de l'API MediaWiki. */
    static String wikitext(String apiResponse) throws IOException {
        JsonNode root = JSON.readTree(apiResponse);
        JsonNode text = root.path("parse").path("wikitext").path("*");
        if (text.isMissingNode() || !text.isTextual()) {
            throw new IOException("Réponse MediaWiki sans wikitext : " + root.path("error").path("info").asText("format inconnu"));
        }
        return text.asText();
    }

    /** Toutes les lignes du tableau qui portent un {@code {{PersonZelle}}}. */
    static List<Judge> parse(String wikitext) {
        List<Judge> judges = new ArrayList<>();
        for (String row : wikitext.split("\\n\\|-[^\\n]*")) {
            Matcher p = PERSON.matcher(row);
            if (!p.find()) {
                continue;
            }
            // cellules : [0] reste de la ligne « |- », [1] personne, [2] entrée, [3] sortie, [4] chambre, [5] VP, [6] président
            String[] cells = row.split("\\n\\|");
            String personCell = cells.length > 1 ? cells[1] : row;
            Matcher b = BIRTH.matcher(personCell);
            Integer birth = b.find() ? Integer.valueOf(b.group(1)) : null;
            Matcher life = LIFE_SPAN.matcher(personCell);
            boolean deceased = false;
            if (birth == null && life.find()) {
                birth = Integer.valueOf(life.group(1));
                deceased = true;
            }
            judges.add(new Judge(clean(p.group(1)), clean(p.group(2)), birth, deceased,
                    date(cells, 2), date(cells, 3), date(cells, 4), date(cells, 5), date(cells, 6)));
        }
        return judges;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static LocalDate date(String[] cells, int index) {
        if (index >= cells.length) {
            return null;
        }
        // une référence <ref> peut contenir d'autres dates : on ne lit que le début de la cellule
        String cell = cells[index];
        int ref = cell.indexOf("<ref");
        Matcher m = ISO_DATE.matcher(ref >= 0 ? cell.substring(0, ref) : cell);
        return m.find() ? LocalDate.parse(m.group(1)) : null;
    }

    /** Nom de famille comparable dans un plan officiel : dernier mot, sans particule ni accent décomposé. */
    static String comparableSurname(String lastName) {
        String[] words = lastName.trim().split("\\s+");
        return Normalizer.normalize(words[words.length - 1], Normalizer.Form.NFC);
    }

    /** Vrai si le nom de famille apparaît comme mot entier dans le texte du plan officiel. */
    static boolean inOfficialPlan(String lastName, String planText) {
        String surname = comparableSurname(lastName);
        if (surname.isEmpty()) {
            return false;
        }
        Pattern word = Pattern.compile("(?<![\\p{L}])" + Pattern.quote(surname) + "(?![\\p{L}])", Pattern.UNICODE_CASE);
        return word.matcher(Normalizer.normalize(planText, Normalizer.Form.NFC)).find();
    }

    static String normalizedName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
    }
}
