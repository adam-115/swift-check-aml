package com.swiftcheck.aml.plugins.lupep.associated;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Lecture d'une déclaration d'intérêts financiers d'un député (formulaire du Code de conduite, art. 4).
 *
 * <p>Deux sections sont lues :
 * <ul>
 *   <li><b>E)</b> participation aux comités ou conseils d'administration d'entreprises, ONG, associations… ;</li>
 *   <li><b>G)</b> participations directes ou indirectes dans une société, en deux tableaux : avec des
 *       répercussions possibles sur la politique publique, et conférant une influence significative.</li>
 * </ul>
 * Chaque tableau commence par un en-tête (« Catégories de revenus », « Participation(s)… ») suivi des lignes
 * numérotées « 1. Nom de l'organisme ». Les croix « X » de la colonne des revenus, les numéros de page et les
 * lignes vides du formulaire sont retirés.
 *
 * <p>Une partie des déclarations sont des documents scannés, sans texte : {@link Declaration#scanned()} est
 * alors vrai et aucune ligne n'est lue. Les déclarations scannées puis passées à l'OCR sont lues, mais le texte
 * peut contenir des erreurs de reconnaissance.
 */
final class DeclarationReader {

    /** En dessous, le PDF n'a pas de couche texte exploitable (document scanné). */
    static final int MIN_TEXT_LENGTH = 300;

    /** Type de tableau de la section G. */
    enum HoldingKind {
        /** Participation avec des répercussions possibles sur la politique publique. */
        PUBLIC_POLICY,
        /** Participation conférant une influence significative. */
        SIGNIFICANT_INFLUENCE
    }

    record Holding(HoldingKind kind, String text) {
    }

    record Declaration(boolean scanned, boolean sectionsFound, List<String> mandates, List<Holding> holdings) {

        static Declaration scan() {
            return new Declaration(true, false, List.of(), List.of());
        }
    }

    /** Début d'une ligne numérotée : « 1. », « 12) ». Pas « 50.001- » (montant de l'en-tête). */
    private static final Pattern ENTRY = Pattern.compile("^(\\d{1,2})\\s*[.)](?:\\s+(.*))?$");
    private static final Pattern TABLE_HEADER = Pattern.compile("^(Catégories de revenus|Participation\\(s\\))");
    /** Croix de la colonne des revenus (« X » ou « x » isolé). */
    private static final Pattern CROSS = Pattern.compile("(^|\\s)[Xx](?=\\s|$)");
    /** Lignes laissées vides ou déclarées sans objet. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?iu)^(?:[-–/.,)]+|n[ée]ant|non applicable|aucune?|sans objet|n/a|pas de .*|ja|oui|non|nee|yes|no)$");
    private static final Pattern GLUED_ENTRY = Pattern.compile("\\s\\d{1,2}\\.(?=\\p{L})");

    private DeclarationReader() {
    }

    static Declaration read(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return parse(stripper.getText(doc));
        }
    }

    static Declaration parse(String text) {
        if (text.replaceAll("\\s+", "").length() < MIN_TEXT_LENGTH) {
            return Declaration.scan();
        }
        String e = section(text, 'E', 'F');
        String g = section(text, 'G', 'H');
        List<String> mandates = new ArrayList<>();
        List<Holding> holdings = new ArrayList<>();
        if (e != null) {
            tables(e).forEach(t -> mandates.addAll(t.entries));
        }
        if (g != null) {
            List<Table> tables = tables(g);
            for (int i = 0; i < tables.size(); i++) {
                Table t = tables.get(i);
                HoldingKind kind = t.header.contains("influence") ? HoldingKind.SIGNIFICANT_INFLUENCE
                        : t.header.contains("politique") ? HoldingKind.PUBLIC_POLICY
                        : i == 0 ? HoldingKind.PUBLIC_POLICY : HoldingKind.SIGNIFICANT_INFLUENCE;
                t.entries.forEach(entry -> holdings.add(new Holding(kind, entry)));
            }
        }
        return new Declaration(false, e != null || g != null, mandates, holdings);
    }

    /**
     * Texte de la section {@code letter}, jusqu'à la section {@code next}. Les titres de section commencent par
     * « E) conformément à l'article 4… » ; l'OCR déforme souvent le mot (« conforn,é,nent »), seul « con » est exigé.
     */
    static String section(String text, char letter, char next) {
        Matcher start = Pattern.compile("(?m)^" + letter + " ?\\)\\s*con").matcher(text);
        if (!start.find()) {
            return null;
        }
        Matcher end = Pattern.compile("(?m)^" + next + " ?\\)\\s*con").matcher(text);
        return end.find(start.end()) ? text.substring(start.start(), end.start()) : text.substring(start.start());
    }

    /** Un tableau : le texte de son en-tête et ses lignes. */
    private static final class Table {
        final StringBuilder headerText = new StringBuilder();
        final List<String> entries = new ArrayList<>();
        String header = "";
    }

    private static List<Table> tables(String section) {
        List<Table> tables = new ArrayList<>();
        Table current = null;
        boolean inHeader = false;
        StringBuilder entry = null;
        for (String raw : section.split("\\R")) {
            String line = raw.replace(' ', ' ').trim();
            Matcher header = TABLE_HEADER.matcher(line);
            // Un en-tête s'étend sur plusieurs lignes ; le même intitulé une seconde fois ouvre le tableau suivant
            // (le premier tableau a pu rester sans ligne numérotée : « Non applicable »)
            if (header.find() && !(current != null && inHeader && current.headerText.indexOf(header.group(1)) < 0)) {
                add(current, entry);
                entry = null;
                current = new Table();
                tables.add(current);
                inHeader = true;
            }
            if (current == null || line.isEmpty() || line.matches("\\d+")) {
                continue;                                   // avant le premier tableau, ligne vide, numéro de page
            }
            if (line.startsWith("*")) {                     // note de bas de page : termine la ligne en cours
                add(current, entry);
                entry = null;
                continue;
            }
            Matcher m = ENTRY.matcher(line);
            if (m.matches()) {
                if (inHeader) {
                    current.header = current.headerText.toString();
                    inHeader = false;
                }
                add(current, entry);
                entry = new StringBuilder(m.group(2) == null ? "" : m.group(2));
            } else if (inHeader) {
                current.headerText.append(' ').append(line);
            } else if (entry != null) {
                entry.append(' ').append(line);
            }
        }
        add(current, entry);
        for (Table t : tables) {
            if (t.header.isEmpty()) {
                t.header = t.headerText.toString();
            }
        }
        return tables;
    }

    private static void add(Table table, StringBuilder entry) {
        if (table == null || entry == null) {
            return;
        }
        // Numéros collés au texte précédent quand deux lignes du tableau se suivent de près : « … Dudelange 7.ONGD »
        for (String part : GLUED_ENTRY.split(entry)) {
            String text = clean(part);
            if (!text.isEmpty() && !PLACEHOLDER.matcher(text).matches()) {
                table.entries.add(text);
            }
        }
    }

    static String clean(String s) {
        return CROSS.matcher(s).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.;:)])", "$1")
                .replaceAll("\\(\\s+", "(")
                .trim()
                .replaceAll("^[-–•]\\s*", "")
                .replaceAll("\\s+0(?:[.,]-?|\\s*€)?$", ""); // revenu « 0 », « 0.- » resté en fin de ligne
    }
}
