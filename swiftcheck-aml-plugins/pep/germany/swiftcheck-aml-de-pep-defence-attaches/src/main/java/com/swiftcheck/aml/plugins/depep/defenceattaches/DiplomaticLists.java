package com.swiftcheck.aml.plugins.depep.defenceattaches;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Attachés de défense allemands lus dans les listes diplomatiques officielles des pays d'accueil (« Diplomatic List »
 * publiée par le ministère des Affaires étrangères de chaque pays). L'Allemagne ne publie pas la liste de ses attachés :
 * ces listes sont la seule source officielle qui les nomme.
 * <p>
 * Dans chaque liste, on lit la section de l'ambassade d'Allemagne ({@code section}, puis {@code window} lignes) et les
 * lignes de fonction « Defence Attaché » (adjoints, assistants et bureau de l'attaché écartés). Le nom est sur la même
 * ligne (« Colonel Ralf HEIMRICH, Defence Attaché »), sur la ligne d'avant ou sur celle d'après selon la mise en page de
 * la liste ({@code nameAt} : {@code same}, {@code before}, {@code after}).
 */
final class DiplomaticLists {

    /** Une liste diplomatique (paramètre {@code lists}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListConfig(String host, String publisher, String url, String nameAt, String section, Integer window) {

        String nameAtOrDefault() {
            return nameAt == null ? "same" : nameAt;
        }
    }

    /** Attaché lu : nom, grade, pays d'accueil, ligne lue et adresse de la liste. */
    record Attache(String fullName, String firstName, String lastName, String rank, String host, String line, String sourceUrl) {
    }

    /** « GERMANY » seul en majuscules : un « Germany » isolé est souvent la fin d'une adresse d'une autre ambassade. */
    static final String DEFAULT_SECTION = "(?i:Embassy of (?:the )?Federal Republic of Germany)|^\\s*\\*?\\s*GERMANY\\s*$|(?i:Ambassade de la République fédérale d'Allemagne)";
    static final int DEFAULT_WINDOW = 90;

    private static final Pattern OTHER_EMBASSY = Pattern.compile("(?i)^(?:The\\s+)?(?:Embassy|High Commission|Ambassade|Botschaft)\\s+of\\b|^Ambassade\\s+d");
    private static final Pattern ATTACHE =Pattern.compile("(?i)\\bdefen[cs]e\\s+attach|attaché\\s+de\\s+défense|verteidigungsattach");
    private static final Pattern NOT_HEAD = Pattern.compile("(?i)assistant|deputy|adjoint|stellvertret|office of|\\boffice\\b|bureau|section|to defen[cs]e");
    private static final Pattern DATE = Pattern.compile("\\(?\\b\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}\\)?");
    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)");
    private static final Pattern RANK = Pattern.compile("^(?:(?:Brigadier|Major|Lieutenant|Lt\\.?|Rear|Vice|Flotilla)\\s+)?(?:General|Colonel|Col\\.?|Commander|Cdr\\.?|Captain|Capt\\.?|"
            + "Commodore|Admiral|Oberst(?:leutnant)?|Brigadegeneral|Generalmajor|Fregattenkapitän|Kapitän zur See|Flottillenadmiral|"
            + "LTC|COL|CDR|CAPT|BG|MG|BGen|LtCol|WO|SMS)\\b\\.?(?:\\s*\\((?:GS|N|Navy|AF|Army)\\))?(?:\\s+i\\.\\s?G\\.)?\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CIVILITY = Pattern.compile("^(?:His Excellency|Her Excellency|H\\.E\\.|Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_TOKEN = Pattern.compile("^\\p{Lu}[\\p{L}'’]*(?:-\\p{Lu}?[\\p{L}'’]+)*\\.?$|^(?:von|van|de|zu|der|den)$");
    /** Mots qui ne sont pas des noms (« Security », « Resident »). */
    private static final Pattern NOT_NAME = Pattern.compile("(?i)^(security|resident|residence|embassy|defen[cs]e|attach[ée]e?|military|naval|air|in|with|and|the|et|of)$");

    private DiplomaticLists() {
    }

    /** Texte d'une liste : PDF (trié par position), document OpenDocument (.odt) ou page HTML. */
    static String text(byte[] content) throws IOException {
        if (content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F') {
            try (PDDocument doc = PDDocument.load(content)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                return stripper.getText(doc);
            }
        }
        if (content.length > 2 && content[0] == 'P' && content[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.getName().equals("content.xml")) {
                        return xmlText(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            throw new IOException("archive sans content.xml (ni .odt ni .docx lisible)");
        }
        return xmlText(new String(content, StandardCharsets.UTF_8).replaceAll("(?is)<script.*?</script>|<style.*?</style>", " "));
    }

    /** Une ligne par paragraphe, ligne de tableau ou retour à la ligne ; les cellules d'une même ligne séparées par une espace. */
    private static String xmlText(String xml) {
        String s = xml.replaceAll("(?i)</(text:p|text:h|p|div|li|tr|h\\d|table:table-row)>|<br\\s*/?>|<text:line-break/>", "\n")
                .replaceAll("(?i)<text:tab/>|<text:s/>|</td>|</table:table-cell>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#160;", " ");
        return s;
    }

    /** Attachés de défense allemands d'une liste. */
    static List<Attache> attaches(String text, ListConfig list) {
        List<String> lines = Arrays.stream(text.split("\\R")).map(l -> l.replace(' ', ' ').replaceAll("[ \\t]+", " ").trim()).toList();
        Pattern section = Pattern.compile(list.section() == null ? DEFAULT_SECTION : list.section());
        int window = list.window() == null ? DEFAULT_WINDOW : list.window();
        List<Attache> out = new ArrayList<>();
        for (int s = 0; s < lines.size(); s++) {
            if (!section.matcher(lines.get(s)).find()) {
                continue;
            }
            for (int i = s; i < Math.min(lines.size(), s + window); i++) {
                String line = lines.get(i);
                // fin de la section : l'ambassade suivante
                if (i > s + 1 && OTHER_EMBASSY.matcher(line).find() && !section.matcher(line).find()) {
                    break;
                }
                Matcher k = ATTACHE.matcher(line);
                if (!k.find() || NOT_HEAD.matcher(line).find()) {
                    continue;
                }
                String raw = switch (list.nameAtOrDefault()) {
                    case "before" -> previous(lines, i);
                    case "after" -> next(lines, i);
                    default -> line.substring(0, k.start());
                };
                Attache a = attache(raw, list, line);
                if (a != null && out.stream().noneMatch(o -> o.fullName().equals(a.fullName()))) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    private static String previous(List<String> lines, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!lines.get(j).isBlank()) {
                return lines.get(j);
            }
        }
        return "";
    }

    private static String next(List<String> lines, int i) {
        for (int j = i + 1; j < lines.size(); j++) {
            if (!lines.get(j).isBlank()) {
                return lines.get(j);
            }
        }
        return "";
    }

    /** Nom et grade d'un fragment (« Colonel (GS) Andreas Bauer – », « Commander Michael Radtke 13.08.2025 »), ou null. */
    static Attache attache(String raw, ListConfig list, String line) {
        String s = DATE.matcher(PARENS.matcher(raw).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[_*]", " ").replaceAll("[,;:–—-]+\\s*$", "").replaceAll("\\s+[–—]\\s.*$", "").replaceAll("\\s+", " ").trim();
        s = s.replaceAll(",$", "").trim();
        String rank = null;
        for (int pass = 0; pass < 3; pass++) {
            Matcher c = CIVILITY.matcher(s);
            if (c.find()) {
                s = s.substring(c.end());
            }
            Matcher r = RANK.matcher(s);
            if (r.find() && r.end() > 0) {
                rank = (rank == null ? "" : rank + " ") + r.group().trim();
                s = s.substring(r.end());
            }
        }
        // « Colonel (GS) Andreas Bauer » : un « (GS) » isolé après le grade
        s = s.replaceAll("^\\(?GS\\)?\\s+", "").replaceAll("\\s+[Mm]\\s*$", "").trim();
        String[] tokens = s.split(" ");
        List<String> names = new ArrayList<>();
        for (String t : tokens) {
            if (NAME_TOKEN.matcher(t).matches() && !NOT_NAME.matcher(t).matches()) {
                names.add(t.replaceAll("\\.$", ""));
            } else {
                break;
            }
        }
        if (names.size() < 2) {
            return null;
        }
        List<String> cased = names.stream().map(DiplomaticLists::properCase).toList();
        int last = cased.size() - 1;
        while (last > 1 && cased.get(last - 1).matches("von|van|de|zu|der|den")) {
            last--;
        }
        String first = String.join(" ", cased.subList(0, last));
        String lastName = String.join(" ", cased.subList(last, cased.size()));
        return new Attache(first + " " + lastName, first, lastName, rank, list.host(), line, list.url());
    }

    /** « HEIMRICH » donne « Heimrich », « KREUTZBERG-KOWALCZYK » donne « Kreutzberg-Kowalczyk » ; les autres mots restent. */
    static String properCase(String w) {
        if (!w.equals(w.toUpperCase(Locale.ROOT)) || w.length() < 2) {
            return w;
        }
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (char ch : w.toCharArray()) {
            sb.append(start ? ch : Character.toLowerCase(ch));
            start = ch == '-' || ch == '\'';
        }
        return sb.toString();
    }
}
