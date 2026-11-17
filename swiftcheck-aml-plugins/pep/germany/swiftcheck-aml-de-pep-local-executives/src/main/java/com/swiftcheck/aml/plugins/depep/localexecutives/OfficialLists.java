package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Listes officielles des exécutifs locaux :
 * <ul>
 * <li>Landräte : page « Landkreise und Landräte » du Deutscher Landkreistag, union fédérale des 294 Landkreise
 * (tableau Landkreis / Landrat, un titre par Land) ;</li>
 * <li>population des communes : répertoire des communes de Destatis (Gemeindeverzeichnis, classeur trimestriel) ;</li>
 * <li>maires : listes publiées par chaque Land (offices statistiques), décrites dans le paramètre {@code mayorSources}.</li>
 * </ul>
 * Les méthodes sont pures (contenu en entrée) pour être testées sur les fichiers réels.
 */
final class OfficialLists {

    /** Chef de l'exécutif d'une collectivité. */
    record Executive(String land, String key, String place, String type, long population, String role, String firstName,
                     String lastName, String title, String gender, String birthYear, String party, String start, String sourceUrl,
                     String note) {

        /** Exécutif lu dans une source officielle (sans note de source). */
        Executive(String land, String key, String place, String type, long population, String role, String firstName,
                  String lastName, String title, String gender, String birthYear, String party, String start, String sourceUrl) {
            this(land, key, place, type, population, role, firstName, lastName, title, gender, birthYear, party, start, sourceUrl, null);
        }

        String fullName() {
            return firstName == null || firstName.isBlank() ? lastName : firstName + " " + lastName;
        }
    }

    /** Commune du répertoire Destatis : clé AGS à 8 chiffres, nom, population. */
    record Municipality(String ags, String name, long population) {

        /** « München, Landeshauptstadt » donne « München » ; « Flensburg, Stadt » donne « Flensburg ». */
        String shortName() {
            int comma = name.indexOf(',');
            return comma > 0 ? name.substring(0, comma).trim() : name.trim();
        }

        String land() {
            return LAENDER.getOrDefault(ags.substring(0, 2), ags.substring(0, 2));
        }
    }

    /**
     * Liste officielle des maires d'un Land (paramètre {@code mayorSources}). Format « xlsx » : {@code sheet},
     * {@code agsPrefix}, {@code columns}, {@code nameOrder}, {@code roles}. Format « perCity » : {@code url} avec
     * {@code {ags6}} ou {@code {ags8}}, {@code elected} (motif : nom, parti, date de l'élection) et {@code roleFrom}
     * (motif dont le groupe 1 donne la fonction ; à défaut « Bürgermeister ») et {@code keyAliases} (clé AGS vers la clé
     * propre au site, quand elles diffèrent).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MayorSource(String land, String publisher, String url, String format, String sheet, String agsPrefix,
                       Map<String, String> columns, String nameOrder, String roles, String elected, String roleFrom,
                       Map<String, String> keyAliases) {
    }

    /**
     * Élu d'un fichier de résultats (« Bei der Stichwahl am 28.09.2025 gewählt: Burmester, Torsten (SPD) 28.09.2025: … »),
     * ou null si le fichier n'en donne pas.
     */
    static Executive electedMayor(String text, String ags, MayorSource source, String url) {
        Matcher m = Pattern.compile(source.elected()).matcher(text);
        if (!m.find()) {
            return null;
        }
        // « Voß, Karola (Maria) » : le complément entre parenthèses n'est pas gardé
        String name = m.group(1).replaceAll("\\s*\\([^)]*\\)", "").trim();
        String[] parts = "lastFirst".equals(source.nameOrder()) ? splitLastFirst(name) : splitFirstLast(name);
        String role = "Bürgermeister";
        if (source.roleFrom() != null) {
            Matcher r = Pattern.compile(source.roleFrom()).matcher(text);
            if (r.find()) {
                role = r.group(1);
            }
        }
        String date = m.groupCount() >= 3 ? m.group(3) : null;
        String iso = date != null && date.matches("\\d{2}\\.\\d{2}\\.\\d{4}")
                ? date.substring(6) + "-" + date.substring(3, 5) + "-" + date.substring(0, 2) : null;
        return new Executive(source.land(), ags, null, "Gemeinde", 0, role, parts[1], parts[2], parts[0], null, null,
                m.groupCount() >= 2 ? clean(m.group(2)) : null, iso, url);
    }

