package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texte d'une page HTML, une ligne par paragraphe, titre, cellule ou élément de liste : les motifs de
 * {@code international-orgs.json} s'écrivent sur ce texte (« Nom » puis « Fonction » sur la ligne suivante, par
 * exemple). Le titre de la page ({@code <title>}) est gardé : certaines pages n'y écrivent le nom qu'une fois.
 */
final class PageText {

    private static final Pattern DROP = Pattern.compile(
            "(?is)<(script|style|noscript|svg|iframe)\\b.*?</\\1>|<!--.*?-->");
    private static final Pattern BLOCK = Pattern.compile(
            "(?i)<br\\s*/?>|</?(?:p|div|li|ul|ol|h[1-6]|tr|td|th|dt|dd|section|article|header|footer|figcaption|title"
                    + "|span|a|strong|em|b|blockquote|table|label|button|nav)\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    private PageText() {
    }

    static String of(String html) {
        String text = TAG.matcher(BLOCK.matcher(DROP.matcher(html).replaceAll(" ")).replaceAll("\n")).replaceAll(" ");
        List<String> lines = new ArrayList<>();
        for (String line : decode(text).split("\n")) {
            String l = line.replaceAll("[\\p{Z}\\s]+", " ").trim();
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        return String.join("\n", lines);
    }

    private static String decode(String s) {
        StringBuilder out = new StringBuilder();
        Matcher m = ENTITY.matcher(s);
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "rsquo", "lsquo" -> "’";
                case "eacute" -> "é";
                case "egrave" -> "è";
                case "agrave" -> "à";
                case "ccedil" -> "ç";
                case "ndash" -> "–";
                case "raquo" -> "»";
                case "laquo" -> "«";
                default -> e.startsWith("#") ? new String(Character.toChars(e.startsWith("#x")
                        ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1)))) : m.group();
            };
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }
}
