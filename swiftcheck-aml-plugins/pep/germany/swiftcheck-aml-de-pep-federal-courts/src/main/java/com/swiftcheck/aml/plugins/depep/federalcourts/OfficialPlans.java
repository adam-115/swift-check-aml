package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Plans de répartition officiels (Geschäftsverteilungspläne) et rapports annuels en PDF.
 * <p>
 * L'adresse d'un plan change à chaque mise à jour ; elle est retrouvée à partir d'une page fixe de la cour en suivant
 * des liens, un motif par étape (paramètre {@code plan.links}). À chaque étape, le lien retenu est le plus grand dans
 * l'ordre alphabétique : les adresses contiennent l'année et la date (« Geschaeftsverteilungsplan2026 »,
 * « gvpl_20261101.pdf », « Geschaeftsverteilungsplan_2026_02 »), et la plus grande est donc la plus récente.
 */
final class OfficialPlans {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    private OfficialPlans() {
    }

    /** Accès HTTP binaire remplaçable dans les tests. */
    interface BytesFetcher {
        byte[] get(String url) throws IOException;
    }

    /** Accès HTTP texte remplaçable dans les tests. */
    interface TextFetcher {
        String get(String url) throws IOException;
    }

    /**
     * Adresse du document final : à partir de {@code startUrl}, suit pour chaque motif le lien le plus récent. Sans
     * motif, {@code startUrl} est l'adresse du document.
     */
    static String discover(String startUrl, List<String> linkPatterns, TextFetcher pages) throws IOException {
        String url = startUrl;
        for (String pattern : linkPatterns == null ? List.<String>of() : linkPatterns) {
            url = latestLink(pages.get(url), url, Pattern.compile(pattern));
        }
        return url;
    }

    /** Lien de la page qui correspond au motif, le plus grand dans l'ordre alphabétique, résolu par rapport à la page. */
    static String latestLink(String html, String baseUrl, Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            if (pattern.matcher(href).find()) {
                found.add(href);
            }
        }
        return found.stream().max(Comparator.naturalOrder())
                .map(h -> URI.create(baseUrl).resolve(h.replace(" ", "%20")).toString())
                .orElseThrow(() -> new IOException("aucun lien « " + pattern + " » sur " + baseUrl));
    }

    /**
     * Texte d'un PDF, trié par position : sans ce tri, les plans en colonnes (BGH) séparent la fonction (« Richterin am
     * Bundesgerichtshof ») du nom placé dans la colonne voisine.
     */
    static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }
}
