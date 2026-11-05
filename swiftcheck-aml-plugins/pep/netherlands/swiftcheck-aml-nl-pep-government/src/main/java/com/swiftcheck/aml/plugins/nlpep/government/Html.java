package com.swiftcheck.aml.plugins.nlpep.government;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Texte lisible à partir d'un fragment HTML : balises retirées, entités décodées (dont les fadas irlandais), espaces normalisés. */
final class Html {

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");
    /** « aacute », « Eacute », « uuml »… : lettre de base puis accent. */
    private static final Pattern ACCENTED = Pattern.compile("([a-zA-Z])(acute|grave|circ|uml|tilde|cedil)");
    private static final Map<String, Character> ACCENTS = Map.of("acute", '́', "grave", '̀', "circ", '̂',
            "uml", '̈', "tilde", '̃', "cedil", '̧');

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("hellip", "…"));

    private Html() {
    }

    static String text(String html) {
        if (html == null) {
            return "";
        }
        return decode(html.replaceAll("<[^>]+>", " ")).replace(' ', ' ').replaceAll("\\s+", " ").trim();
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
            } else if (NAMED.containsKey(e)) {
                value = NAMED.get(e);
            } else {
                Matcher a = ACCENTED.matcher(e);
                value = a.matches()
                        ? Normalizer.normalize(a.group(1) + ACCENTS.get(a.group(2)), Normalizer.Form.NFC) : m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
