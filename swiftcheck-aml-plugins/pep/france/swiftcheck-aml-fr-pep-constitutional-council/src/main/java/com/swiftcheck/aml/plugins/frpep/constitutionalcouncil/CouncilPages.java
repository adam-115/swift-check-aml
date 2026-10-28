package com.swiftcheck.aml.plugins.frpep.constitutionalcouncil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture des pages de conseil-constitutionnel.fr.
 *
 * <p>Page des membres : une carte par membre, deux fois (composition, puis placement protocolaire).
 * <pre>
 * &lt;a href="/membres/richard-ferrand" rel="bookmark" title="Richard FERRAND"&gt; …
 *   &lt;div class="title"&gt;Richard FERRAND&lt;/div&gt;
 *   &lt;span class="president-conseil"&gt;Président du Conseil Constitutionnel&lt;/span&gt;
 *   &lt;div class="field field--name-field-texte-nomination …"&gt;Nommé le 20 février 2025 par le Président de la République&lt;/div&gt;
 * </pre>
 * Fiche d'un membre : « A prêté serment le 7 mars 2025 … » et « Né le 1&lt;sup&gt;er&lt;/sup&gt; juillet 1962 à Rodez (Aveyron) ».
 */
final class CouncilPages {

    /** Un membre de la page. {@code slug} = fin de l'adresse de sa fiche ({@code richard-ferrand}). */
    record Member(String slug, String name, boolean president, String nominationDate, String nominatedBy) {
    }

    /** Ce que donne la fiche d'un membre (dates ISO, {@code null} si absentes). */
    record Profile(String oathDate, String birthDate, String birthPlace) {
    }

    private static final Pattern CARD = Pattern.compile("(?s)<a href=\"/membres/([a-z0-9-]+)\"[^>]*>(.*?)</a>");
    private static final Pattern TITLE = Pattern.compile("(?s)<div class=\"title\">(.*?)</div>");
    private static final Pattern NOMINATION = Pattern.compile("(?s)field-texte-nomination[^>]*>(.*?)</div>");
    private static final Pattern OATH = Pattern.compile("(?s)field-texte-serment[^>]*>(.*?)</div>");
    private static final Pattern BIRTH = Pattern.compile("(?s)field-texte-naissance[^>]*>(.*?)</div>");
    /** « Nommée le 1 er mars 2022 par le Président de la République » (après retrait des balises). */
    private static final Pattern DATE = Pattern.compile("(?u)le (\\d{1,2})(?: ?er)? (\\p{L}+) (\\d{4})");
    private static final Pattern BY = Pattern.compile("(?u) par (?:le |la )?(.+)$");
    private static final Pattern PLACE = Pattern.compile("(?u)\\d{4} à ([^(]+?)\\s*(?:\\(|$)");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("fevrier", 2), Map.entry("mars", 3), Map.entry("avril", 4),
            Map.entry("mai", 5), Map.entry("juin", 6), Map.entry("juillet", 7), Map.entry("aout", 8),
            Map.entry("septembre", 9), Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - Conseil constitutionnel)";

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    private CouncilPages() {
    }

    /** Membres en fonction, une fois chacun, dans l'ordre de la page. */
    static List<Member> members(String html) {
        Map<String, Member> members = new LinkedHashMap<>();
        Matcher card = CARD.matcher(html);
        while (card.find()) {
            String body = card.group(2);
            Matcher title = TITLE.matcher(body);
            if (!title.find() || members.containsKey(card.group(1))) {
                continue;
            }
            String nomination = first(NOMINATION, body);
            Matcher by = nomination == null ? null : BY.matcher(nomination);
            members.put(card.group(1), new Member(card.group(1), Html.text(title.group(1)),
                    body.contains("president-conseil"), date(nomination), by != null && by.find() ? by.group(1) : null));
        }
        return new ArrayList<>(members.values());
    }

    static Profile profile(String html) {
        String birth = first(BIRTH, html);
        Matcher place = birth == null ? null : PLACE.matcher(birth);
        return new Profile(date(first(OATH, html)), date(birth), place != null && place.find() ? place.group(1).trim() : null);
    }

    /** « Richard FERRAND » → {« Richard », « FERRAND »} : les mots tout en majuscules forment le nom. */
    static String[] splitName(String name) {
        StringBuilder first = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            StringBuilder target = word.equals(word.toUpperCase(Locale.ROOT)) ? last : first;
            target.append(target.length() > 0 ? " " : "").append(word);
        }
        return new String[] { first.length() == 0 ? null : first.toString(), last.toString() };
    }

    private static String date(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = DATE.matcher(text);
        if (!m.find()) {
            return null;
        }
        Integer month = MONTHS.get(Normalizer.normalize(m.group(2), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT));
        return month == null ? null : String.format("%s-%02d-%02d", m.group(3), month, Integer.parseInt(m.group(1)));
    }

    private static String first(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? Html.text(m.group(1)) : null;
    }

    static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " : " + url);
            }
            return response.body();
        };
    }
}
