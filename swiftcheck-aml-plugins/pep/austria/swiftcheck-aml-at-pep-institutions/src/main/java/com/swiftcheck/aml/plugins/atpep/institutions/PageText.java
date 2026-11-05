package com.swiftcheck.aml.plugins.atpep.institutions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Texte d'une page web en lignes : un saut de ligne à chaque balise de bloc, aucun aux balises « en ligne ». */
final class PageText {

    private PageText() {
    }

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    private static final Map<String, String> NAMED = Map.ofEntries(Map.entry("nbsp", " "), Map.entry("amp", "&"),
            Map.entry("quot", "\""), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("apos", "'"),
            Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("hellip", "…"), Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("shy", ""),
            Map.entry("aacute", "á"), Map.entry("eacute", "é"), Map.entry("iacute", "í"), Map.entry("oacute", "ó"),
            Map.entry("uacute", "ú"), Map.entry("agrave", "à"), Map.entry("acirc", "â"), Map.entry("ecirc", "ê"),
            Map.entry("ocirc", "ô"), Map.entry("atilde", "ã"), Map.entry("otilde", "õ"), Map.entry("ccedil", "ç"),
            Map.entry("auml", "ä"), Map.entry("ouml", "ö"), Map.entry("uuml", "ü"), Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"), Map.entry("Uuml", "Ü"), Map.entry("szlig", "ß"),
            Map.entry("Aacute", "Á"), Map.entry("Eacute", "É"), Map.entry("Iacute", "Í"), Map.entry("Oacute", "Ó"),
            Map.entry("Uacute", "Ú"), Map.entry("Acirc", "Â"), Map.entry("Ecirc", "Ê"), Map.entry("Ocirc", "Ô"),
            Map.entry("Atilde", "Ã"), Map.entry("Otilde", "Õ"), Map.entry("Ccedil", "Ç"));

    /** Les lignes non vides d'une page, sans scripts, styles ni commentaires. */
    static List<String> lines(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<!--.*?-->|<noscript.*?</noscript>", " ");
        s = s.replaceAll("(?i)</?(p|div|li|h\\d|tr|td|th|section|article|ul|ol|table|figcaption|dd|dt|header|footer|nav|a|br)\\b[^>]*>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = decode(s);
        List<String> out = new ArrayList<>();
        for (String raw : s.split("\\R")) {
            String t = raw.replaceAll("[\\u200b\\u00a0\\u00ad]", " ").replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    static String decode(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            if (e.startsWith("#x")) {
                rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            } else if (e.startsWith("#")) {
                rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } else {
                rep = NAMED.getOrDefault(e, m.group());
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
