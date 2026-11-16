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
 * Plans de répartition officiels (Geschäftsverteilungspläne), lus pour confirmer les juges trouvés sur Wikipédia.
 * Les plans ne donnent que les noms de famille : ils servent à vérifier qu'un juge siège encore, pas à l'identifier.
 * <ul>
 * <li>BVerwG : PDF à adresse fixe ;</li>
 * <li>BGH : page de la répartition, puis page du plan de l'année la plus récente, puis lien du PDF
 * ({@code …pdf?__blob=publicationFile}).</li>
 * </ul>
 */
final class OfficialPlans {

    private static final Pattern BGH_YEAR_PAGE = Pattern.compile(
            "href=\"([^\"]*/Geschaeftsverteilungsplan(\\d{4})/[^\"]*_node\\.html)\"");
    private static final Pattern PDF_LINK = Pattern.compile("href=\"([^\"]*\\.pdf\\?__blob=publicationFile[^\"]*)\"");

    private OfficialPlans() {
    }

    /** Accès HTTP binaire remplaçable dans les tests. */
    interface BytesFetcher {
        byte[] get(String url) throws IOException;
    }

    /** Lien vers la page du plan de l'année la plus récente, résolu par rapport à la page de répartition. */
    static String latestBghYearPage(String html, String baseUrl) throws IOException {
        List<String[]> found = new ArrayList<>();
        Matcher m = BGH_YEAR_PAGE.matcher(html);
        while (m.find()) {
            found.add(new String[] {m.group(1), m.group(2)});
        }
        return found.stream().max(Comparator.comparing(f -> f[1]))
                .map(f -> resolve(baseUrl, f[0]))
                .orElseThrow(() -> new IOException("Aucun lien « Geschaeftsverteilungsplan<année> » sur " + baseUrl));
    }

    /** Premier lien PDF ({@code __blob=publicationFile}) de la page. */
    static String pdfLink(String html, String baseUrl) throws IOException {
        Matcher m = PDF_LINK.matcher(html);
        if (!m.find()) {
            throw new IOException("Aucun lien PDF sur " + baseUrl);
        }
        return resolve(baseUrl, m.group(1));
    }

    static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String resolve(String base, String href) {
        return URI.create(base).resolve(href.replace("&amp;", "&")).toString();
    }
}