    static final Map<String, String> LAENDER = Map.ofEntries(Map.entry("01", "SH"), Map.entry("02", "HH"), Map.entry("03", "NI"),
            Map.entry("04", "HB"), Map.entry("05", "NW"), Map.entry("06", "HE"), Map.entry("07", "RP"), Map.entry("08", "BW"),
            Map.entry("09", "BY"), Map.entry("10", "SL"), Map.entry("11", "BE"), Map.entry("12", "BB"), Map.entry("13", "MV"),
            Map.entry("14", "SN"), Map.entry("15", "ST"), Map.entry("16", "TH"));
    private static final Map<String, String> LAND_NAMES = Map.ofEntries(Map.entry("Baden-Württemberg", "BW"),
            Map.entry("Bayern", "BY"), Map.entry("Brandenburg", "BB"), Map.entry("Hessen", "HE"), Map.entry("Mecklenburg-Vorpommern", "MV"),
            Map.entry("Niedersachsen", "NI"), Map.entry("Nordrhein-Westfalen", "NW"), Map.entry("Rheinland-Pfalz", "RP"),
            Map.entry("Saarland", "SL"), Map.entry("Sachsen", "SN"), Map.entry("Sachsen-Anhalt", "ST"),
            Map.entry("Schleswig-Holstein", "SH"), Map.entry("Thüringen", "TH"));

    private static final Pattern TITLE = Pattern.compile("^(?:(?:Prof\\.|Dr\\.|Dr\\.-Ing\\.|h\\.\\s?c\\.|mult\\.)\\s*)+");
    private static final Pattern PARTICLE = Pattern.compile("^(von|van|de|zu|vom|der|den|dem)$");
    private static final Pattern H5 = Pattern.compile("(?is)<h5[^>]*>(.*?)</h5>");
    private static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern TD = Pattern.compile("(?is)<td[^>]*>(.*?)</td>");

    private OfficialLists() {
    }

    // ------------------------------------------------------------------ Landräte

    /**
     * Landräte du tableau du Deutscher Landkreistag : une ligne par Landkreis (nom, Landrat), le Land vient du dernier titre
     * {@code <h5>} rencontré.
     *
     * @param roles titre de l'exécutif quand il n'est pas « Landrat » (« Region Hannover » donne « Regionspräsident »)
     */
    static List<Executive> landraete(String html, String sourceUrl, Map<String, String> roles) {
        List<Executive> out = new ArrayList<>();
        String land = null;
        Matcher tr = TR.matcher(html);
        while (tr.find()) {
            String row = tr.group(1);
            Matcher h5 = H5.matcher(row);
            if (h5.find()) {
                land = LAND_NAMES.get(text(h5.group(1)));
                continue;
            }
            List<String> cells = new ArrayList<>();
            Matcher td = TD.matcher(row);
            while (td.find()) {
                cells.add(text(td.group(1)));
            }
            if (cells.size() < 2 || cells.get(0).isBlank() || cells.get(1).isBlank() || cells.get(0).equals("Landkreis")) {
                continue;
            }
            String district = cells.get(0);
            String name = cells.get(1).replaceAll(",\\s*(MdL|MdB|MdEP)\\b.*$", "").trim();
            String[] parts = splitFirstLast(name);
            String role = roles == null ? null : roles.get(district);
            out.add(new Executive(land, null, district, "Landkreis", 0, role == null ? "Landrat" : role, parts[1], parts[2], parts[0],
                    null, null, null, null, sourceUrl));
        }
        return out;
    }

    // ------------------------------------------------------------------ Destatis

