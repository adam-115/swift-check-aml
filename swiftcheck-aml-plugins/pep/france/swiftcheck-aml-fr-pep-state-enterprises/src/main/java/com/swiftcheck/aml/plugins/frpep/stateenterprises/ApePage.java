package com.swiftcheck.aml.plugins.frpep.stateenterprises;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page « Les entreprises de l'APE » (economie.gouv.fr) : une section repliable par secteur (Énergie, Industrie,
 * Services et finance, Transports, « Entités où l'État ne détient qu'une seule action »), avec les noms en liste
 * {@code <li>} ou en paragraphe à puces {@code •} séparées par {@code <br>}.
 */
final class ApePage {

    /** Une entreprise listée : son libellé, le secteur, et si l'État n'y détient qu'une action. */
    record Listed(String name, String sector, boolean singleShare) {
    }

    private static final Pattern SECTION = Pattern.compile("<section\\b(.*?)</section>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<button\\b[^>]*>(.*?)</button>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile("<li\\b[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<p\\b[^>]*>(.*?)</p>", Pattern.DOTALL);

    private ApePage() {
    }

    static List<Listed> read(String html) {
        // La liste commence au titre « Les NN entreprises du portefeuille de l'APE » : les sections plus haut sont
        // des menus du site
        int start = html.indexOf("entreprises du portefeuille");
        String body = start < 0 ? html : html.substring(start);
        List<Listed> listed = new ArrayList<>();
        Matcher section = SECTION.matcher(body);
        while (section.find()) {
            Matcher title = TITLE.matcher(section.group(1));
            if (!title.find()) {
                continue;
            }
            String sector = text(title.group(1));
            boolean singleShare = fold(sector).contains("seuleaction");
            String content = section.group(1).substring(title.end());
            List<String> names = new ArrayList<>();
            Matcher item = ITEM.matcher(content);
            while (item.find()) {
                names.add(text(item.group(1)));
            }
            Matcher paragraph = PARAGRAPH.matcher(content);
            while (paragraph.find()) {
                for (String line : paragraph.group(1).split("<br\\s*/?>")) {
                    String name = text(line);
                    if (name.startsWith("•")) {
                        names.add(name.substring(1).trim());
                    }
                }
            }
            names.stream().filter(n -> !n.isEmpty()).forEach(n -> listed.add(new Listed(n, sector, singleShare)));
        }
        return listed;
    }

    /** Texte d'un fragment HTML : sans balises, entités décodées, espaces réduits. */
    static String text(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#039;", "'").replace("&rsquo;", "’")
                .replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Clé de comparaison d'un nom : sans accents, casse, espaces ni ponctuation (apostrophes et tirets compris). */
    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
