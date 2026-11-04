package com.swiftcheck.aml.plugins.bepep.king;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Contrôle du Roi par le Moniteur belge (source primaire, ejustice.just.fgov.be).
 *
 * <p>Le numéro du Moniteur du 21 juillet 2013 publie la loi et l'arrêté royal signés par « PHILIPPE, Roi des
 * Belges » et mentionne l'abdication d'Albert II le même jour. Ce document fait foi pour l'avènement : le nom du
 * Roi trouvé dans Wikidata et sa date d'accession doivent y figurer. Au prochain règne, le paramètre
 * {@code moniteurUrl} doit désigner le numéro de l'avènement du nouveau Roi ; tant qu'il ne change pas,
 * l'exécution s'arrête plutôt que de publier un titulaire non confirmé.
 */
final class MoniteurCheck {

    private static final String[] FR_MONTHS = { "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
            "septembre", "octobre", "novembre", "décembre" };
    private static final String[] NL_MONTHS = { "januari", "februari", "maart", "april", "mei", "juni", "juli",
            "augustus", "september", "oktober", "november", "december" };

    private MoniteurCheck() {
    }

    /** Texte de toutes les pages du PDF du Moniteur. */
    static String pdfText(Path pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf.toFile())) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    /**
     * @param text      texte du numéro du Moniteur
     * @param firstName prénom du Roi d'après Wikidata
     * @param start     date d'accession d'après Wikidata (aaaa-mm-jj)
     * @throws IllegalStateException si le Moniteur ne confirme pas le nom ou la date
     */
    static void verify(String text, String firstName, String start, String moniteurUrl) {
        if (start == null || start.length() < 10) {
            throw new IllegalStateException("Wikidata ne donne pas la date d'accession du Roi : elle ne peut pas être "
                    + "confirmée par le Moniteur belge.");
        }
        Pattern signature = Pattern.compile(Pattern.quote(firstName) + "\\s*,\\s*Roi des Belges",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        if (!signature.matcher(text).find()) {
            throw new IllegalStateException("Le Moniteur belge (" + moniteurUrl + ") ne contient pas « "
                    + firstName.toUpperCase(Locale.ROOT) + ", Roi des Belges » : Wikidata et le Moniteur ne désignent "
                    + "pas le même Roi. Vérifier un changement de règne et le paramètre moniteurUrl.");
        }
        LocalDate date = LocalDate.parse(start.substring(0, 10));
        String fr = date.getDayOfMonth() + " " + FR_MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
        String nl = date.getDayOfMonth() + " " + NL_MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains(fr) && !lower.contains(nl)) {
            throw new IllegalStateException("Le Moniteur belge (" + moniteurUrl + ") ne mentionne pas la date "
                    + "d'accession donnée par Wikidata (" + fr + ").");
        }
    }
}
