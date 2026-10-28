package com.swiftcheck.aml.plugins.frpep.banquedefrance;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Texte lisible à partir d'un fragment HTML : balises retirées, entités décodées, espaces normalisés. */
final class Html {

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    /** Entités nommées utilisées par les sites officiels français (accents, apostrophes, guillemets). */
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("hellip", "…"), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("eacute", "é"), Map.entry("egrave", "è"), Map.entry("ecirc", "ê"), Map.entry("euml", "ë"),
            Map.entry("agrave", "à"), Map.entry("acirc", "â"), Map.entry("auml", "ä"), Map.entry("ccedil", "ç"),
            Map.entry("icirc", "î"), Map.entry("iuml", "ï"), Map.entry("ocirc", "ô"), Map.entry("ouml", "ö"),
            Map.entry("ugrave", "ù"), Map.entry("ucirc", "û"), Map.entry("uuml", "ü"), Map.entry("oelig", "œ"),
            Map.entry("Eacute", "É"), Map.entry("Egrave", "È"), Map.entry("Ecirc", "Ê"), Map.entry("Agrave", "À"),
            Map.entry("Acirc", "Â"), Map.entry("Ccedil", "Ç"), Map.entry("Icirc", "Î"), Map.entry("Ocirc", "Ô"),
            Map.entry("OElig", "Œ"));

    private Html() {
    }

    static String text(String html) {
        if (html == null) {
            return "";
        }
        return decode(html.replaceAll("<[^>]+>", " ")).replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    static String decode(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String value;
            if (e.startsWith("#x")) {
                value = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            } else if (e.startsWith("#")) {
                value = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } else {
                value = NAMED.getOrDefault(e, m.group());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
