package com.swiftcheck.aml.plugins.frpep.internationalorgs;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portrait d'un dirigeant dans la page officielle de son organisation, selon le mode {@code photo} de la page dans
 * {@code configJson} :
 * <ul>
 *   <li>{@code card} : une image dont le texte alternatif est le nom ({@code <img alt="Anthony Donnellan">}), sinon
 *       une carte où l'image est suivie du nom en titre, sans autre image entre les deux ;</li>
 *   <li>{@code og:image} : l'image de partage d'une page consacrée à une seule personne (directeur général) ; jamais
 *       utilisée si la page nomme plusieurs dirigeants.</li>
 * </ul>
 * Jamais de photo devinée : un nom illustré par deux images différentes n'en a aucune, et une image dont le nom de
 * fichier cite un autre dirigeant de la page (photo commune, « photo-officielle-pascale-trimbach-tobias-butow ») est
 * écartée.
 */
final class PagePhotos {

    static final String CARD = "card";
    static final String OG_IMAGE = "og:image";

    private static final Pattern IMG = Pattern.compile("(?si)<img\\b([^>]*)>");
    private static final Pattern SRC = Pattern.compile("(?i)\\ssrc=\"([^\"]+)\"");
    private static final Pattern ALT = Pattern.compile("(?i)\\s(?:alt|title)=\"([^\"]+)\"");
    /** Image puis titre, sans autre image entre les deux. */
    private static final Pattern CARD_TITLE = Pattern.compile(
            "(?si)<img\\b[^>]*\\ssrc=\"([^\"]+)\"[^>]*>(?:(?!<img\\b).){0,3000}?<h[2-5][^>]*>(.*?)</h[2-5]>");
    private static final Pattern OG = Pattern.compile(
            "(?i)<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"");

    private PagePhotos() {
    }

    /**
     * Adresse absolue (https) du portrait de {@code name}, ou {@code null}.
     *
     * @param leadersOnPage dirigeants trouvés dans la page, {@code name} compris (le mode {@code og:image} exige
     *                      qu'il soit le seul)
     */
    static String find(String mode, String html, String pageUrl, String name, Collection<String> leadersOnPage) {
        if (mode == null) {
            return null;
        }
        String result = candidate(mode, html, pageUrl, name, leadersOnPage.size());
        if (result == null) {
            return null;
        }
        String file = FrPepInternationalOrgsParser.fold(result.replaceAll("\\?.*", "").replaceAll(".*/", ""))
                .replaceAll("[^a-z0-9]+", " ");
        for (String other : leadersOnPage) {
            String[] words = FrPepInternationalOrgsParser.fold(other).replaceAll("[^a-z0-9 ]+", " ").trim().split("\\s+");
            String last = words[words.length - 1];
            if (!FrPepInternationalOrgsParser.fold(other).equals(FrPepInternationalOrgsParser.fold(name))
                    && last.length() > 2 && (" " + file + " ").contains(" " + last + " ")) {
                return null;        // photo commune avec un autre dirigeant
            }
        }
        return result;
    }

    private static String candidate(String mode, String html, String pageUrl, String name, int leadersOnPage) {
        String key = FrPepInternationalOrgsParser.fold(name);
        Set<String> found = new LinkedHashSet<>();
        if (CARD.equals(mode)) {
            Matcher img = IMG.matcher(html);
            while (img.find()) {
                Matcher alt = ALT.matcher(img.group(1));
                Matcher src = SRC.matcher(img.group(1));
                while (alt.find()) {
                    if (src.find(0) && key.equals(FrPepInternationalOrgsParser.fold(PageText.of(alt.group(1)).trim()))) {
                        found.add(absolute(pageUrl, src.group(1)));
                    }
                }
            }
            if (found.isEmpty()) {
                Matcher card = CARD_TITLE.matcher(html);
                while (card.find()) {
                    if (key.equals(FrPepInternationalOrgsParser.fold(PageText.of(card.group(2)).trim()))) {
                        found.add(absolute(pageUrl, card.group(1)));
                    }
                }
            }
        } else if (OG_IMAGE.equals(mode) && leadersOnPage == 1) {
            Matcher og = OG.matcher(html);
            if (og.find()) {
                found.add(absolute(pageUrl, og.group(1)));
            }
        }
        found.remove(null);
        return found.size() == 1 ? found.iterator().next() : null;
    }

    /** Adresse absolue en https ; {@code null} pour une image intégrée ({@code data:}) ou une adresse illisible. */
    private static String absolute(String pageUrl, String src) {
        try {
            String url = URI.create(pageUrl).resolve(src.replace("&amp;", "&").replace(" ", "%20")).toString();
            return url.startsWith("https://") ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
