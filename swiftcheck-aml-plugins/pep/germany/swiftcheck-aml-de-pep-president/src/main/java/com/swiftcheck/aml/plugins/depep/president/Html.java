package com.swiftcheck.aml.plugins.depep.president;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Html {

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("laquo", "«"), Map.entry("raquo", "»"),
            Map.entry("hellip", "…"), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("auml", "ä"), Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("szlig", "ß"),
            Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"), Map.entry("Uuml", "Ü"),
            Map.entry("eacute", "é"), Map.entry("egrave", "è"), Map.entry("ecirc", "ê"));

    private Html() {
    }

    static String text(String html) {
        if (html == null) {
            return "";
        }
        return decode(html.replaceAll("<[^>]+>", " ")).replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
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

