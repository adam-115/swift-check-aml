package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Landräte des 294 Landkreise allemands, lus dans Wikipédia DE : la page « Liste der Landkreise in Deutschland » donne les
 * articles, et l'infobox de chaque article donne le chef de l'exécutif (champ {@code Landrat} ou {@code Landrätin} ;
 * {@code Organwalter} et {@code Organwaltertitel} pour la Städteregion Aachen et la Region Hannover) et son parti.
 * Wikidata ne renseigne le chef de l'exécutif que pour une vingtaine d'arrondissements : il ne suffit pas ici.
 * Les méthodes sont pures (texte en entrée) pour être testées sur des pages réelles.
 */
final class LandkreisInfoboxes {

    static final String LIST_PAGE = "Liste_der_Landkreise_in_Deutschland";
    static final String API = "https://de.wikipedia.org/w/api.php";
    /** Nombre maximal de titres par appel à l'API MediaWiki. */
    static final int BATCH = 50;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEAD = Pattern.compile(
            "\\n[ \\t]*\\|[ \\t]*(Landrat|Landrätin|Organwalter)[ \\t]*=[ \\t]*([^\\n]*)");
    // [ \t] et non \s : un champ vide ne doit pas prendre la ligne suivante
    private static final Pattern HEAD_TITLE = Pattern.compile("\\n[ \\t]*\\|[ \\t]*Organwaltertitel[ \\t]*=[ \\t]*([^\\n]*)");
    private static final Pattern PARTY = Pattern.compile("\\n[ \\t]*\\|[ \\t]*Partei[ \\t]*=[ \\t]*([^\\n]*)");
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|#]+)(?:\\|([^\\]]*))?]]");

    private LandkreisInfoboxes() {
    }

    /** Chef de l'exécutif d'un arrondissement. */
    record Head(String district, String role, String name, String party) {
    }

    static String listUrl() {
        return API + "?action=parse&prop=wikitext&format=json&redirects=1&page=" + LIST_PAGE;
    }

    static String articlesUrl(List<String> titles) {
        return API + "?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&redirects=1&titles="
                + URLEncoder.encode(String.join("|", titles), StandardCharsets.UTF_8);
    }

    /** Titres des articles des Landkreise : deuxième colonne des lignes qui commencent par un code à 5 chiffres. */
    static List<String> districtTitles(String listApiResponse) throws IOException {
        String wikitext = JSON.readTree(listApiResponse).path("parse").path("wikitext").path("*").asText("");
        List<String> titles = new ArrayList<>();
        for (String row : wikitext.split("\\n\\|-[^\\n]*")) {
            String[] cells = row.split("\\n\\|");
            if (cells.length < 3 || !cells[1].trim().matches("\\d{5}.*")) {
                continue;
            }
            Matcher m = LINK.matcher(cells[2]);
            if (m.find()) {
                titles.add(m.group(1).trim());
            }
        }
        return titles;
    }

    /** Wikitext de chaque article d'une réponse {@code prop=revisions}, par titre. */
    static Map<String, String> articles(String queryApiResponse) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode page : JSON.readTree(queryApiResponse).path("query").path("pages")) {
            JsonNode content = page.path("revisions").path(0).path("slots").path("main").path("*");
            out.put(page.path("title").asText(), content.asText(""));
        }
        return out;
    }

    /** Chef de l'exécutif lu dans l'infobox, ou null si le champ est absent ou vide. */
    static Head head(String district, String wikitext) {
        Matcher m = HEAD.matcher(wikitext);
        if (!m.find()) {
            return null;
        }
        String name = clean(m.group(2));
        if (name.isEmpty()) {
            return null;
        }
        String role = m.group(1).equals("Organwalter") ? organTitle(wikitext) : m.group(1);
        Matcher p = PARTY.matcher(wikitext);
        String party = p.find() ? clean(p.group(1)) : null;
        return new Head(district, role, name, party == null || party.isEmpty() ? null : party);
    }

    private static String organTitle(String wikitext) {
        Matcher t = HEAD_TITLE.matcher(wikitext);
        String title = t.find() ? clean(t.group(1)) : "";
        return title.isEmpty() ? "Landrat" : title;
    }

    /**
     * Valeur lisible d'un champ d'infobox : sans références, avec le texte affiché des liens
     * ({@code [[Markus Müller (Politiker)|Markus Müller]]} donne « Markus Müller ») et sans précision entre parenthèses.
     */
    static String clean(String raw) {
        String s = raw.replace("{{!}}", "|")
                .replaceAll("(?s)<ref[^>]*/>", "")
                .replaceAll("(?s)<ref[^>]*>.*?</ref>", "")
                .replaceAll("<ref.*$", "")
                .replaceAll("<[^>]+>", " ");
        Matcher m = LINK.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(2) != null ? m.group(2) : m.group(1)));
        }
        m.appendTail(sb);
        s = sb.toString().replaceAll("\\{\\{[^}]*}}", "");
        // « Ludwig Schmid (Politiker, 1977)|Ludwig Schmid » (lien écrit avec {{!}}) : garder le texte affiché
        if (s.contains("|")) {
            s = s.substring(s.lastIndexOf('|') + 1);
        }
        return s.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("[\\[\\]']", "").replaceAll("\\s+", " ").trim();
    }
}