    /**
     * Communes du répertoire Destatis (feuille des communes) : lignes de « Satzart » 60, clé AGS = Land (C) + Regierungsbezirk
     * (D) + Kreis (E) + Gemeinde (G), nom (H), population (J).
     */
    static Map<String, Municipality> municipalities(byte[] xlsx) throws IOException {
        Map<String, Municipality> out = new LinkedHashMap<>();
        for (Map<String, String> row : Xlsx.rows(xlsx, "^Onlineprodukt")) {
            if (!"60".equals(row.get("A")) || row.get("J") == null) {
                continue;
            }
            String ags = row.getOrDefault("C", "") + row.getOrDefault("D", "") + row.getOrDefault("E", "") + row.getOrDefault("G", "");
            if (!ags.matches("\\d{8}")) {
                continue;
            }
            try {
                out.put(ags, new Municipality(ags, row.get("H"), Long.parseLong(row.get("J").replaceAll("\\D", ""))));
            } catch (NumberFormatException e) {
                // ligne de total ou valeur absente
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ maires

    /**
     * Maires d'une liste de Land au format classeur : la ligne d'en-tête est celle qui contient les libellés de
     * {@code columns}; les lignes dont la fonction correspond à {@code roles} sont gardées.
     */
    static List<Executive> mayorsFromXlsx(byte[] xlsx, MayorSource source) throws IOException {
        List<Map<String, String>> rows = Xlsx.rows(xlsx, source.sheet());
        Map<String, String> letters = null;
        List<Executive> out = new ArrayList<>();
        Pattern roles = source.roles() == null ? null : Pattern.compile(source.roles());
        for (Map<String, String> row : rows) {
            if (letters == null) {
                letters = headerLetters(row, source.columns());
                continue;
            }
            String role = clean(row.get(letters.get("role")));
            if (role == null || (roles != null && !roles.matcher(role).find())) {
                continue;
            }
            String key = clean(row.get(letters.get("key")));
            String name = clean(row.get(letters.get("name")));
            if (key == null || name == null) {
                continue;
            }
            String ags = (source.agsPrefix() == null ? "" : source.agsPrefix()) + key.replaceAll("\\D", "");
            String[] parts = "lastFirst".equals(source.nameOrder()) ? splitLastFirst(name) : splitFirstLast(name);
            String gender = clean(row.get(letters.get("gender")));
            String start = clean(row.get(letters.get("start")));
            out.add(new Executive(source.land(), ags, clean(row.get(letters.get("place"))), "Gemeinde", 0, role, parts[1], parts[2],
                    parts[0], gender == null ? null : gender.toLowerCase(Locale.ROOT).startsWith("w") ? "female" : "male",
                    clean(row.get(letters.get("birthYear"))), clean(row.get(letters.get("party"))),
                    start == null ? null : start.matches("\\d+(\\.\\d+)?") ? Xlsx.excelDate(start) : start, source.url()));
        }
        if (letters == null) {
            throw new IOException("en-tête introuvable (" + source.columns().values() + ")");
        }
        return out;
    }

    /** Lettres des colonnes de la ligne d'en-tête, ou null si la ligne n'est pas l'en-tête. */
    private static Map<String, String> headerLetters(Map<String, String> row, Map<String, String> columns) {
        Map<String, String> byLabel = new HashMap<>();
        row.forEach((letter, label) -> byLabel.put(normalized(label), letter));
        Map<String, String> letters = new HashMap<>();
        for (Map.Entry<String, String> c : columns.entrySet()) {
            String letter = byLabel.get(normalized(c.getValue()));
            if (letter == null) {
                return null;
            }
            letters.put(c.getKey(), letter);
        }
        return letters;
    }

    private static String normalized(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ noms

    /** « Dr. Christian Ante » : titre, prénoms, nom (particules comprises : « Arne von Boetticher »). */
    static String[] splitFirstLast(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        String title = null;
        Matcher t = TITLE.matcher(s);
        if (t.find()) {
            title = t.group().trim();
            s = s.substring(t.end()).trim();
        }
        String[] tokens = s.split(" ");
        int last = tokens.length - 1;
        while (last > 1 && PARTICLE.matcher(tokens[last - 1]).matches()) {
            last--;
        }
        String first = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, last));
        String lastName = String.join(" ", java.util.Arrays.copyOfRange(tokens, last, tokens.length));
        return new String[] {title, first.isBlank() ? null : first, lastName};
    }

    /**
     * « Kern Dr. Michael », « Kriegl, Gisela », « von Dobschütz Dr. Christian » : nom, titre éventuel, prénoms. Sans
     * virgule ni titre, le nom est le premier mot (avec ses particules).
     */
    static String[] splitLastFirst(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        String lastName;
        String rest;
        int comma = s.indexOf(',');
        Matcher title = Pattern.compile("\\s((?:Prof\\.|Dr\\.|Dr\\.-Ing\\.)(?:\\s(?:Prof\\.|Dr\\.|h\\.\\s?c\\.))*)\\s").matcher(s);
        String titleText = null;
        if (comma > 0) {
            lastName = s.substring(0, comma).trim();
            rest = s.substring(comma + 1).trim();
        } else if (title.find()) {
            lastName = s.substring(0, title.start()).trim();
            titleText = title.group(1);
            rest = s.substring(title.end()).trim();
        } else {
            String[] tokens = s.split(" ");
            int i = 0;
            while (i < tokens.length - 1 && PARTICLE.matcher(tokens[i]).matches()) {
                i++;
            }
            lastName = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, i + 1));
            rest = String.join(" ", java.util.Arrays.copyOfRange(tokens, i + 1, tokens.length));
        }
        Matcher t = TITLE.matcher(rest);
        if (titleText == null && t.find()) {
            titleText = t.group().trim();
            rest = rest.substring(t.end()).trim();
        }
        return new String[] {titleText, rest.isBlank() ? null : rest, lastName};
    }

    private static String text(String html) {
        return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() || t.equals("-") ? null : t;
    }
}
